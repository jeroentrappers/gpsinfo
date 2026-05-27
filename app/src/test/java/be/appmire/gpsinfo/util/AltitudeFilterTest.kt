package be.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AltitudeFilterTest {

    @Test fun update_first_sample_passes_through_unfiltered() {
        val f = AltitudeFilter()
        assertEquals(42.0, f.update(42.0)!!, 1e-9)
    }

    @Test fun update_returns_null_for_null_when_no_history() {
        val f = AltitudeFilter()
        assertNull(f.update(null))
    }

    @Test fun update_keeps_previous_smoothed_on_null_sample() {
        val f = AltitudeFilter()
        f.update(100.0)
        val out = f.update(null)
        assertNotNull(out)
        assertEquals(100.0, out!!, 1e-9)
    }

    @Test fun update_small_step_blends_toward_new_value() {
        val f = AltitudeFilter(alpha = 0.5f, resetDeltaMeters = 100.0)
        f.update(100.0)              // seeds at 100
        val next = f.update(110.0)!! // blend
        // y = 0.5*110 + 0.5*100 = 105
        assertEquals(105.0, next, 1e-9)
    }

    @Test fun update_resets_on_large_jump() {
        val f = AltitudeFilter(alpha = 0.1f, resetDeltaMeters = 50.0)
        f.update(100.0)
        // Δ = 200 m → re-seed rather than slowly chase.
        val next = f.update(300.0)!!
        assertEquals(300.0, next, 1e-9)
    }

    @Test fun reset_clears_history() {
        val f = AltitudeFilter()
        f.update(100.0)
        f.reset()
        // Next sample is again "first" → passes through.
        assertEquals(50.0, f.update(50.0)!!, 1e-9)
    }
}
