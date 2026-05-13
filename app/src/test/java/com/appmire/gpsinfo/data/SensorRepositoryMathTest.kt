package com.appmire.gpsinfo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRepositoryMathTest {

    /** First sample seeds both prev and continuous to the wrapped value. */
    @Test fun integrateContinuousHeading_first_sample_seeds() {
        val (continuous, prev) = integrateContinuousHeading(
            prevWrapped = Double.NaN,
            newWrapped = 42.0,
            prevContinuous = 0.0,
        )
        assertEquals(42.0, continuous, 1e-9)
        assertEquals(42.0, prev, 1e-9)
    }

    /** Small forward step: continuous += delta. */
    @Test fun integrateContinuousHeading_small_step_forward() {
        val (continuous, prev) = integrateContinuousHeading(
            prevWrapped = 359.0,
            newWrapped = 1.0,
            prevContinuous = 359.0,
        )
        // Shortest delta from 359 to 1 is +2.
        assertEquals(361.0, continuous, 1e-9)
        assertEquals(1.0, prev, 1e-9)
    }

    /** Crossing north backward: shortest delta is negative. */
    @Test fun integrateContinuousHeading_step_backward_through_north() {
        val (continuous, _) = integrateContinuousHeading(
            prevWrapped = 1.0,
            newWrapped = 359.0,
            prevContinuous = 1.0,
        )
        // Shortest delta from 1 to 359 is -2.
        assertEquals(-1.0, continuous, 1e-9)
    }

    /** A spin doesn't reverse mid-rotation if the input wobbles back. */
    @Test fun integrateContinuousHeading_wobble_at_boundary_still_monotone() {
        // Sequence: 358 → 1 → 0 → 2 (slight oscillation at the wrap)
        var prev = Double.NaN
        var cont = 0.0
        for (wrapped in listOf(358.0, 1.0, 0.0, 2.0)) {
            val r = integrateContinuousHeading(prev, wrapped, cont)
            cont = r.first; prev = r.second
        }
        // Net angular travel: +3° + (-1°) + (+2°) = +4° from 358 → 362
        assertEquals(362.0, cont, 1e-9)
    }

    /** Distance approximation is exact along an east–west chord at the
     *  equator: 1° of longitude ≈ 111 320 m. */
    @Test fun metresBetween_one_degree_lon_at_equator() {
        val d = metresBetween(0.0, 0.0, 0.0, 1.0)
        assertTrue("expected ~111 km, got $d", d in 111_000.0..112_000.0)
    }

    /** Same lat/lon → zero. */
    @Test fun metresBetween_same_point_is_zero() {
        assertEquals(0.0, metresBetween(51.13, 4.38, 51.13, 4.38), 1e-9)
    }

    /** NaN previous lat triggers the "infinity" signal — repository uses
     *  this to force a recompute when it has no previous reading. */
    @Test fun metresBetween_nan_prev_is_infinity() {
        assertEquals(Double.POSITIVE_INFINITY, metresBetween(Double.NaN, 0.0, 0.0, 0.0), 0.0)
    }
}
