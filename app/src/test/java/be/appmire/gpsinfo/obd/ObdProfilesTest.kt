package be.appmire.gpsinfo.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProfilesTest {

    private fun decode(role: ObdRole, payload: IntArray): Double? =
        ObdProfiles.VW_MEB.commands.first { it.role == role }.command.decode(payload)

    @Test
    fun `MEB SoC is byte over 2_5`() {
        assertEquals(80.0, decode(ObdRole.BATTERY_SOC, intArrayOf(0xC8))!!, 0.001) // 200 / 2.5
    }

    @Test
    fun `MEB HV voltage is u16 over 4`() {
        assertEquals(400.0, decode(ObdRole.HV_VOLTAGE, intArrayOf(0x06, 0x40))!!, 0.001) // 1600/4
    }

    @Test
    fun `MEB HV current is u32 minus 150000 over 100`() {
        // 145000 → (145000-150000)/100 = -50 A (discharge / driving)
        assertEquals(-50.0, decode(ObdRole.HV_CURRENT, intArrayOf(0x00, 0x02, 0x36, 0x68))!!, 0.001)
        // 160000 → +100 A (charge / regen)
        assertEquals(100.0, decode(ObdRole.HV_CURRENT, intArrayOf(0x00, 0x02, 0x71, 0x00))!!, 0.001)
    }

    @Test
    fun `MEB HV temp is u16 over 64`() {
        assertEquals(40.0, decode(ObdRole.HV_TEMP, intArrayOf(0x0A, 0x00))!!, 0.001) // 2560/64
    }

    @Test
    fun `MEB outside temp is byte over 2 minus 50`() {
        assertEquals(5.0, decode(ObdRole.AMBIENT_TEMP, intArrayOf(0x6E))!!, 0.001) // 110/2-50
    }

    @Test
    fun `MEB uses 29-bit BMS header`() {
        val soc = ObdProfiles.VW_MEB.commands.first { it.role == ObdRole.BATTERY_SOC }.command
        assertTrue(soc.request.startsWith("ATSP7;ATSH17FC007B;ATCRA17FE007B;"))
        assertTrue(soc.request.endsWith("22028C"))
    }

    @Test
    fun `short current payload decodes null`() {
        assertNull(decode(ObdRole.HV_CURRENT, intArrayOf(0x13)))
    }

    @Test
    fun `VIN WMI hints the VW profile, others null`() {
        assertEquals(ObdProfiles.VW_MEB.id, ObdProfiles.hintForVin("WVGZZZ1TZNP000123")?.id)
        assertNull(ObdProfiles.hintForVin("1HGCM82633A004352")) // Honda → no make profile
        assertNull(ObdProfiles.hintForVin(null))
    }

    @Test
    fun `generic profile maps standard PIDs to roles`() {
        val speed = ObdProfiles.GENERIC.roleCommands.first { it.role == ObdRole.SPEED }
        assertEquals("010D", speed.command.request)
    }

    // ── Hyundai / Kia (multi-frame 220101/220105 byte offsets) ──

    private fun dec(p: VehicleProfile, role: ObdRole, payload: IntArray): Double? =
        p.commands.first { it.role == role }.command.decode(payload)

    @Test
    fun `Hyundai 220101 decodes voltage current temp at offsets`() {
        // 16-byte payload: [10..11]=current, [12..13]=voltage, [14]=temp
        val b = IntArray(16)
        b[10] = 0xFF; b[11] = 0x38 // s16 0xFF38 = -200 → -20.0 A (discharge)
        b[12] = 0x0F; b[13] = 0x00 // 3840 → 384.0 V
        b[14] = 0x19 //                 25 °C
        assertEquals(-20.0, dec(ObdProfiles.HYUNDAI_KIA, ObdRole.HV_CURRENT, b)!!, 0.001)
        assertEquals(384.0, dec(ObdProfiles.HYUNDAI_KIA, ObdRole.HV_VOLTAGE, b)!!, 0.001)
        assertEquals(25.0, dec(ObdProfiles.HYUNDAI_KIA, ObdRole.HV_TEMP, b)!!, 0.001)
    }

    @Test
    fun `Hyundai 220105 SoC at byte 31`() {
        val b = IntArray(32)
        b[31] = 0x9C // 156 / 2 = 78 %
        assertEquals(78.0, dec(ObdProfiles.HYUNDAI_KIA, ObdRole.BATTERY_SOC, b)!!, 0.001)
    }

    @Test
    fun `Hyundai uses 7E4 BMS header with flow control and 7B3 for ambient`() {
        val v = ObdProfiles.HYUNDAI_KIA.commands.first { it.role == ObdRole.HV_VOLTAGE }.command
        assertTrue(v.request.startsWith("ATSP6;ATSH7E4;ATCRA7EC;ATFCSH7E4"))
        assertTrue(v.request.endsWith("220101"))
        val amb = ObdProfiles.HYUNDAI_KIA.commands.first { it.role == ObdRole.AMBIENT_TEMP }.command
        assertTrue(amb.request.startsWith("ATSP6;ATSH7B3;ATCRA7BB;"))
    }

    // ── BMW (i3 DDBC vs G-platform E5CE; shared SME voltage/current/temp) ──

    @Test
    fun `BMW i3 SoC is u16 over 10, G-platform over 100`() {
        assertEquals(100.0, dec(ObdProfiles.BMW_I3, ObdRole.BATTERY_SOC, intArrayOf(0x03, 0xE8))!!, 0.001)
        assertEquals(50.0, dec(ObdProfiles.BMW_G, ObdRole.BATTERY_SOC, intArrayOf(0x13, 0x88))!!, 0.001)
    }

    @Test
    fun `BMW HV voltage u16 over 100, current s32 over 100`() {
        assertEquals(260.30, dec(ObdProfiles.BMW_I3, ObdRole.HV_VOLTAGE, intArrayOf(0x65, 0xAE))!!, 0.001)
        // 0xFFFFFECC = -308 → -3.08 A (discharge negative)
        assertEquals(-3.08, dec(ObdProfiles.BMW_I3, ObdRole.HV_CURRENT, intArrayOf(0xFF, 0xFF, 0xFE, 0xCC))!!, 0.001)
    }

    @Test
    fun `BMW HV temp is signed s16 at offset 2 over 100`() {
        // DDC0: [0..1]=Tmin, [2..3]=Tmax → use Tmax; 0xFF9C = -100 → -1.0 °C
        assertEquals(-1.0, dec(ObdProfiles.BMW_I3, ObdRole.HV_TEMP, intArrayOf(0x00, 0x00, 0xFF, 0x9C))!!, 0.001)
    }

    @Test
    fun `BMW ambient via KOM is byte over 2 minus 40`() {
        assertEquals(6.0, dec(ObdProfiles.BMW_I3, ObdRole.AMBIENT_TEMP, intArrayOf(0x5C))!!, 0.001) // 92/2-40
    }

    @Test
    fun `BMW uses 6F1 tester with extended addressing`() {
        val soc = ObdProfiles.BMW_I3.commands.first { it.role == ObdRole.BATTERY_SOC }.command
        assertTrue(soc.request.startsWith("ATSP6;ATSH6F1;ATCEA07;ATCRA607;"))
        assertTrue(soc.request.endsWith("22DDBC"))
        val amb = ObdProfiles.BMW_I3.commands.first { it.role == ObdRole.AMBIENT_TEMP }.command
        assertTrue(amb.request.startsWith("ATSP6;ATSH6F1;ATCEA60;ATCRA660;"))
    }
}
