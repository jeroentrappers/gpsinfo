package be.appmire.gpsinfo.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptMaxSpeedTest {

    @Test fun unchanged_when_below_max() {
        assertEquals(180f, adaptMaxSpeed(measuredKmh = 100f, currentMaxKmh = 180f), 1e-3f)
    }

    @Test fun unchanged_at_exact_max() {
        // `>` not `>=` — touching the max shouldn't ratchet.
        assertEquals(180f, adaptMaxSpeed(measuredKmh = 180f, currentMaxKmh = 180f), 1e-3f)
    }

    @Test fun bumps_to_240_when_exceeding_180() {
        // 180 × 1.3 = 234 → ceil to 240.
        assertEquals(240f, adaptMaxSpeed(measuredKmh = 180.1f, currentMaxKmh = 180f), 1e-3f)
    }

    @Test fun bumps_for_exact_multiple_of_ten() {
        // 200 × 1.3 = 260 → already a multiple of 10.
        assertEquals(260f, adaptMaxSpeed(measuredKmh = 200f, currentMaxKmh = 180f), 1e-3f)
    }

    @Test fun never_shrinks() {
        // Slow re-reading should not lower the ceiling.
        assertEquals(240f, adaptMaxSpeed(measuredKmh = 50f, currentMaxKmh = 240f), 1e-3f)
    }

    @Test fun bumps_again_when_new_value_exceeds_previously_bumped_max() {
        val firstBump = adaptMaxSpeed(measuredKmh = 180.1f, currentMaxKmh = 180f) // 240
        val secondBump = adaptMaxSpeed(measuredKmh = 245f, currentMaxKmh = firstBump)
        // 245 × 1.3 = 318.5 → ceil to 320.
        assertEquals(320f, secondBump, 1e-3f)
    }
}
