package be.appmire.gpsinfo.data.rally

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegularityStageTest {

    private fun stage(vararg changes: Pair<Double, Double>, lengthKm: Double? = null) =
        RegularityStage(
            id = "t",
            name = "test",
            changes = changes.map { SpeedChange(it.first, it.second) },
            lengthKm = lengthKm,
        )

    // ── targetSpeedKmhAt ───────────────────────────────────────────

    @Test
    fun `single segment speed holds everywhere`() {
        val s = stage(0.0 to 36.0)
        assertEquals(36.0, s.targetSpeedKmhAt(0.0), 1e-9)
        assertEquals(36.0, s.targetSpeedKmhAt(99.0), 1e-9)
    }

    @Test
    fun `speed changes apply from their breakpoint`() {
        val s = stage(0.0 to 36.0, 2.0 to 48.0)
        assertEquals(36.0, s.targetSpeedKmhAt(1.99), 1e-9)
        assertEquals(48.0, s.targetSpeedKmhAt(2.0), 1e-9)
        assertEquals(48.0, s.targetSpeedKmhAt(5.0), 1e-9)
    }

    // ── targetElapsedSecondsAt ─────────────────────────────────────

    @Test
    fun `constant 36 kmh means 100 seconds per km`() {
        val s = stage(0.0 to 36.0)
        // 36 km/h = 10 m/s → 1 km in 100 s.
        assertEquals(100.0, s.targetElapsedSecondsAt(1.0), 1e-6)
        assertEquals(250.0, s.targetElapsedSecondsAt(2.5), 1e-6)
    }

    @Test
    fun `piecewise integration across a speed change`() {
        // 2 km at 36 (200 s) then 48 km/h (75 s per km).
        val s = stage(0.0 to 36.0, 2.0 to 48.0)
        assertEquals(200.0, s.targetElapsedSecondsAt(2.0), 1e-6)
        assertEquals(275.0, s.targetElapsedSecondsAt(3.0), 1e-6)
    }

    @Test
    fun `mid-segment distance integrates partially`() {
        val s = stage(0.0 to 36.0, 2.0 to 48.0)
        // 2 km @36 = 200 s, plus 0.5 km @48 = 37.5 s.
        assertEquals(237.5, s.targetElapsedSecondsAt(2.5), 1e-6)
    }

    @Test
    fun `zero and negative distance are time zero`() {
        val s = stage(0.0 to 36.0)
        assertEquals(0.0, s.targetElapsedSecondsAt(0.0), 1e-9)
        assertEquals(0.0, s.targetElapsedSecondsAt(-1.0), 1e-9)
    }

    @Test
    fun `zero speed segment contributes no time instead of dividing by zero`() {
        val s = stage(0.0 to 0.0, 1.0 to 36.0)
        assertEquals(100.0, s.targetElapsedSecondsAt(2.0), 1e-6)
    }

    // ── totals + completion ────────────────────────────────────────

    @Test
    fun `target total uses stage length`() {
        val s = stage(0.0 to 36.0, lengthKm = 3.0)
        assertEquals(300.0, s.targetTotalSeconds()!!, 1e-6)
        assertNull(stage(0.0 to 36.0).targetTotalSeconds())
    }

    @Test
    fun `completion only with known length`() {
        val s = stage(0.0 to 36.0, lengthKm = 3.0)
        assertFalse(s.isComplete(2.99))
        assertTrue(s.isComplete(3.0))
        assertFalse(stage(0.0 to 36.0).isComplete(999.0))
    }

    // ── JSON round-trip ────────────────────────────────────────────

    @Test
    fun `json round trip preserves everything`() {
        val s = stage(0.0 to 36.0, 2.35 to 47.5, lengthKm = 6.1)
        val back = RegularityStage.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s, back)
    }

    @Test
    fun `json without length stays open ended`() {
        val s = stage(0.0 to 50.0)
        val back = RegularityStage.fromJson(JSONObject(s.toJson().toString()))
        assertNull(back.lengthKm)
        assertEquals(s, back)
    }

    @Test
    fun `fromJson sorts unsorted breakpoints`() {
        val json = JSONObject(
            """{"id":"x","name":"n","changes":[
                {"atKm":2.0,"speedKmh":48.0},
                {"atKm":0.0,"speedKmh":36.0}
            ]}"""
        )
        val s = RegularityStage.fromJson(json)
        assertEquals(0.0, s.changes.first().atKm, 1e-9)
        assertEquals(200.0, s.targetElapsedSecondsAt(2.0), 1e-6)
    }
}
