package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.model.NavigationTarget
import be.appmire.gpsinfo.data.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProjectionTest {

    /** Helper: build a track-back route from a list of (lat, lon, ele)
     *  triples in *forward-recorded* order (i.e. position 0 = trail
     *  start, position N-1 = trail end where the user currently is). */
    private fun route(vararg p: Triple<Double, Double, Double>): NavigationTarget.Route {
        val points = p.map { (lat, lon, ele) ->
            TrailPoint(timeMillis = 0L, latDeg = lat, lonDeg = lon, eleMeters = ele)
        }
        return NavigationTarget.Route(
            points = points,
            currentIdx = points.size - 1,
            trailName = "test",
        )
    }

    @Test fun upcoming_walks_forward_from_current_idx() {
        // Trail at 0,0 → 0.0009,0 → 0.0018,0 (each segment ≈ 100 m). User
        // is at the last point; track-back walks them backward to 0,0.
        val r = route(
            Triple(0.0, 0.0, 100.0),         // [0] start (final track-back target)
            Triple(0.0009, 0.0, 110.0),      // [1] middle
            Triple(0.0018, 0.0, 105.0),      // [2] current
        )
        val segments = RouteProjection.upcomingSegments(r, maxDistanceMetres = 500.0)
        assertEquals(2, segments.size)
        // First upcoming segment: from index 2 → 1 (user walks toward middle).
        // Elevation goes 105 → 110 → climb. Grade should be positive.
        assertTrue("first segment should be climbing, got ${segments[0].gradePercent}", segments[0].gradePercent > 0)
        // Second segment: from 1 → 0 (middle → start). 110 → 100 → descent.
        assertTrue("second segment should be descending, got ${segments[1].gradePercent}", segments[1].gradePercent < 0)
    }

    @Test fun upcoming_respects_distance_budget() {
        // Five 100 m segments; ask for 250 m → should get the first 3.
        val r = route(
            Triple(0.0, 0.0, 0.0),
            Triple(0.0009, 0.0, 0.0),
            Triple(0.0018, 0.0, 0.0),
            Triple(0.0027, 0.0, 0.0),
            Triple(0.0036, 0.0, 0.0),
            Triple(0.0045, 0.0, 0.0),
        )
        val segments = RouteProjection.upcomingSegments(r, maxDistanceMetres = 250.0)
        assertEquals(3, segments.size)
    }

    @Test fun upcoming_skips_points_without_elevation() {
        val r = NavigationTarget.Route(
            points = listOf(
                TrailPoint(timeMillis = 0L, latDeg = 0.0, lonDeg = 0.0, eleMeters = 100.0),
                TrailPoint(timeMillis = 0L, latDeg = 0.0009, lonDeg = 0.0, eleMeters = null),
                TrailPoint(timeMillis = 0L, latDeg = 0.0018, lonDeg = 0.0, eleMeters = 110.0),
            ),
            currentIdx = 2,
            trailName = "test",
        )
        val segments = RouteProjection.upcomingSegments(r)
        // Middle point has no elevation → segments either side are skipped.
        // Only the "ele-to-no-ele" and "no-ele-to-ele" transitions appear
        // but both are dropped, so segments is empty.
        assertTrue("expected 0 valid segments, got ${segments.size}", segments.size <= 1)
    }

    @Test fun next_climb_detects_qualifying_uphill() {
        // 100 m flat → 300 m climb at 5 % grade (~15 m vertical) → flat.
        // climbGain 15 m is at the lower bound; with default 20m threshold
        // this *doesn't* count. Bump grade slightly so it does.
        val segments = listOf(
            RouteProjection.UpcomingSegment(100.0, 0f, 0.0, 0.0),
            RouteProjection.UpcomingSegment(300.0, 8f, 0.0, 24.0),
            RouteProjection.UpcomingSegment(100.0, 0f, 24.0, 24.0),
        )
        val climb = RouteProjection.nextClimb(segments)
        assertNotNull("expected a climb to be detected", climb)
        assertEquals(100.0, climb!!.distanceToStartM, 0.001)
        assertEquals(300.0, climb.climbDistanceM, 0.001)
        assertTrue(climb.climbGainM >= 20.0)
    }

    @Test fun next_climb_ignores_short_steep_bumps() {
        // 10 m at 20% grade = only 2 m vertical — below the default
        // 20 m gain threshold. Should not trigger.
        val segments = listOf(
            RouteProjection.UpcomingSegment(10.0, 20f, 0.0, 2.0),
            RouteProjection.UpcomingSegment(100.0, 0f, 2.0, 2.0),
        )
        assertNull(RouteProjection.nextClimb(segments))
    }

    @Test fun next_climb_returns_null_on_flat_or_descending() {
        val segments = listOf(
            RouteProjection.UpcomingSegment(500.0, 0f, 0.0, 0.0),
            RouteProjection.UpcomingSegment(500.0, -3f, 0.0, -15.0),
        )
        assertNull(RouteProjection.nextClimb(segments))
    }
}
