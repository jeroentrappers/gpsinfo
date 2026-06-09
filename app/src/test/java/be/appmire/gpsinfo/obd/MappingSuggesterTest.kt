package be.appmire.gpsinfo.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingSuggesterTest {

    private fun r(role: ObdRole, profile: String, req: String, live: Boolean, value: Double? = 1.0) =
        RoleReading(role, profile, req, if (live) value else null, if (live) "00" else null, live)

    @Test
    fun `make profile chosen when its energy roles are live`() {
        val readings = listOf(
            r(ObdRole.SPEED, "generic", "010D", live = true),
            r(ObdRole.BATTERY_SOC, "vw_meb", "ATSH7E5;ATCRA7ED;22028C", live = true),
            r(ObdRole.POWER_KW, "vw_meb", "ATSH7E5;ATCRA7ED;221E32", live = true),
            r(ObdRole.RANGE_KM, "vw_meb", "ATSH7E5;ATCRA7ED;2222D7", live = false),
        )
        val s = MappingSuggester.suggest("VIN123", readings)
        assertEquals("vw_meb", s.profileId)
        val m = s.mapping!!
        // SoC + power from the make profile; speed falls back to generic.
        assertEquals("ATSH7E5;ATCRA7ED;22028C", m.roles[ObdRole.BATTERY_SOC])
        assertEquals("ATSH7E5;ATCRA7ED;221E32", m.roles[ObdRole.POWER_KW])
        assertEquals("010D", m.roles[ObdRole.SPEED])
        // Range wasn't live → not mapped.
        assertTrue(ObdRole.RANGE_KM !in m.roles)
        assertEquals("VIN123", m.vehicleKey)
    }

    @Test
    fun `falls back to generic when no make energy roles are live`() {
        val readings = listOf(
            r(ObdRole.SPEED, "generic", "010D", live = true),
            r(ObdRole.COOLANT_TEMP, "generic", "0105", live = true),
            r(ObdRole.POWER_KW, "vw_meb", "ATSH7E5;ATCRA7ED;221E32", live = false),
        )
        val s = MappingSuggester.suggest("MAC", readings)
        assertEquals("generic", s.profileId)
        assertEquals("010D", s.mapping!!.roles[ObdRole.SPEED])
    }

    @Test
    fun `no live readings yields null mapping`() {
        val readings = listOf(r(ObdRole.SPEED, "generic", "010D", live = false))
        assertNull(MappingSuggester.suggest("k", readings).mapping)
    }
}
