package be.appmire.gpsinfo.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geographic math for the bearing-to-waypoint and track-back
 * features. All inputs in degrees; outputs in degrees (bearings) or
 * metres (distances) as documented per function. No state, no Android
 * dependencies — fully unit-testable.
 */
object NavigationMath {

    private const val EARTH_R_METRES = 6_371_000.0

    /**
     * Initial great-circle bearing from `(fromLat, fromLon)` toward
     * `(toLat, toLon)`, in degrees from true north, range [0, 360).
     *
     * "Initial" matters at large distances — the great-circle path
     * curves, so a heading set once and held doesn't end up where the
     * destination originally pointed. For typical hike / run distances
     * (< 30 km) the discrepancy between initial and rhumb-line bearing
     * is fractions of a degree and we don't bother correcting.
     *
     * Returns 0.0 when the two points coincide (no meaningful bearing
     * exists; caller should treat as "you have arrived").
     */
    fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        if (fromLat == toLat && fromLon == toLon) return 0.0
        val φ1 = Math.toRadians(fromLat)
        val φ2 = Math.toRadians(toLat)
        val Δλ = Math.toRadians(toLon - fromLon)
        val y = sin(Δλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        val θ = Math.toDegrees(atan2(y, x))
        return (θ + 360.0) % 360.0
    }

    /**
     * Great-circle distance in metres between two points using the
     * haversine formula. Accurate to << 1 m at the distances we care
     * about. Symmetric — order of arguments doesn't change the result.
     */
    fun distanceMetres(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val φ1 = Math.toRadians(fromLat)
        val φ2 = Math.toRadians(toLat)
        val Δφ = Math.toRadians(toLat - fromLat)
        val Δλ = Math.toRadians(toLon - fromLon)
        val a = sin(Δφ / 2.0) * sin(Δφ / 2.0) +
            cos(φ1) * cos(φ2) * sin(Δλ / 2.0) * sin(Δλ / 2.0)
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return EARTH_R_METRES * c
    }

    /**
     * Seconds to reach a point [distanceMetres] away at the given
     * [speedKmh]. Returns `null` when speed is too low to produce a
     * sensible ETA — stationary users get "—" instead of "∞".
     */
    fun etaSeconds(distanceMetres: Double, speedKmh: Float): Long? {
        if (speedKmh < MIN_ETA_SPEED_KMH || distanceMetres <= 0.0) return null
        // Double rather than Float — Float precision turns 5 km/h ÷ 3.6
        // into 1.3888887... and the resulting ETA loses the last second
        // to truncation. We don't need single-second accuracy, but
        // off-by-one in unit tests is a smell worth avoiding.
        val mps = speedKmh.toDouble() / 3.6
        return (distanceMetres / mps).toLong()
    }

    /**
     * Minimum great-circle distance from (lat, lon) to any point in
     * [routePoints]. Used by the off-route alarm — a runner more than
     * the configured threshold from every route point is genuinely off
     * their planned line.
     *
     * Point-distance (not perpendicular-to-segment) is good enough at
     * recorded-GPX sample density (1-2 s ≈ 5-15 m apart), and a fair
     * amount cheaper than walking every segment.
     */
    fun minDistanceToRouteMetres(
        routePoints: List<be.appmire.gpsinfo.data.model.TrailPoint>,
        currentLat: Double,
        currentLon: Double,
    ): Double? {
        if (routePoints.isEmpty()) return null
        var best = Double.POSITIVE_INFINITY
        for (p in routePoints) {
            val d = distanceMetres(currentLat, currentLon, p.latDeg, p.lonDeg)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Relative bearing (target bearing minus current heading), wrapped
     * to [0, 360). Pass through to a `rotationZ` modifier to make a
     * navigation arrow point at the target from the user's POV.
     */
    fun relativeBearingDegrees(targetBearingDeg: Double, currentHeadingDeg: Float): Double {
        return ((targetBearingDeg - currentHeadingDeg + 360.0) % 360.0)
    }

    /** Below this speed an ETA isn't a meaningful number — the user is
     *  effectively stationary and any "ETA" is wild speculation. */
    private const val MIN_ETA_SPEED_KMH = 0.5f
}
