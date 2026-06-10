package be.appmire.gpsinfo.obd

/**
 * Adaptive pacing for the live poller — an AIMD controller (like TCP
 * congestion control) over the inter-cycle delay.
 *
 * The adapter/bus is the bottleneck and its capacity is unknown and
 * variable (genuine vs clone ELM, bus load while driving), so instead
 * of a fixed rate we *probe* it: back off **multiplicatively** the
 * instant we see overload (a read timeout, an ELM busy marker like
 * BUFFER FULL / BUS BUSY, or a near-timeout latency), and creep the
 * delay back down **additively** after a run of clean, prompt reads.
 * The delay oscillates lightly around the sustainable rate and settles.
 *
 * Pure and unit-tested; fed one outcome per ELM read by [ObdManager].
 * NOTE: `NO DATA` is a *prompt, healthy* reply (the ECU just doesn't
 * have that PID), so it counts as a good read, not overload.
 */
class ObdLoadMonitor(
    private val minDelayMs: Long = 20,
    private val maxDelayMs: Long = 2_000,
    startDelayMs: Long = 60,
    private val backoff: Double = 1.7,
    private val decreaseStepMs: Long = 15,
    private val goodStreakToSpeedUp: Int = 8,
    /** A read slower than this is treated as soft overload (set well
     *  above legit multi-frame UDS latency to avoid false positives). */
    private val latencyCeilingMs: Long = 800,
) {
    private var delay = startDelayMs.coerceIn(minDelayMs, maxDelayMs)
    private var goodStreak = 0

    /** Consecutive overload events — lets callers detect a dead link. */
    var consecutiveOverloads = 0
        private set

    fun delayMs(): Long = delay

    fun onOutcome(ok: Boolean, busy: Boolean, timedOut: Boolean, latencyMs: Long) {
        val overloaded = timedOut || busy || latencyMs > latencyCeilingMs
        if (overloaded) {
            consecutiveOverloads++
            goodStreak = 0
            delay = (delay * backoff).toLong().coerceAtMost(maxDelayMs)
        } else if (ok) {
            consecutiveOverloads = 0
            goodStreak++
            if (goodStreak >= goodStreakToSpeedUp) {
                goodStreak = 0
                delay = (delay - decreaseStepMs).coerceAtLeast(minDelayMs)
            }
        }
    }
}
