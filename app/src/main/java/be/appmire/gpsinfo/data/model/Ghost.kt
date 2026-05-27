package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * A "ghost runner" (virtual partner) to race against on the Runner
 * dashboard. The persisted *selection* is a [GhostReference]; the
 * runtime curve used for live comparison is a [Ghost].
 *
 * Three sources, all reducing to "where is the ghost at elapsed time
 * t, and when does it reach distance d":
 *  - [TargetPace]  — an even pace (sec per km). Constant speed.
 *  - [Goal]        — a goal distance in a goal time. Constant speed.
 *  - [PastRun]     — a previously recorded trail, replayed by its own
 *                    distance-over-time curve (variable speed).
 */
sealed interface GhostReference {
    /** Even-pace partner. Pace stored canonically in seconds per km. */
    @Immutable data class TargetPace(val secondsPerKm: Float) : GhostReference

    /** Hit [totalMeters] in [totalSeconds] — partner holds the implied
     *  average pace. */
    @Immutable data class Goal(val totalSeconds: Long, val totalMeters: Double) : GhostReference

    /** Race a saved trail. The id resolves to a [Ghost] by loading the
     *  trail's points and building its cumulative-distance curve. */
    @Immutable data class PastRun(val trailId: String, val trailName: String) : GhostReference
}

/**
 * Runtime ghost curve. Both directions are needed for the dual gap
 * readout: distance-at-time for the distance gap, time-at-distance for
 * the time gap.
 */
interface Ghost {
    /** How far the ghost has travelled by [elapsedMs] from the start. */
    fun distanceMetersAt(elapsedMs: Long): Double

    /** When the ghost reaches [meters] of cumulative distance. */
    fun elapsedMsAtDistance(meters: Double): Long

    /** Total distance the ghost ever covers (for "ghost finished"). */
    val totalMeters: Double

    /** Total time the ghost runs (ms). */
    val totalMillis: Long
}

/** Constant-speed ghost — backs both [GhostReference.TargetPace] and
 *  [GhostReference.Goal]. */
class ConstantPaceGhost(
    private val speedMps: Double,
    override val totalMeters: Double = Double.MAX_VALUE,
    override val totalMillis: Long = Long.MAX_VALUE,
) : Ghost {
    override fun distanceMetersAt(elapsedMs: Long): Double =
        speedMps * (elapsedMs / 1000.0)

    override fun elapsedMsAtDistance(meters: Double): Long =
        if (speedMps <= 0.0) 0L else (meters / speedMps * 1000.0).toLong()

    companion object {
        fun fromPaceSecPerKm(secondsPerKm: Float): ConstantPaceGhost =
            ConstantPaceGhost(speedMps = if (secondsPerKm > 0f) 1000.0 / secondsPerKm else 0.0)

        fun fromGoal(totalSeconds: Long, totalMeters: Double): ConstantPaceGhost {
            val v = if (totalSeconds > 0L) totalMeters / totalSeconds else 0.0
            return ConstantPaceGhost(
                speedMps = v,
                totalMeters = totalMeters,
                totalMillis = totalSeconds * 1000L,
            )
        }
    }
}

/**
 * Variable-speed ghost replayed from a recorded run. [samples] is a
 * monotonic-by-time list of (elapsedMs from start, cumulative metres),
 * including a leading (0, 0). Lookups linearly interpolate between
 * samples and clamp past the ends.
 */
class TrailGhost(private val samples: List<Sample>) : Ghost {

    @Immutable
    data class Sample(val elapsedMs: Long, val cumMeters: Double)

    override val totalMeters: Double = samples.lastOrNull()?.cumMeters ?: 0.0
    override val totalMillis: Long = samples.lastOrNull()?.elapsedMs ?: 0L

    override fun distanceMetersAt(elapsedMs: Long): Double {
        if (samples.isEmpty()) return 0.0
        if (elapsedMs <= samples.first().elapsedMs) return samples.first().cumMeters
        if (elapsedMs >= samples.last().elapsedMs) return samples.last().cumMeters
        // Binary search for the bracketing pair by time.
        var lo = 0
        var hi = samples.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].elapsedMs <= elapsedMs) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        val span = (b.elapsedMs - a.elapsedMs).toDouble()
        if (span <= 0.0) return a.cumMeters
        val frac = (elapsedMs - a.elapsedMs) / span
        return a.cumMeters + (b.cumMeters - a.cumMeters) * frac
    }

    override fun elapsedMsAtDistance(meters: Double): Long {
        if (samples.isEmpty()) return 0L
        if (meters <= samples.first().cumMeters) return samples.first().elapsedMs
        if (meters >= samples.last().cumMeters) return samples.last().elapsedMs
        var lo = 0
        var hi = samples.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].cumMeters <= meters) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        val span = b.cumMeters - a.cumMeters
        if (span <= 0.0) return a.elapsedMs
        val frac = (meters - a.cumMeters) / span
        return a.elapsedMs + ((b.elapsedMs - a.elapsedMs) * frac).toLong()
    }
}

/**
 * Live gap between the runner and the ghost. Positive = the runner is
 * AHEAD (reached the current distance sooner / has covered more ground
 * than the ghost at the same elapsed time).
 */
@Immutable
data class GhostGap(
    val aheadSeconds: Double,
    val aheadMeters: Double,
    /** True once the runner has passed the ghost's total distance
     *  (e.g. a goal/past-run that has a finite length). */
    val ghostFinished: Boolean = false,
) {
    val isAhead: Boolean get() = aheadSeconds >= 0.0
}
