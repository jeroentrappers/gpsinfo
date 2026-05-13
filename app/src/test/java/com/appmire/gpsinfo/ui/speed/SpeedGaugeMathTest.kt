package com.appmire.gpsinfo.ui.speed

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedGaugeMathTest {

    /** 0 km/h sits at the start of the sweep. */
    @Test fun zero_kmh_at_start() =
        assertEquals(0f, speedToFraction(0f, 180f), 1e-3f)

    /** 100 km/h sits exactly at the 60% break. */
    @Test fun hundred_kmh_at_60_percent() =
        assertEquals(0.6f, speedToFraction(100f, 180f), 1e-3f)

    /** Max value sits at the very end. */
    @Test fun max_at_end() =
        assertEquals(1f, speedToFraction(180f, 180f), 1e-3f)

    /** Halfway from 0 to 100 should sit at 30% of the sweep. */
    @Test fun fifty_kmh_at_30_percent() =
        assertEquals(0.30f, speedToFraction(50f, 180f), 1e-3f)

    /** Above max → clamped to 1. */
    @Test fun above_max_clamped() =
        assertEquals(1f, speedToFraction(1000f, 180f), 1e-3f)

    /** Below zero → clamped to 0. */
    @Test fun below_zero_clamped() =
        assertEquals(0f, speedToFraction(-5f, 180f), 1e-3f)

    /** When max < 100 km/h, falls back to linear so the dial still makes sense. */
    @Test fun small_max_falls_back_to_linear() =
        assertEquals(0.5f, speedToFraction(40f, 80f), 1e-3f)

    @Test fun tickSteps_under_200() = assertEquals(10f to 20f, chooseTickSteps(180f))
    @Test fun tickSteps_at_200_boundary() = assertEquals(10f to 20f, chooseTickSteps(200f))
    @Test fun tickSteps_240() = assertEquals(20f to 40f, chooseTickSteps(240f))
    @Test fun tickSteps_500() = assertEquals(25f to 50f, chooseTickSteps(500f))
    @Test fun tickSteps_above_700() = assertEquals(50f to 100f, chooseTickSteps(800f))
}
