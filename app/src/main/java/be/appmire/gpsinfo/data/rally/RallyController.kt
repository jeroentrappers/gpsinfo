package be.appmire.gpsinfo.data.rally

import android.location.Location
import android.os.SystemClock
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
 * Distance model — two sources, built for *continuous recalibration
 * while driving*:
 *   - **Wheel** (preferred): a BLE CSC speed sensor on a wheel hub
 *     feeds cumulative revolutions via [offerWheelRevolutions] —
 *     the same physical measurement a Halda/Brantz/Blunik takes from
 *     its probes. Wins whenever its data is fresh (<[WHEEL_FRESH_MS]).
 *   - **GPS** ([offer]): always-available fallback; accuracy/hop
 *     filters reject multipath teleports.
 *
 * `drivenKm` accumulates per-source deltas, each scaled by that
 * source's calibration factor. A [nudge] (±10 m sync against a
 * roadbook landmark) corrects the distance immediately **and** — once
 * the active source has ≥[FOLD_MIN_RAW_KM] of raw distance — folds
 * into that source's factor, shrinking the *rate* of future drift,
 * exactly like re-calibrating a wheel tripmeter mid-stage. For the
 * wheel source the factor IS the circumference calibration: we start
 * from a nominal [WHEEL_CIRCUMFERENCE_M] and let the nudges converge
 * it onto the real tire.
 *
 * Multi-source GNSS feeding is deduped on fix timestamps, so the car
 * screen, phone dashboard and recording service can all call [offer].
 */
object RallyController {

    private val _state = MutableStateFlow<RallyState>(RallyState.Idle)
    val state: StateFlow<RallyState> = _state.asStateFlow()

    /** Corrected rally distance: Σ factored per-source deltas + nudges. */
    private var drivenKmAcc = 0.0
    /** Unfactored per-source distance since [start] — fold denominators. */
    private var rawGpsKm = 0.0
    private var rawWheelKm = 0.0
    /** Per-source calibration. Survive across stages in a session —
     *  neither the tire nor the GPS behaviour changes between RTs. */
    private var gpsFactor = 1.0
    private var wheelFactor = 1.0
    /** Nudges not yet folded into a factor. */
    private var pendingNudgeKm = 0.0

    private var lastLocation: Location? = null
    private var lastFixElapsedNanos = 0L
    private var lastWheelRevs: Long? = null
    private var lastWheelAtMillis = 0L

    /** Stage the user picked in the editor; survives Idle→Armed→Running. */
    @Synchronized
    fun arm(stage: RegularityStage) {
        _state.value = RallyState.Armed(stage)
    }

    @Synchronized
    fun disarm() {
        if (_state.value is RallyState.Armed) _state.value = RallyState.Idle
    }

    /** Marshal's go: zero the clock + distance. Calibration factors
     *  carry over from any previous run this session. */
    @Synchronized
    fun start(nowMillis: Long = System.currentTimeMillis()) {
        val stage = when (val s = _state.value) {
            is RallyState.Armed -> s.stage
            is RallyState.Running -> s.stage // restart on double-tap
            else -> return
        }
        drivenKmAcc = 0.0
        rawGpsKm = 0.0
        rawWheelKm = 0.0
        pendingNudgeKm = 0.0
        lastLocation = null
        lastWheelRevs = null
        _state.value = RallyState.Running(
            stage = stage,
            startedAtMillis = nowMillis,
            drivenKm = 0.0,
            deltaSeconds = 0.0,
            targetSpeedKmh = stage.targetSpeedKmhAt(0.0),
            calibrationFactor = activeFactor(),
            usingWheel = wheelFresh(),
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
     *  screen when passing a roadbook landmark). Folds into the active
     *  source's calibration factor once it has ≥[FOLD_MIN_RAW_KM] of
     *  raw distance — the continuous-recalibration half of the model. */
    @Synchronized
    fun nudge(meters: Double) {
        val s = _state.value as? RallyState.Running ?: return
        val deltaKm = meters / 1000.0
        drivenKmAcc += deltaKm
        pendingNudgeKm += deltaKm
        val activeRaw = if (wheelFresh()) rawWheelKm else rawGpsKm
        if (activeRaw >= FOLD_MIN_RAW_KM) {
            if (wheelFresh()) wheelFactor += pendingNudgeKm / activeRaw
            else gpsFactor += pendingNudgeKm / activeRaw
            pendingNudgeKm = 0.0
        }
        publish(s, System.currentTimeMillis())
    }

    /** Feed a GNSS snapshot. No-op unless Running. Safe to call from
     *  multiple streams — deduped on the fix's elapsed-realtime stamp.
     *  Distance integration defers to the wheel while it's fresh. */
    @Synchronized
    fun offer(snapshot: GnssSnapshot) {
        val s = _state.value as? RallyState.Running ?: return
        val loc = snapshot.location ?: return
        if (loc.elapsedRealtimeNanos <= lastFixElapsedNanos) return
        lastFixElapsedNanos = loc.elapsedRealtimeNanos

        val prev = lastLocation
        lastLocation = loc
        if (prev != null && !wheelFresh()) {
            val hop = prev.distanceTo(loc).toDouble()
            // Reject implausible hops: multipath teleports and fixes
            // too coarse to integrate distance from.
            val accuracyOk = !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M
            if (accuracyOk && hop < MAX_HOP_M) {
                rawGpsKm += hop / 1000.0
                drivenKmAcc += hop / 1000.0 * gpsFactor
            }
        }
        publish(s, System.currentTimeMillis())
    }

    /** Feed the wheel sensor's cumulative revolution counter. The
     *  wheel is the preferred distance source — every fresh sample
     *  shadows GPS integration until the sensor goes quiet. */
    @Synchronized
    fun offerWheelRevolutions(cumulativeRevs: Long) {
        val s = _state.value as? RallyState.Running ?: return
        val prev = lastWheelRevs
        lastWheelRevs = cumulativeRevs
        lastWheelAtMillis = SystemClock.elapsedRealtime()
        if (prev != null) {
            val deltaRevs = wheelRevsDelta(prev, cumulativeRevs)
            if (deltaRevs != null && deltaRevs > 0) {
                val km = deltaRevs * WHEEL_CIRCUMFERENCE_M / 1000.0
                rawWheelKm += km
                drivenKmAcc += km * wheelFactor
            }
        }
        publish(s, System.currentTimeMillis())
    }

    private fun wheelFresh(): Boolean =
        lastWheelRevs != null &&
            SystemClock.elapsedRealtime() - lastWheelAtMillis < WHEEL_FRESH_MS

    private fun activeFactor(): Double = if (wheelFresh()) wheelFactor else gpsFactor

    private fun publish(s: RallyState.Running, nowMillis: Long) {
        val elapsed = (nowMillis - s.startedAtMillis) / 1000.0
        val delta = elapsed - s.stage.targetElapsedSecondsAt(drivenKmAcc)
        _state.value = s.copy(
            drivenKm = drivenKmAcc,
            deltaSeconds = delta,
            targetSpeedKmh = s.stage.targetSpeedKmhAt(drivenKmAcc),
            calibrationFactor = activeFactor(),
            usingWheel = wheelFresh(),
            finished = s.stage.isComplete(drivenKmAcc),
        )
    }

    /**
     * Revolution delta with uint32 wraparound handling. Returns null
     * for deltas that can't be real driving — a sensor reset or
     * reconnect rebases the counter without injecting a distance jump.
     */
    internal fun wheelRevsDelta(prev: Long, curr: Long): Long? {
        var delta = curr - prev
        if (delta < 0) delta += UINT32_RANGE
        return if (delta > MAX_SANE_DELTA_REVS) null else delta
    }

    private const val MAX_ACCURACY_M = 35f
    private const val MAX_HOP_M = 200.0
    private const val FOLD_MIN_RAW_KM = 0.5

    /** Nominal car-tire rolling circumference (≈195/65R15). The wheel
     *  factor converges the few-percent error out via nudges. */
    private const val WHEEL_CIRCUMFERENCE_M = 1.95
    /** Wheel data older than this means the sensor dropped — GPS
     *  resumes distance integration seamlessly. */
    private const val WHEEL_FRESH_MS = 3_000L
    private const val UINT32_RANGE = 1L shl 32
    /** ~600 revs ≈ 1.2 km between two notifications (≤1 s apart) is
     *  impossible driving — treat as counter reset. */
    private const val MAX_SANE_DELTA_REVS = 600L
}

/** Lifecycle of a regularity test. */
sealed interface RallyState {
    data object Idle : RallyState

    /** Stage selected, waiting for the marshal's go. */
    data class Armed(val stage: RegularityStage) : RallyState

    /**
     * Clock running. [deltaSeconds] is elapsed − ideal: **positive =
     * late (speed up), negative = early (slow down)**. [drivenKm] is
     * the calibration-corrected rally distance; [usingWheel] tells
     * the UI whether it's wheel-probe or GPS distance right now.
     */
    data class Running(
        val stage: RegularityStage,
        val startedAtMillis: Long,
        val drivenKm: Double,
        val deltaSeconds: Double,
        val targetSpeedKmh: Double,
        val calibrationFactor: Double,
        val usingWheel: Boolean,
        val finished: Boolean,
    ) : RallyState
}
