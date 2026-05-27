package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.model.NavigationTarget

/**
 * Forward projection of a track-back route. Given the active
 * [NavigationTarget.Route], computes the upcoming N metres of trail as
 * a list of grade-typed segments — feeds the Sports Dashboard's
 * intensity-profile chart and the "ETA to next climb" panel.
 *
 * For track-back, "forward" means decrementing `currentIdx` (the user
 * walks the trail in reverse of how it was recorded). For a future
 * planned-route-forward mode, this function would need a direction
 * flag — out of scope for now.
 */
object RouteProjection {

    /** A single segment of the upcoming trail. [gradePercent] is positive
     *  when climbing in the user's direction of travel. */
    data class UpcomingSegment(
        val distanceMetres: Double,
        val gradePercent: Float,
        val elevationStartM: Double,
        val elevationEndM: Double,
    )

    /**
     * Walk the route forward from `currentIdx`, accumulating up to
     * [maxDistanceMetres] of segments. Segments with missing elevation
     * data are skipped (osmdroid imports without elevation are common).
     */
    fun upcomingSegments(
        route: NavigationTarget.Route,
        maxDistanceMetres: Double = DEFAULT_PROFILE_DISTANCE_M,
    ): List<UpcomingSegment> {
        val pts = route.points
        var i = route.currentIdx
        if (i <= 0) return emptyList()
        val out = ArrayList<UpcomingSegment>()
        var remaining = maxDistanceMetres
        while (i > 0 && remaining > 0.0) {
            val a = pts[i]
            val b = pts[i - 1]
            val eleA = a.eleMeters
            val eleB = b.eleMeters
            val d = NavigationMath.distanceMetres(a.latDeg, a.lonDeg, b.latDeg, b.lonDeg)
            if (eleA != null && eleB != null && d > 0.0) {
                val grade = ((eleB - eleA) / d * 100.0).toFloat()
                out.add(UpcomingSegment(d, grade, eleA, eleB))
                remaining -= d
            }
            i--
        }
        return out
    }

    /**
     * Find the next "major climb" in the upcoming segments.
     *
     * Climb definition: a contiguous run of segments with grade above
     * [climbGradeThresholdPercent] that accumulates at least
     * [climbGainThresholdMetres] of vertical gain. Returns the
     * distance from the user's current position to the *start* of the
     * climb in metres, plus the cumulative gain over the climb itself.
     *
     * Returns null when no upcoming segment qualifies — flat or
     * descending route ahead.
     */
    fun nextClimb(
        segments: List<UpcomingSegment>,
        climbGradeThresholdPercent: Float = 3f,
        climbGainThresholdMetres: Double = 20.0,
    ): Climb? {
        var leadDistance = 0.0
        var i = 0
        while (i < segments.size) {
            val s = segments[i]
            if (s.gradePercent < climbGradeThresholdPercent) {
                leadDistance += s.distanceMetres
                i++
                continue
            }
            // Found the start of a candidate climb. Walk forward while
            // grade stays above threshold; accumulate distance + gain.
            var climbDist = 0.0
            var climbGain = 0.0
            val climbStartIdx = i
            while (i < segments.size && segments[i].gradePercent >= climbGradeThresholdPercent) {
                climbDist += segments[i].distanceMetres
                climbGain += segments[i].elevationEndM - segments[i].elevationStartM
                i++
            }
            if (climbGain >= climbGainThresholdMetres) {
                return Climb(
                    distanceToStartM = leadDistance,
                    climbDistanceM = climbDist,
                    climbGainM = climbGain,
                )
            }
            // Wasn't enough vertical — keep scanning.
            leadDistance += segments.subList(climbStartIdx, i).sumOf { it.distanceMetres }
        }
        return null
    }

    data class Climb(
        val distanceToStartM: Double,
        val climbDistanceM: Double,
        val climbGainM: Double,
    )

    private const val DEFAULT_PROFILE_DISTANCE_M = 1_500.0
}
