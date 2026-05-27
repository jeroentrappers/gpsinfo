package be.appmire.gpsinfo.util

import kotlin.math.abs

/**
 * Exponentially-weighted IIR low-pass for raw GPS altitude.
 *
 * Why this exists: the "altitude" channel of a consumer-grade GNSS
 * fix is the noisiest by far — 1σ vertical accuracy is roughly twice
 * the horizontal figure, and the dashboard's altitude readout jumps
 * around ±5–10 m even when the user is stationary. The bog-standard
 * fix is an α-filter:
 *
 *     y_n = α·x_n + (1 − α)·y_{n−1}
 *
 * with a small α (we use 0.15) — that gives a time constant of ~6
 * samples, smoothing out per-fix jitter while still tracking real
 * climbs within a few seconds at typical 1 Hz fix cadence.
 *
 * Reset-on-jump: a true elevation change (e.g., stepping onto a
 * cable car, an elevator opening up) shows as a large delta in one
 * sample. We detect that and re-seed the filter rather than let it
 * lag for thirty seconds of "ghost elevation". The 50 m threshold is
 * tuned to be larger than the worst typical noise spike but smaller
 * than any genuine ride / walk transition.
 *
 * What this is NOT: a geoid correction — i.e., a HAE→MSL conversion.
 * Real geoid correction needs an EGM model and a 2D undulation grid;
 * that's a much bigger feature. This filter just denoises whatever
 * vertical reference the chip exposes. The displayed altitude is
 * still HAE (or whatever the chip reports), just with the high-
 * frequency noise removed.
 */
class AltitudeFilter(
    private val alpha: Float = DEFAULT_ALPHA,
    private val resetDeltaMeters: Double = DEFAULT_RESET_DELTA,
) {
    private var smoothed: Double? = null

    /**
     * Feed a raw altitude sample and get back the smoothed value. The
     * first sample passes through unfiltered (no history to blend
     * against). Returns null when called with null — callers that
     * want to surface "unknown altitude" can preserve that semantics.
     */
    fun update(rawMeters: Double?): Double? {
        if (rawMeters == null) {
            // Don't clobber the running average on a dropped fix — a
            // single null is far more likely to be a transient than a
            // genuine "lost all altitude" event.
            return smoothed
        }
        val prev = smoothed
        if (prev == null || abs(rawMeters - prev) > resetDeltaMeters) {
            smoothed = rawMeters
            return rawMeters
        }
        val next = alpha * rawMeters + (1.0 - alpha) * prev
        smoothed = next
        return next
    }

    /** Discard accumulated state — e.g., on a new recording or after
     *  the user toggles the feature off and on. */
    fun reset() {
        smoothed = null
    }

    companion object {
        const val DEFAULT_ALPHA = 0.15f
        const val DEFAULT_RESET_DELTA = 50.0
    }
}
