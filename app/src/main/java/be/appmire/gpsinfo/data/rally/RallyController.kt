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
 *   - **Wheel** (preferred): one or more BLE CSC speed sensors on
 *     wheel hubs feed cumulative revolutions via
 *     [offerWheelRevolutions] — the same physical measurement a
 *     Halda/Brantz/Blunik takes from its probes. Wins whenever any
 *     sensor's data is fresh (<[WHEEL_FRESH_MS]).
 *
 *     With multiple sensors each delta contributes `1/nFresh` of its
 *     distance, so the combined value approximates the *mean* wheel
 *     path. Mounted left + right on the same axle that mean IS the
 *     vehicle-centreline distance — cornering asymmetry cancels
 *     geometrically (the reason pro tripmeters take two probes). A
 *     sensor dropping out degrades smoothly: N → N−1 → GPS.
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
    /** Per-sensor revolution counters + last-heard stamps, keyed by MAC. */
    private val wheelLastRevs = HashMap<String, Long>()
    private val wheelLastAtMillis = HashMap<String, Long>()

    /** Injectable monotonic clock — JVM unit tests can't touch
     *  [SystemClock]; production uses the real thing. */
    internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }

    /** Drop session calibration back to 1.0 — new tires, new day, or
     *  test isolation. Not exposed in UI yet. */
    @Synchronized
    internal fun resetCalibration() {
        gpsFactor = 1.0
        wheelFactor = 1.0
    }

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
        wheelLastRevs.clear()
        wheelLastAtMillis.clear()
        _state.value = RallyState.Running(
            stage = stage,
            startedAtMillis = nowMillis,
            drivenKm = 0.0,
            deltaSeconds = 0.0,
            targetSpeedKmh = stage.targetSpeedKmhAt(0.0),
            calibrationFactor = activeFactor(),
            wheelSensorsFresh = 0,
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

    /** Feed one wheel sensor's cumulative revolution counter, keyed by
     *  [sensorId] (the device MAC). Wheel data is the preferred
     *  distance source — every fresh sample shadows GPS integration
     *  until all sensors go quiet. Each delta contributes `1/nFresh`
     *  of its distance so multiple probes average toward the
     *  vehicle-centreline path. The dropout transient is bounded: a
     *  dying sensor undercounts at most [WHEEL_FRESH_MS] of half (one
     *  of two probes) the distance before nFresh adjusts. */
    @Synchronized
    fun offerWheelRevolutions(sensorId: String, cumulativeRevs: Long) {
        val s = _state.value as? RallyState.Running ?: return
        val prev = wheelLastRevs[sensorId]
        wheelLastRevs[sensorId] = cumulativeRevs
        wheelLastAtMillis[sensorId] = elapsedRealtime()
        if (prev != null) {
            val deltaRevs = wheelRevsDelta(prev, cumulativeRevs)
            if (deltaRevs != null && deltaRevs > 0) {
                val km = deltaRevs * WHEEL_CIRCUMFERENCE_M / 1000.0 / freshSensorCount()
                rawWheelKm += km
                drivenKmAcc += km * wheelFactor
            }
        }
        publish(s, System.currentTimeMillis())
    }

    private fun freshSensorCount(): Int {
        val now = elapsedRealtime()
        return wheelLastAtMillis.values.count { now - it < WHEEL_FRESH_MS }.coerceAtLeast(1)
    }

    private fun wheelFresh(): Boolean {
        val now = elapsedRealtime()
        return wheelLastAtMillis.values.any { now - it < WHEEL_FRESH_MS }
    }

    private fun activeFactor(): Double = if (wheelFresh()) wheelFactor else gpsFactor

    private fun publish(s: RallyState.Running, nowMillis: Long) {
        val elapsed = (nowMillis - s.startedAtMillis) / 1000.0
        val delta = elapsed - s.stage.targetElapsedSecondsAt(drivenKmAcc)
        val now = elapsedRealtime()
        _state.value = s.copy(
            drivenKm = drivenKmAcc,
            deltaSeconds = delta,
            targetSpeedKmh = s.stage.targetSpeedKmhAt(drivenKmAcc),
            calibrationFactor = activeFactor(),
            wheelSensorsFresh = wheelLastAtMillis.values.count { now - it < WHEEL_FRESH_MS },
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
     * the calibration-corrected rally distance;
     * [wheelSensorsFresh] is how many wheel probes are currently
     * feeding it (0 = GPS distance).
     */
    data class Running(
        val stage: RegularityStage,
        val startedAtMillis: Long,
        val drivenKm: Double,
        val deltaSeconds: Double,
        val targetSpeedKmh: Double,
        val calibrationFactor: Double,
        val wheelSensorsFresh: Int,
        val finished: Boolean,
    ) : RallyState {
        val usingWheel: Boolean get() = wheelSensorsFresh > 0
    }
}
