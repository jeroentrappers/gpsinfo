package be.appmire.gpsinfo.util

/**
 * Rolling distance-over-time speed estimator.
 *
 * Each [push] feeds a new (lat, lon, wall-clock ms) sample. Internally
 * we keep a FIFO of samples newer than [windowMs] and walk them to
 * sum the consecutive segment distances. [averageKmh] returns the
 * window-averaged speed in km/h, or null when the window doesn't
 * contain enough data yet (single sample, zero elapsed, zero
 * distance).
 *
 * Why this exists, given that `Location.speed` already reports an
 * instantaneous speed: the chip's value is Doppler-derived per fix,
 * which is fast but noisy on multipath / weak-signal scenarios. The
 * position-derived average is independent — same physical motion,
 * different chain. Cross-checking the two gives the user a
 * confidence read: if they agree, fix quality is sound; if they
 * diverge, something's drifting.
 *
 * Pure math, no allocation per push beyond the deque entry. Safe to
 * call at 1-5 Hz from a Compose recompose loop. Caller owns the
 * lifetime (typically a `remember { RollingSpeed() }`).
 */
class RollingSpeed(private val windowMs: Long = 10_000L) {

    private data class Sample(val timeMs: Long, val latDeg: Double, val lonDeg: Double)

    private val samples: ArrayDeque<Sample> = ArrayDeque()

    fun push(latDeg: Double, lonDeg: Double, timeMs: Long = System.currentTimeMillis()) {
        // Dedup duplicate timestamps — Location updates can fire
        // twice with the same `time` when the activity briefly
        // pauses and resumes. Without this we'd record two samples
        // at identical t and double-count the next segment.
        if (samples.isNotEmpty() && samples.last().timeMs == timeMs) return
        samples.addLast(Sample(timeMs, latDeg, lonDeg))
        while (samples.isNotEmpty() && timeMs - samples.first().timeMs > windowMs) {
            samples.removeFirst()
        }
    }

    /** Average speed over the rolling window in km/h. Null until at
     *  least 1 second / 5 metres has accumulated. */
    fun averageKmh(): Float? {
        if (samples.size < 2) return null
        val first = samples.first()
        val last = samples.last()
        val elapsedMs = (last.timeMs - first.timeMs).coerceAtLeast(0L)
        if (elapsedMs < 1000L) return null
        var total = 0.0
        var prev = first
        val iter = samples.iterator()
        iter.next() // skip first
        while (iter.hasNext()) {
            val cur = iter.next()
            total += haversineMetres(prev.latDeg, prev.lonDeg, cur.latDeg, cur.lonDeg)
            prev = cur
        }
        if (total < 5.0) return null
        return (total / 1000.0 / (elapsedMs / 3_600_000.0)).toFloat()
    }

    /** Wall-clock span of the current window in seconds — useful for
     *  the UI to show "avg over 10s" or "avg over 12s" depending on
     *  data density. */
    fun windowSeconds(): Long {
        if (samples.size < 2) return 0L
        return (samples.last().timeMs - samples.first().timeMs) / 1000L
    }

    /** Total horizontal distance over the current window in metres. */
    fun windowMetres(): Double {
        if (samples.size < 2) return 0.0
        var total = 0.0
        var prev = samples.first()
        val iter = samples.iterator()
        iter.next()
        while (iter.hasNext()) {
            val cur = iter.next()
            total += haversineMetres(prev.latDeg, prev.lonDeg, cur.latDeg, cur.lonDeg)
            prev = cur
        }
        return total
    }

    fun clear() {
        samples.clear()
    }

    private fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val midLatRad = Math.toRadians((lat1 + lat2) / 2.0)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * kotlin.math.cos(midLatRad)
        return EARTH_R_M * kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }

    private companion object {
        const val EARTH_R_M = 6_371_000.0
    }
}
