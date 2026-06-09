package be.appmire.gpsinfo.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdProfilesTest {

    private fun decode(role: ObdRole, payload: IntArray): Double? =
        ObdProfiles.VW_MEB.commands.first { it.role == role }.command.decode(payload)

    @Test
    fun `MEB SoC scales by 0_4`() {
        assertEquals(80.0, decode(ObdRole.BATTERY_SOC, intArrayOf(0xC8))!!, 0.001) // 200 * 0.4
    }

    @Test
    fun `MEB power is signed kW per 100`() {
        assertEquals(50.0, decode(ObdRole.POWER_KW, intArrayOf(0x13, 0x88))!!, 0.001) // 5000/100
        // 0xFF9C = -100 → regen -1.0 kW
        assertEquals(-1.0, decode(ObdRole.POWER_KW, intArrayOf(0xFF, 0x9C))!!, 0.001)
    }

    @Test
    fun `MEB range is plain km`() {
        assertEquals(300.0, decode(ObdRole.RANGE_KM, intArrayOf(0x01, 0x2C))!!, 0.001)
    }

    @Test
    fun `MEB current signed tenths of amp`() {
        assertEquals(-12.0, decode(ObdRole.HV_CURRENT, intArrayOf(0xFF, 0x88))!!, 0.001) // -120 * 0.1
    }

    @Test
    fun `short payload decodes null`() {
        assertNull(decode(ObdRole.POWER_KW, intArrayOf(0x13)))
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
