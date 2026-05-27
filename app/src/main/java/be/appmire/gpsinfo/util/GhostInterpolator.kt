package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.model.Trail

/**
 * "Where was past-me at this elapsed time?" — linear interpolation of
 * a recorded [Trail]'s cumulative distance by elapsed-time since its
 * own start. Drives the ghost-pacer card during a live recording: you
 * compare your live distance to the ghost's distance at the same
 * elapsed time and surface the delta.
 *
 * Pure math — no I/O, no coroutines. Cheap enough to call per location
 * tick.
 */
object GhostInterpolator {

    /**
     * Cumulative distance the ghost trail has covered after
     * [elapsedMillis] since its own start. Returns the trail's total
     * distance when [elapsedMillis] exceeds its duration — the ghost
     * has finished and is waiting at the line.
     *
     * Returns null when the trail has too few timestamped points to
     * yield a meaningful interpolation.
     */
    fun distanceAtElapsedMs(trail: Trail, elapsedMillis: Long): Double? {
        val points = trail.points
        if (points.size < 2) return null
        val start = trail.startTimeMillis ?: return null
        val targetTime = start + elapsedMillis.coerceAtLeast(0L)
        if (targetTime >= (trail.endTimeMillis ?: return null)) {
            return trail.distanceMeters
        }
        // Walk the points, accumulating segment lengths until we
        // straddle the target time. Linear-interpolate within the
        // straddling segment so the result is smooth as the live
        // clock ticks.
        var cumulative = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            val segMs = (cur.timeMillis - prev.timeMillis).coerceAtLeast(0L)
            val segMetres = segmentMetres(prev.latDeg, prev.lonDeg, cur.latDeg, cur.lonDeg)
            if (targetTime <= cur.timeMillis) {
                val frac = if (segMs == 0L) 0.0
                else ((targetTime - prev.timeMillis).toDouble() / segMs).coerceIn(0.0, 1.0)
                return cumulative + segMetres * frac
            }
            cumulative += segMetres
        }
        return cumulative
    }

    private fun segmentMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLatRad = Math.toRadians((lat1 + lat2) / 2.0)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * kotlin.math.cos(midLatRad)
        return 6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
