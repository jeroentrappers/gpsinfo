package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable
import be.appmire.gpsinfo.data.UnitSystem

/**
 * Active navigation goal — drives the dashboard navigation card.
 *
 * Two shapes:
 *
 * - [Single] — fixed waypoint. Bearing/distance computed against the
 *   single (lat, lon) target. Used by the "pick on map" / "paste
 *   coordinates" flows.
 *
 * - [Route] — track-back. A list of [TrailPoint]s from a recorded trail,
 *   with [currentIdx] pointing at the next point to head toward. The
 *   ViewModel advances [currentIdx] as the user approaches each point,
 *   stepping backward through the trail until the user reaches the
 *   start (currentIdx == 0 is the final target; advancing past it
 *   completes the navigation).
 */
sealed interface NavigationTarget {

    /** Lat/lon of the *current* target point, in degrees. */
    val targetLatDeg: Double
    val targetLonDeg: Double

    /** Display name shown on the navigation card. */
    val displayName: String

    /**
     * Overall pace target (sec/unit). On a [Single] target this is
     * authored once and never changes; on a [Route] it's the
     * trail-wide fallback used when no per-segment target is set on
     * the current point. Callers should usually read
     * [effectiveTargetPaceSecondsPerUnit] instead, which resolves
     * per-segment first.
     */
    val targetPaceSecondsPerUnit: Float?

    /**
     * Effective target pace for the current segment, in the user's
     * displayed unit. On [Single] returns [targetPaceSecondsPerUnit];
     * on [Route] returns the per-point target (converted from km, the
     * canonical persistence unit) when present, otherwise the
     * route-level fallback.
     */
    fun effectiveTargetPaceSecondsPerUnit(unitSystem: UnitSystem): Float?

    @Immutable
    data class Single(
        val latDeg: Double,
        val lonDeg: Double,
        val name: String,
        override val targetPaceSecondsPerUnit: Float? = null,
    ) : NavigationTarget {
        override val targetLatDeg: Double get() = latDeg
        override val targetLonDeg: Double get() = lonDeg
        override val displayName: String get() = name
        override fun effectiveTargetPaceSecondsPerUnit(unitSystem: UnitSystem): Float? =
            targetPaceSecondsPerUnit
    }

    /**
     * Track-back through a recorded trail. [points] is the trail in its
     * original (forward) order; [currentIdx] starts at `points.size - 1`
     * (where the user is now) and decrements as the user reaches each
     * subsequent point, walking backward to `points[0]`. We track the
     * forward-order index so callers can render a "remaining trail"
     * polyline straightforwardly.
     */
    @Immutable
    data class Route(
        val points: List<TrailPoint>,
        val currentIdx: Int,
        val trailName: String,
        override val targetPaceSecondsPerUnit: Float? = null,
    ) : NavigationTarget {
        init {
            require(points.isNotEmpty()) { "Route must contain at least one point" }
            require(currentIdx in points.indices) {
                "currentIdx=$currentIdx out of range for ${points.size} points"
            }
        }
        override val targetLatDeg: Double get() = points[currentIdx].latDeg
        override val targetLonDeg: Double get() = points[currentIdx].lonDeg
        override val displayName: String get() = trailName

        override fun effectiveTargetPaceSecondsPerUnit(unitSystem: UnitSystem): Float? {
            val perPointKm = points.getOrNull(currentIdx)?.targetPaceSecondsPerKm
            if (perPointKm != null) {
                return when (unitSystem) {
                    UnitSystem.Metric -> perPointKm
                    UnitSystem.Imperial -> perPointKm * 1.609344f
                    UnitSystem.Nautical -> perPointKm * 1.852f
                }
            }
            return targetPaceSecondsPerUnit
        }
    }
}

/**
 * Returns the same navigation target with an updated [targetPaceSecondsPerUnit].
 * Allows the UI to set/clear a goal without caring whether the underlying
 * target is a Single or a Route.
 */
fun NavigationTarget.withTargetPace(pace: Float?): NavigationTarget = when (this) {
    is NavigationTarget.Single -> copy(targetPaceSecondsPerUnit = pace)
    is NavigationTarget.Route -> copy(targetPaceSecondsPerUnit = pace)
}
