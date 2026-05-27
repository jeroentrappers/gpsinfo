package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailScoringTest {

    @Test fun perfect_pace_scores_100() {
        // Target 5:00 /km = 300 s/km. Actual 5:00 /km too → 12 km/h.
        val s = TrailScoring.scoreFromPace(
            avgSpeedKmh = 12f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
        )!!
        assertEquals(100, s.overall)
        assertEquals(0f, s.deltaSecondsPerUnit, 1f)
    }

    @Test fun slower_pace_loses_points() {
        // Target 5:00 /km (300 s/km). Actual ~5:30 /km → ~30 s slower.
        // Score lands near 70 — exact value depends on float math.
        val s = TrailScoring.scoreFromPace(
            avgSpeedKmh = 10.91f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
        )!!
        assertTrue("expected ~70, got ${s.overall}", s.overall in 65..75)
    }

    @Test fun faster_pace_also_loses_points() {
        // Same scoring magnitude for "too fast" as "too slow" — the
        // goal is to *hit* the target, over-shooting is also a miss.
        val s = TrailScoring.scoreFromPace(
            avgSpeedKmh = 13.33f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
        )!!
        assertTrue("expected ~70, got ${s.overall}", s.overall in 65..75)
    }

    @Test fun very_off_clamps_to_zero() {
        // Target 5:00 /km, actual 8:00 /km (~7.5 km/h) → +180 s delta.
        // Penalty would be 180; score floors at 0.
        val s = TrailScoring.scoreFromPace(
            avgSpeedKmh = 7.5f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
        )!!
        assertEquals(0, s.overall)
    }

    @Test fun stationary_returns_null() {
        assertNull(TrailScoring.scoreFromPace(0f, 300f, UnitSystem.Metric))
        assertNull(TrailScoring.scoreFromPace(0.3f, 300f, UnitSystem.Metric))
    }

    @Test fun missing_goal_returns_null() {
        assertNull(TrailScoring.scoreFromPace(12f, null, UnitSystem.Metric))
    }

    // Combined pace + HR scoring.

    @Test fun combined_falls_back_to_pace_only_with_no_hr() {
        val s = TrailScoring.scoreCombined(
            avgSpeedKmh = 12f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
            timeInZoneSeconds = IntArray(0),
        )!!
        // With no HR data, the combined score equals the pace score.
        assertEquals(s.paceScore, s.overall)
        assertNull(s.hrScore)
    }

    @Test fun combined_perfect_pace_and_full_target_zone_scores_100() {
        // 100% time in Z3 (in the Z2-Z4 training band), perfect pace.
        val s = TrailScoring.scoreCombined(
            avgSpeedKmh = 12f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
            timeInZoneSeconds = intArrayOf(0, 0, 1800, 0, 0),  // 30 min Z3
        )!!
        assertEquals(100, s.paceScore)
        assertEquals(100, s.hrScore)
        assertEquals(100, s.overall)
    }

    @Test fun combined_blends_pace_and_hr() {
        // Pace ~70, HR 50% in target → combined ~60 at 50/50 weight.
        val s = TrailScoring.scoreCombined(
            avgSpeedKmh = 10.91f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
            timeInZoneSeconds = intArrayOf(0, 900, 0, 0, 900),  // half Z2, half Z5
        )!!
        assertTrue("paceScore ~70, got ${s.paceScore}", s.paceScore in 65..75)
        assertEquals(50, s.hrScore)
        assertTrue("overall blended ~60, got ${s.overall}", s.overall in 55..65)
    }

    @Test fun combined_ignores_recovery_z1_time() {
        // 100% in Z1 — outside the training band → 0 HR score.
        val s = TrailScoring.scoreCombined(
            avgSpeedKmh = 12f,
            targetPaceSecondsPerUnit = 300f,
            unitSystem = UnitSystem.Metric,
            timeInZoneSeconds = intArrayOf(1800, 0, 0, 0, 0),
        )!!
        assertEquals(0, s.hrScore)
        // Overall = 0.5 * 100 (pace) + 0.5 * 0 (hr) = 50
        assertEquals(50, s.overall)
    }
}
