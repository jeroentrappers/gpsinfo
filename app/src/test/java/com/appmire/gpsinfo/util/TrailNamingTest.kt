package com.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TrailNamingTest {

    /** A known epoch chosen so the formatted string is stable across runners. */
    private val knownEpoch: Long = run {
        val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(2026, Calendar.MAY, 19, 14, 7, 0); set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }

    @Test fun default_trail_name_includes_prefix_and_timestamp() {
        val name = TrailNaming.defaultTrailName(knownEpoch)
        assertTrue("expected 'Trail ' prefix, got: $name", name.startsWith("Trail "))
        assertTrue("expected '2026-05-19' in $name", name.contains("2026-05-19"))
        // SimpleDateFormat uses the JVM default tz; we assert only the
        // date portion so this is stable on CI runners in any zone.
    }

    @Test fun timestamped_uses_at_separator() {
        val name = TrailNaming.timestamped("Waypoint", knownEpoch)
        assertTrue("expected '@' separator, got: $name", " @ " in name)
        assertTrue(name.startsWith("Waypoint "))
    }

    @Test fun empty_prefix_still_well_formed() {
        // Edge case from the dashboard: stringResource(...) can yield ""
        // briefly during recomposition. We don't crash, we don't produce
        // a leading "@".
        val name = TrailNaming.timestamped("", knownEpoch)
        assertTrue("expected ' @ ' separator even for empty prefix", " @ " in name)
        assertEquals(' ', name.first())
    }
}
