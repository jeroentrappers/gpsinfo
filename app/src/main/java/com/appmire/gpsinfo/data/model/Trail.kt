package com.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable
import kotlin.math.cos

/**
 * A recorded GPS trail — an ordered list of [TrailPoint] plus identifying
 * metadata. The [id] is the storage filename (without the `.gpx` suffix)
 * and is what the UI uses to look up a trail to display.
 *
 * Heavier derived quantities (distance, duration) are computed on demand
 * rather than stored — keeps the model honest when points are appended.
 */
@Immutable
data class Trail(
    val id: String,
    val name: String,
    val points: List<TrailPoint>,
) {
    val startTimeMillis: Long? get() = points.firstOrNull()?.timeMillis
    val endTimeMillis: Long? get() = points.lastOrNull()?.timeMillis

    val durationMillis: Long
        get() {
            val s = startTimeMillis ?: return 0L
            val e = endTimeMillis ?: return 0L
            return (e - s).coerceAtLeast(0L)
        }

    /** Total path length in metres, using a flat-earth approximation
     *  segment-by-segment. Good to << 1 % under 100 km per segment —
     *  fine since consecutive GPX trackpoints are seconds apart. */
    val distanceMeters: Double
        get() {
            if (points.size < 2) return 0.0
            var total = 0.0
            for (i in 1 until points.size) {
                total += segmentMetres(points[i - 1], points[i])
            }
            return total
        }

    val bounds: LatLonBounds?
        get() {
            if (points.isEmpty()) return null
            var minLat = points[0].latDeg
            var maxLat = minLat
            var minLon = points[0].lonDeg
            var maxLon = minLon
            for (i in 1 until points.size) {
                val p = points[i]
                if (p.latDeg < minLat) minLat = p.latDeg
                if (p.latDeg > maxLat) maxLat = p.latDeg
                if (p.lonDeg < minLon) minLon = p.lonDeg
                if (p.lonDeg > maxLon) maxLon = p.lonDeg
            }
            return LatLonBounds(minLat, maxLat, minLon, maxLon)
        }

    /** Average speed in km/h over the whole recording. Uses
     *  [distanceMeters] / [durationMillis] so it stays consistent with
     *  the displayed distance even when fixes were dropped. */
    val avgSpeedKmh: Float
        get() {
            val ms = durationMillis
            if (ms <= 0L) return 0f
            return (distanceMeters / ms * 3600.0).toFloat()  // m/ms → km/h
        }

    /** Peak instantaneous speed reported by the GPS chip, in km/h.
     *  Falls back to the segment-derived speed when no point reported one. */
    val maxSpeedKmh: Float
        get() {
            val reported = points.mapNotNull { it.speedMps }.maxOrNull()
            if (reported != null) return reported * 3.6f
            // No speed in any trackpoint — fall back to segment speeds.
            if (points.size < 2) return 0f
            var maxKmh = 0f
            for (i in 1 until points.size) {
                val ms = (points[i].timeMillis - points[i - 1].timeMillis).coerceAtLeast(1L)
                val km = segmentMetres(points[i - 1], points[i]) / 1000.0
                val kmh = (km / ms * 3_600_000.0).toFloat()
                if (kmh > maxKmh) maxKmh = kmh
            }
            return maxKmh
        }

    /** Total positive elevation change, in metres. Ignores noise below
     *  [ELE_NOISE_M] so a flat walk doesn't accumulate phantom climb. */
    val ascentMeters: Double get() = elevationDelta(positive = true)

    /** Total negative elevation change, expressed as a positive number. */
    val descentMeters: Double get() = elevationDelta(positive = false)

    private fun elevationDelta(positive: Boolean): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        var prev: Double = points.firstNotNullOfOrNull { it.eleMeters } ?: return 0.0
        for (i in 1 until points.size) {
            val cur = points[i].eleMeters ?: continue
            val d = cur - prev
            // Per-segment noise gate — without this, a stationary phone
            // accumulates dozens of metres of "climb" from GPS jitter.
            if (kotlin.math.abs(d) >= ELE_NOISE_M) {
                if ((d > 0) == positive) total += kotlin.math.abs(d)
                prev = cur
            }
        }
        return total
    }
}

/** Per-step elevation change below this is treated as GPS noise.
 *  Roughly matches the typical 1σ of consumer GPS vertical accuracy. */
private const val ELE_NOISE_M = 3.0

@Immutable
data class LatLonBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLon: Double get() = (minLon + maxLon) / 2.0
    val spanLat: Double get() = (maxLat - minLat).coerceAtLeast(1e-6)
    val spanLon: Double get() = (maxLon - minLon).coerceAtLeast(1e-6)
}

private const val EARTH_R = 6_371_000.0

internal fun segmentMetres(a: TrailPoint, b: TrailPoint): Double {
    val midLatRad = Math.toRadians((a.latDeg + b.latDeg) / 2.0)
    val dLat = Math.toRadians(b.latDeg - a.latDeg)
    val dLon = Math.toRadians(b.lonDeg - a.lonDeg) * cos(midLatRad)
    return EARTH_R * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
}
