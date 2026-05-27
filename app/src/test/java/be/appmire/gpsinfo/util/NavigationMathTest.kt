package be.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationMathTest {

    // Bearing tests — reference values cross-checked against
    // movable-type.co.uk/scripts/latlong.html.

    @Test fun bearing_due_north() {
        val b = NavigationMath.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, b, 0.01)
    }

    @Test fun bearing_due_east() {
        val b = NavigationMath.bearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, b, 0.01)
    }

    @Test fun bearing_due_south() {
        val b = NavigationMath.bearingDegrees(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, b, 0.01)
    }

    @Test fun bearing_due_west() {
        val b = NavigationMath.bearingDegrees(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, b, 0.01)
    }

    @Test fun bearing_diagonal_northeast() {
        // From (0,0) to (1,1) — initial bearing isn't exactly 45° because
        // longitude metres shrink with latitude on a sphere. Confirmed
        // ~44.996° from the reference site.
        val b = NavigationMath.bearingDegrees(0.0, 0.0, 1.0, 1.0)
        assertEquals(44.996, b, 0.01)
    }

    @Test fun bearing_self_is_zero() {
        val b = NavigationMath.bearingDegrees(51.13, 4.37, 51.13, 4.37)
        assertEquals(0.0, b, 1e-9)
    }

    @Test fun bearing_is_wrapped_to_360() {
        // A small western lon delta produces a bearing in the 4th quadrant.
        val b = NavigationMath.bearingDegrees(0.0, 0.0, 0.0, -0.001)
        assertEquals(270.0, b, 0.01)
        // Output must never be negative.
        assert(b >= 0.0 && b < 360.0) { "bearing $b not in [0, 360)" }
    }

    // Distance tests — haversine values cross-checked against the same
    // reference site.

    @Test fun distance_one_degree_lat() {
        // One degree of latitude at the equator ≈ 111,195 m.
        val d = NavigationMath.distanceMetres(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 50.0)
    }

    @Test fun distance_symmetric() {
        val a = NavigationMath.distanceMetres(51.13, 4.37, 48.86, 2.29)
        val b = NavigationMath.distanceMetres(48.86, 2.29, 51.13, 4.37)
        assertEquals(a, b, 0.001)
    }

    @Test fun distance_self_is_zero() {
        val d = NavigationMath.distanceMetres(51.13, 4.37, 51.13, 4.37)
        assertEquals(0.0, d, 1e-9)
    }

    @Test fun distance_antwerp_to_paris() {
        // ~301 km, matches the published great-circle value.
        val d = NavigationMath.distanceMetres(51.2194, 4.4025, 48.8566, 2.3522)
        assertEquals(301_000.0, d, 2_000.0)
    }

    // ETA tests.

    @Test fun eta_at_walking_pace() {
        // 1000 m at 5 km/h = 720 s = 12 min.
        val s = NavigationMath.etaSeconds(1000.0, 5f)
        assertEquals(720L, s)
    }

    @Test fun eta_null_when_stationary() {
        assertNull(NavigationMath.etaSeconds(1000.0, 0f))
        assertNull(NavigationMath.etaSeconds(1000.0, 0.4f))
    }

    @Test fun eta_null_when_distance_zero() {
        assertNull(NavigationMath.etaSeconds(0.0, 10f))
    }

    // Relative bearing.

    @Test fun relative_bearing_target_ahead_when_heading_matches() {
        val rel = NavigationMath.relativeBearingDegrees(targetBearingDeg = 90.0, currentHeadingDeg = 90f)
        assertEquals(0.0, rel, 1e-9)
    }

    @Test fun relative_bearing_target_behind_when_heading_opposite() {
        val rel = NavigationMath.relativeBearingDegrees(targetBearingDeg = 0.0, currentHeadingDeg = 180f)
        assertEquals(180.0, rel, 1e-9)
    }

    @Test fun relative_bearing_wraps_on_negative() {
        // Target at 350°, user heading 10° → target is 20° to the left
        // → wraps to 340°.
        val rel = NavigationMath.relativeBearingDegrees(targetBearingDeg = 350.0, currentHeadingDeg = 10f)
        assertEquals(340.0, rel, 1e-9)
    }
}
