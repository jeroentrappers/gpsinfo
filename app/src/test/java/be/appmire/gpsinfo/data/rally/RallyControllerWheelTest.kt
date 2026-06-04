package be.appmire.gpsinfo.data.rally

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multi-sensor wheel-distance behaviour of [RallyController], driven
 * through the injectable monotonic clock (JVM tests can't touch
 * SystemClock). GPS paths aren't covered here — android.location is
 * a stub off-device; the wheel path is pure arithmetic.
 *
 * Geometry note: nominal circumference is 1.95 m, so 100 revs = 195 m.
 */
class RallyControllerWheelTest {

    private var fakeNow = 0L

    private fun stage() = RegularityStage(
        id = "t",
        name = "test",
        changes = listOf(SpeedChange(0.0, 36.0)),
        lengthKm = 10.0,
    )

    private fun running(): RallyState.Running =
        RallyController.state.value as RallyState.Running

    @Before
    fun setUp() {
        fakeNow = 1_000_000L
        RallyController.elapsedRealtime = { fakeNow }
        // The controller is a process singleton — calibration factors
        // deliberately survive start(); tests must not share them.
        RallyController.resetCalibration()
        RallyController.arm(stage())
        RallyController.start(nowMillis = 0L)
    }

    @Test
    fun `single sensor integrates circumference distance`() {
        RallyController.offerWheelRevolutions("A", 1_000L) // rebase only
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 1_100L) // +100 revs
        assertEquals(0.195, running().drivenKm, 1e-9)
        assertEquals(1, running().wheelSensorsFresh)
    }

    @Test
    fun `two fresh sensors average toward the centreline`() {
        // Rebase both.
        RallyController.offerWheelRevolutions("L", 0L)
        RallyController.offerWheelRevolutions("R", 0L)
        fakeNow += 1_000
        // Outer wheel 110 revs, inner 90 — centreline is 100 revs.
        RallyController.offerWheelRevolutions("L", 110L)
        RallyController.offerWheelRevolutions("R", 90L)
        assertEquals(100 * 1.95 / 1000.0, running().drivenKm, 1e-9)
        assertEquals(2, running().wheelSensorsFresh)
    }

    @Test
    fun `stale sensor drops out of the average`() {
        RallyController.offerWheelRevolutions("L", 0L)
        RallyController.offerWheelRevolutions("R", 0L)
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("L", 100L)
        RallyController.offerWheelRevolutions("R", 100L)
        val both = running().drivenKm

        // R goes silent past the freshness window; L carries on alone
        // and must now contribute its full distance — two deltas of
        // 100 revs each land after the dropout.
        fakeNow += 10_000
        RallyController.offerWheelRevolutions("L", 200L)
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("L", 300L)
        assertEquals(1, running().wheelSensorsFresh)
        assertEquals(both + 200 * 1.95 / 1000.0, running().drivenKm, 1e-9)
    }

    @Test
    fun `counter reset rebases without distance jump`() {
        RallyController.offerWheelRevolutions("A", 500_000L)
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 500_100L)
        val before = running().drivenKm
        // Battery swap: counter restarts near zero. No jump allowed.
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 3L)
        assertEquals(before, running().drivenKm, 1e-9)
        // And normal counting resumes from the rebased value.
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 103L)
        assertEquals(before + 100 * 1.95 / 1000.0, running().drivenKm, 1e-9)
    }

    @Test
    fun `nudge corrects distance while wheels feed it`() {
        // 300-rev steps stay under the 600-rev counter-reset cap.
        RallyController.offerWheelRevolutions("A", 0L)
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 300L) // 0.585 km raw
        val before = running().drivenKm
        assertEquals(0.585, before, 1e-9)
        RallyController.nudge(10.0)
        assertEquals(before + 0.010, running().drivenKm, 1e-9)
        // With ≥0.5 km of raw wheel distance the correction folds into
        // the wheel factor: subsequent revs scale slightly up.
        fakeNow += 1_000
        RallyController.offerWheelRevolutions("A", 600L)
        assertTrue(running().drivenKm > before + 0.010 + 0.585)
    }
}
