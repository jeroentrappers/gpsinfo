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
    fun `MEB HV temp is byte over 2 minus 40`() {
        assertEquals(10.0, decode(ObdRole.HV_TEMP, intArrayOf(0x64))!!, 0.001) // 100/2-40
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
}
