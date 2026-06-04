package be.appmire.gpsinfo.data.rally

import android.location.Location
import be.appmire.gpsinfo.data.model.GnssSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of an in-flight regularity test (RT) run — the
 * rally analogue of [be.appmire.gpsinfo.data.TrailRecordingController].
 *
 * Lifecycle: [arm] a stage from the phone editor → [start] on the
 * marshal's go (manual tap, car or phone) → live [RallyState.Running]
 * with the early/late delta → [stop] (or [disarm] before start).
 *
 * Distance model — built for *continuous recalibration while driving*:
 *   - `rawKm` accumulates filtered GPS hop distances, untouched.
 *   - `drivenKm = rawKm * factor + offsetKm` is what the rally sees.
 *   - A [nudge] (±10 m from the car screen / phone) adjusts
 *     `offsetKm` immediately, **and** once enough raw distance has
 *     accumulated the correction is folded into `factor`
 *     proportionally (offset re-zeroed). So every sync against a
 *     roadbook landmark doesn't just fix the error — it shrinks the
 *     *rate* of future drift, exactly like re-calibrating a wheel
 *     tripmeter mid-stage.
 *
 * Multi-source feeding: [offer] is called from whichever GNSS streams
 * happen to be alive (car screen, phone dashboard, recording service).
 * Fixes are deduped on `elapsedRealtimeNanos`, so double/triple
 * feeding is harmless — same trick TrailRecorder uses.
 */
object RallyController {

    private val _state = MutableStateFlow<RallyState>(RallyState.Idle)
    val state: StateFlow<RallyState> = _state.asStateFlow()

    private var rawKm = 0.0
    private var offsetKm = 0.0
    private var factor = 1.0
    private var lastLocation: Location? = null
    private var lastFixElapsedNanos = 0L

    /** Stage the user picked in the editor; survives Idle→Armed→Running. */
    @Synchronized
    fun arm(stage: RegularityStage) {
        _state.value = RallyState.Armed(stage)
    }

    @Synchronized
    fun disarm() {
        if (_state.value is RallyState.Armed) _state.value = RallyState.Idle
    }

    /** Marshal's go: zero the clock + distance. Keeps the calibration
     *  factor from any previous run this session — tarmac doesn't
     *  change between stages. */
    @Synchronized
    fun start(nowMillis: Long = System.currentTimeMillis()) {
        val stage = when (val s = _state.value) {
            is RallyState.Armed -> s.stage
            is RallyState.Running -> s.stage // restart on double-tap
            else -> return
        }
        rawKm = 0.0
        offsetKm = 0.0
        lastLocation = null
        _state.value = RallyState.Running(
            stage = stage,
            startedAtMillis = nowMillis,
            drivenKm = 0.0,
            deltaSeconds = 0.0,
            targetSpeedKmh = stage.targetSpeedKmhAt(0.0),
            calibrationFactor = factor,
            finished = false,
        )
    }

    /** End the run; back to Armed so the same stage can be restarted. */
    @Synchronized
    fun stop() {
        val s = _state.value
        if (s is RallyState.Running) _state.value = RallyState.Armed(s.stage)
    }

    /** Correct the rally distance by [meters] (e.g. ±10 from the car
     *  screen when passing a roadbook landmark). Folds into the
     *  calibration factor once ≥[FOLD_MIN_RAW_KM] has accumulated —
     *  the continuous-recalibration half of the distance model. */
    @Synchronized
    fun nudge(meters: Double) {
        val s = _state.value as? RallyState.Running ?: return
        offsetKm += meters / 1000.0
        if (rawKm >= FOLD_MIN_RAW_KM) {
            val corrected = rawKm * factor + offsetKm
            if (corrected > 0.0) {
                factor = corrected / rawKm
                offsetKm = 0.0
            }
        }
        publish(s, System.currentTimeMillis())
    }

    /** Feed a GNSS snapshot. No-op unless Running. Safe to call from
     *  multiple streams — deduped on the fix's elapsed-realtime stamp. */
    @Synchronized
    fun offer(snapshot: GnssSnapshot) {
        val s = _state.value as? RallyState.Running ?: return
        val loc = snapshot.location ?: return
        if (loc.elapsedRealtimeNanos <= lastFixElapsedNanos) return
        lastFixElapsedNanos = loc.elapsedRealtimeNanos

        val prev = lastLocation
        lastLocation = loc
        if (prev != null) {
            val hop = prev.distanceTo(loc).toDouble()
            // Reject implausible hops: multipath teleports and fixes
            // too coarse to integrate distance from.
            val accuracyOk = !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M
            if (accuracyOk && hop < MAX_HOP_M) rawKm += hop / 1000.0
        }
        publish(s, System.currentTimeMillis())
    }

    private fun publish(s: RallyState.Running, nowMillis: Long) {
        val drivenKm = rawKm * factor + offsetKm
        val elapsed = (nowMillis - s.startedAtMillis) / 1000.0
        val delta = elapsed - s.stage.targetElapsedSecondsAt(drivenKm)
        _state.value = s.copy(
            drivenKm = drivenKm,
            deltaSeconds = delta,
            targetSpeedKmh = s.stage.targetSpeedKmhAt(drivenKm),
            calibrationFactor = factor,
            finished = s.stage.isComplete(drivenKm),
        )
    }

    private const val MAX_ACCURACY_M = 35f
    private const val MAX_HOP_M = 200.0
    private const val FOLD_MIN_RAW_KM = 0.5
}

/** Lifecycle of a regularity test. */
sealed interface RallyState {
    data object Idle : RallyState

    /** Stage selected, waiting for the marshal's go. */
    data class Armed(val stage: RegularityStage) : RallyState

    /**
     * Clock running. [deltaSeconds] is elapsed − ideal: **positive =
     * late (speed up), negative = early (slow down)**. [drivenKm] is
     * the calibration-corrected rally distance.
     */
    data class Running(
        val stage: RegularityStage,
        val startedAtMillis: Long,
        val drivenKm: Double,
        val deltaSeconds: Double,
        val targetSpeedKmh: Double,
        val calibrationFactor: Double,
        val finished: Boolean,
    ) : RallyState
}
