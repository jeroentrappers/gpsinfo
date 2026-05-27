package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.UnitSystem

/**
 * Performance scoring for a finished trail. Compares actual run
 * statistics against a target the user set before/during the run.
 *
 * Phase A (this file): pace adherence only. Phase B (deferred —
 * see #48 sub-tasks) adds HR-zone adherence and per-segment scoring.
 *
 * The scoring function is intentionally simple and documented in
 * comments so the user can read what they're being scored on without
 * digging through code:
 *   - 100 = average pace exactly matched target.
 *   - Each second per unit (km / mi / nm) of deviation loses
 *     [POINTS_PER_SECOND_OFF] points.
 *   - Score floor is 0; no negative scores.
 *
 * This isn't a coach. It's a glanceable "how did I do" number.
 */
object TrailScoring {

    /** Result of a finished-trail scoring pass. */
    data class TrailScore(
        /** 0..100 — overall performance against the configured target. */
        val overall: Int,
        /** Average pace in seconds per chosen distance unit. */
        val actualPaceSecondsPerUnit: Float,
        /** Target pace used for the comparison (sec / unit). */
        val targetPaceSecondsPerUnit: Float,
        /** Signed delta: positive = slower than target, negative = faster. */
        val deltaSecondsPerUnit: Float,
        /** 0..100 sub-score for pace adherence. Always present. */
        val paceScore: Int,
        /** 0..100 sub-score for HR-zone adherence — fraction of time
         *  spent in training zones (Z2–Z4). Null when the trail
         *  carried no HR samples; in that case [overall] == [paceScore]. */
        val hrScore: Int? = null,
        /** Per-zone seconds (Z1..Z5 → indices 0..4) the score was
         *  computed from. Empty when no HR data. */
        val timeInZoneSeconds: IntArray = IntArray(0),
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TrailScore) return false
            return overall == other.overall &&
                actualPaceSecondsPerUnit == other.actualPaceSecondsPerUnit &&
                targetPaceSecondsPerUnit == other.targetPaceSecondsPerUnit &&
                deltaSecondsPerUnit == other.deltaSecondsPerUnit &&
                paceScore == other.paceScore &&
                hrScore == other.hrScore &&
                timeInZoneSeconds.contentEquals(other.timeInZoneSeconds)
        }
        override fun hashCode(): Int {
            var r = overall
            r = 31 * r + actualPaceSecondsPerUnit.hashCode()
            r = 31 * r + targetPaceSecondsPerUnit.hashCode()
            r = 31 * r + deltaSecondsPerUnit.hashCode()
            r = 31 * r + paceScore
            r = 31 * r + (hrScore ?: 0)
            r = 31 * r + timeInZoneSeconds.contentHashCode()
            return r
        }
    }

    /**
     * Score a finished trail's pace adherence.
     *
     * @param avgSpeedKmh Average speed over the trail (always km/h).
     * @param targetPaceSecondsPerUnit Goal pace, in seconds per chosen
     *   distance unit. The unit must match [unitSystem].
     * @param unitSystem The unit system the goal was authored in.
     * @return A [TrailScore], or `null` when there isn't enough data to
     *   produce a meaningful number (zero speed, missing goal, etc).
     */
    fun scoreFromPace(
        avgSpeedKmh: Float,
        targetPaceSecondsPerUnit: Float?,
        unitSystem: UnitSystem,
    ): TrailScore? {
        if (targetPaceSecondsPerUnit == null || targetPaceSecondsPerUnit <= 0f) return null
        if (avgSpeedKmh < MIN_SCORABLE_SPEED_KMH) return null
        val actualPace = paceSecondsPerUnit(avgSpeedKmh, unitSystem) ?: return null
        val delta = actualPace - targetPaceSecondsPerUnit
        val pace = paceAdherenceScore(delta)
        return TrailScore(
            overall = pace,
            actualPaceSecondsPerUnit = actualPace,
            targetPaceSecondsPerUnit = targetPaceSecondsPerUnit,
            deltaSecondsPerUnit = delta,
            paceScore = pace,
        )
    }

    /**
     * Combined pace + HR-zone adherence score. Pace score is the same
     * curve as [scoreFromPace]. HR score is the fraction of recording
     * time spent in [trainingZones] (default Z2-Z4 — the "productive"
     * training band); 100% in target = 100, 0% = 0.
     *
     * Overall = [paceWeight] × paceScore + (1 − paceWeight) × hrScore.
     * Default 50/50. Falls back to pace-only when the trail has no HR
     * samples (e.g. trail recorded before BLE pairing, or no monitor
     * was connected).
     */
    fun scoreCombined(
        avgSpeedKmh: Float,
        targetPaceSecondsPerUnit: Float?,
        unitSystem: UnitSystem,
        timeInZoneSeconds: IntArray,
        trainingZones: IntRange = DEFAULT_TRAINING_ZONES,
        paceWeight: Float = 0.5f,
    ): TrailScore? {
        val paceOnly = scoreFromPace(avgSpeedKmh, targetPaceSecondsPerUnit, unitSystem) ?: return null
        val totalSec = timeInZoneSeconds.sum()
        if (totalSec <= 0 || timeInZoneSeconds.size < 5) return paceOnly  // no HR data
        var inTarget = 0
        for (z in trainingZones) {
            val idx = (z - 1).coerceIn(0, timeInZoneSeconds.size - 1)
            inTarget += timeInZoneSeconds[idx]
        }
        val hr = ((inTarget.toDouble() / totalSec) * 100.0).toInt().coerceIn(0, 100)
        val pw = paceWeight.coerceIn(0f, 1f)
        val combined = (paceOnly.paceScore * pw + hr * (1f - pw)).toInt().coerceIn(0, 100)
        return paceOnly.copy(
            overall = combined,
            hrScore = hr,
            timeInZoneSeconds = timeInZoneSeconds.copyOf(),
        )
    }

    private fun paceAdherenceScore(deltaSec: Float): Int {
        val penalty = (kotlin.math.abs(deltaSec) * POINTS_PER_SECOND_OFF).toInt()
        return (100 - penalty).coerceIn(0, 100)
    }

    /** "Productive" training band. Z1 is recovery (too easy for a
     *  scored run) and Z5 is anaerobic (only meaningful in interval
     *  context). Z2–Z4 is the broad target for most workouts. */
    private val DEFAULT_TRAINING_ZONES = 2..4

    /** Below this we treat the recording as "didn't move" — no score. */
    private const val MIN_SCORABLE_SPEED_KMH = 0.5f

    /** Penalty per second-per-unit of deviation. Tuned so:
     *   - 0 s off  → 100 (perfect)
     *   - 10 s off → 90  (excellent)
     *   - 30 s off → 70  (decent)
     *   - 50 s off → 50  (way off)
     *   - 100 s off → 0  (didn't follow the plan) */
    private const val POINTS_PER_SECOND_OFF = 1.0

    /**
     * Convert a stored target pace (seconds per km — the canonical
     * unit used in GPX) back to the user's currently-displayed unit
     * system. Inverse of the normalisation done at save time in
     * [be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel.stopRecording].
     */
    fun targetPaceInUnit(
        targetSecondsPerKm: Float,
        unitSystem: be.appmire.gpsinfo.data.UnitSystem,
    ): Float = when (unitSystem) {
        be.appmire.gpsinfo.data.UnitSystem.Metric -> targetSecondsPerKm
        be.appmire.gpsinfo.data.UnitSystem.Imperial -> targetSecondsPerKm * 1.609344f
        be.appmire.gpsinfo.data.UnitSystem.Nautical -> targetSecondsPerKm * 1.852f
    }
}
