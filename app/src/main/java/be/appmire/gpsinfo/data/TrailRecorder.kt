package be.appmire.gpsinfo.data

import android.location.Location
import android.os.Build
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.model.LapMarker
import be.appmire.gpsinfo.data.model.TrailPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Thread-safe in-memory recorder. The ViewModel feeds it [Location]
 * objects from the GNSS flow while recording is active; on stop, the
 * accumulated points are flushed to the [TrailRepository].
 *
 * Capture rule: drop a point if it is either
 *   * older than [MIN_INTERVAL_MILLIS] since the last accepted point, OR
 *   * within [MIN_DISTANCE_METRES] of the last accepted point.
 *
 * Surfaces a rich [RecordingState.Recording] with running totals (point
 * count, distance, step delta) and rolling-window derivations (cadence,
 * stride length) for the Sports Dashboard. The rolling window is 30 s —
 * long enough to smooth GPS / step-counter jitter, short enough that
 * the runner sees real-time feedback when they speed up or slow down.
 */
class TrailRecorder {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lock = Any()
    private val points = ArrayList<TrailPoint>()
    private var startedAtMillis: Long = 0L
    private var lastAcceptedAt: Long = 0L
    // Running sum of segment lengths between accepted points. Cheaper
    // than re-iterating the point list every emit.
    private var totalDistanceM: Double = 0.0
    private var stepBaseline: Long? = null
    private var latestSteps: Long? = null
    // Ring buffer of (wallClockMs, totalSteps) tuples — used to compute
    // rolling-window cadence and stride length. Pruned to ROLLING_WINDOW_MS.
    private val stepHistory: ArrayDeque<Pair<Long, Long>> = ArrayDeque()
    // Latest BPM from a paired BLE heart-rate monitor (if any). Attached
    // to each accepted [TrailPoint] so GPX export carries per-point HR.
    private var latestHr: Int? = null
    // Latest watts from a paired BLE cycling power meter — same pattern
    // as HR: latched between samples, stamped on each accepted point.
    private var latestPower: Int? = null
    // Rolling window of (wallClockMs, rrMs) tuples for HRV computation.
    // RMSSD over the last [HRV_WINDOW_MS] is surfaced on the recording
    // state. Empty until the device delivers its first R-R interval.
    private val rrHistory: ArrayDeque<Pair<Long, Int>> = ArrayDeque()
    // Lap accounting. `laps` carries the closed splits; the trailing
    // "open" lap (since the last mark, or since start) is derived on
    // demand at `recordLap`-time from the running totals.
    private val laps = ArrayList<LapMarker>()
    private var lapStartTimeMs: Long = 0L
    private var lapStartDistanceM: Double = 0.0
    private var lapStartPointIndex: Int = 0
    // Auto-pause state. `pausedAtMs == 0L` means actively recording.
    // `lastMovementMs` is the wall-clock of the last accepted point
    // whose distance from the previous accepted point was meaningful;
    // the dashboard uses it to surface "stationary for N seconds".
    private var pausedAtMs: Long = 0L
    private var lastMovementMs: Long = 0L
    // Battery-aware sampling. Below [LOW_BATTERY_PCT], the offer()
    // throttle widens to [LOW_BATTERY_INTERVAL_MS] so the GPS chip can
    // doze between fixes — trades a coarser trail for survival of a
    // long backpacking trip. Updated externally via [setBatteryLevel].
    @Volatile private var batteryLevelPct: Int = 100

    fun start() {
        synchronized(lock) {
            points.clear()
            startedAtMillis = System.currentTimeMillis()
            lastAcceptedAt = 0L
            totalDistanceM = 0.0
            stepBaseline = null
            latestSteps = null
            stepHistory.clear()
            latestHr = null
            latestPower = null
            laps.clear()
            lapStartTimeMs = startedAtMillis
            lapStartDistanceM = 0.0
            lapStartPointIndex = 0
            pausedAtMs = 0L
            lastMovementMs = startedAtMillis
            rrHistory.clear()
        }
        _state.value = snapshotRecording()
    }

    fun stop(): RecordingResult = synchronized(lock) {
        val pointSnapshot = points.toList()
        val lapSnapshot = laps.toList()
        points.clear()
        laps.clear()
        totalDistanceM = 0.0
        stepBaseline = null
        latestSteps = null
        stepHistory.clear()
        lapStartPointIndex = 0
        lapStartDistanceM = 0.0
        _state.value = RecordingState.Idle
        RecordingResult(pointSnapshot, lapSnapshot)
    }

    /**
     * Capture a lap split at the current instant. No-op when not
     * recording. Returns the newly-inserted [LapMarker], or null if
     * the recorder is idle.
     *
     * Average HR for the lap is computed from non-null `heartRateBpm`
     * values across the trail points that fall between the previous
     * lap boundary and now — no separate sample accumulator needed.
     */
    fun recordLap(): LapMarker? = synchronized(lock) {
        if (_state.value !is RecordingState.Recording) return@synchronized null
        val now = System.currentTimeMillis()
        val lapDistance = (totalDistanceM - lapStartDistanceM).coerceAtLeast(0.0)
        val lapDuration = (now - lapStartTimeMs).coerceAtLeast(0L)
        val avgHr = run {
            var sum = 0L
            var n = 0
            for (i in lapStartPointIndex until points.size) {
                val hr = points[i].heartRateBpm ?: continue
                sum += hr
                n++
            }
            if (n > 0) (sum / n).toInt() else null
        }
        val marker = LapMarker(
            index = laps.size + 1,
            timeMillis = now,
            cumulativeDistanceM = totalDistanceM,
            lapDistanceM = lapDistance,
            lapDurationMs = lapDuration,
            avgHrBpm = avgHr,
        )
        laps.add(marker)
        lapStartTimeMs = now
        lapStartDistanceM = totalDistanceM
        lapStartPointIndex = points.size
        _state.value = snapshotRecording()
        marker
    }

    /**
     * Latest total-step count from [android.hardware.Sensor.TYPE_STEP_COUNTER].
     * First call after [start] sets the baseline; subsequent calls extend
     * the rolling history buffer and refresh the published state.
     */
    fun offerSteps(totalSteps: Long) {
        if (_state.value !is RecordingState.Recording) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (stepBaseline == null) stepBaseline = totalSteps
            latestSteps = totalSteps
            stepHistory.addLast(now to totalSteps)
            // Prune entries older than the rolling window.
            while (stepHistory.isNotEmpty() && now - stepHistory.first().first > ROLLING_WINDOW_MS) {
                stepHistory.removeFirst()
            }
        }
        _state.value = snapshotRecording()
    }

    /** Latest BPM from a paired BLE HR monitor. No-op outside an
     *  active recording. Attached to the next accepted trail point so
     *  GPX export carries per-point HR (Garmin gpxtpx:hr extension). */
    fun offerHr(bpm: Int) {
        if (_state.value !is RecordingState.Recording) return
        synchronized(lock) { latestHr = bpm }
    }

    /** Latest watts from a paired BLE cycling power meter. No-op
     *  outside an active recording. Stamped on the next accepted
     *  trail point and persisted as `gpxpx:PowerInWatts` in GPX. */
    fun offerPower(watts: Int) {
        if (_state.value !is RecordingState.Recording) return
        synchronized(lock) { latestPower = watts }
    }

    /** Hint the recorder that the device battery has fallen to [pct].
     *  Below 20 %, the sample-acceptance throttle widens to 5 s to
     *  keep the GPS chip dozing between fixes. */
    fun setBatteryLevel(pct: Int) {
        batteryLevelPct = pct.coerceIn(0, 100)
    }

    /** Latest R-R intervals (ms) from a paired BLE HR monitor that
     *  emits them. No-op outside an active recording. Used to compute
     *  HRV (RMSSD) over a rolling window — straps that don't emit RRs
     *  simply never call this. */
    fun offerRrIntervals(rrMs: List<Int>) {
        if (_state.value !is RecordingState.Recording) return
        if (rrMs.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (r in rrMs) rrHistory.addLast(now to r)
            while (rrHistory.isNotEmpty() && now - rrHistory.first().first > HRV_WINDOW_MS) {
                rrHistory.removeFirst()
            }
        }
        _state.value = snapshotRecording()
    }

    /**
     * Offer a fresh GNSS snapshot. Accept it iff the throttling rule
     * allows, and iff we're not currently auto-paused. Cheap to call at
     * high rates — the throttling lives here so callers can stay
     * declarative.
     *
     * Auto-pause: when the recorder has been idle (no point with > 2 m
     * movement) for [AUTO_PAUSE_AFTER_MS], future fixes are dropped
     * silently. The first fix that lands ≥ 2 m from the last accepted
     * point ends the pause and resumes appending.
     */
    fun offer(snapshot: GnssSnapshot) {
        if (_state.value !is RecordingState.Recording) return
        val loc = snapshot.location ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = points.lastOrNull()
            val distanceFromLast = if (last == null) Double.POSITIVE_INFINITY
            else flatMetres(last.latDeg, last.lonDeg, loc.latitude, loc.longitude)

            // Auto-resume: if we were paused and the runner has moved
            // meaningfully since the last accepted point, snap back
            // into active recording.
            if (pausedAtMs != 0L && distanceFromLast >= MIN_DISTANCE_METRES) {
                pausedAtMs = 0L
                lastMovementMs = now
            }

            if (pausedAtMs != 0L) return

            val minInterval = if (batteryLevelPct < LOW_BATTERY_PCT) LOW_BATTERY_INTERVAL_MS
            else MIN_INTERVAL_MILLIS
            val timeOk = (now - lastAcceptedAt) >= minInterval
            val distOk = last == null || distanceFromLast >= MIN_DISTANCE_METRES
            if (!(timeOk || distOk)) return

            val newPoint = loc.toTrailPoint(snapshot.satellitesInUse, latestHr, latestPower)
            if (last != null) {
                totalDistanceM += distanceFromLast
                if (distanceFromLast >= MIN_DISTANCE_METRES) lastMovementMs = now
            } else {
                lastMovementMs = now
            }
            points.add(newPoint)
            lastAcceptedAt = now

            // Auto-pause trigger evaluated AFTER appending the latest
            // point, so the freeze takes effect from the next offer
            // onward — keeps the timeline contiguous.
            if (now - lastMovementMs >= AUTO_PAUSE_AFTER_MS) {
                pausedAtMs = now
            }
        }
        _state.value = snapshotRecording()
    }

    /** Force the recorder into the paused state regardless of
     *  movement. Auto-resume on movement still applies. No-op when
     *  idle. */
    fun pause() {
        if (_state.value !is RecordingState.Recording) return
        synchronized(lock) {
            if (pausedAtMs == 0L) pausedAtMs = System.currentTimeMillis()
        }
        _state.value = snapshotRecording()
    }

    /** Cancel the paused state immediately. Movement-driven auto-pause
     *  may re-trigger on the next idle window. */
    fun resume() {
        if (_state.value !is RecordingState.Recording) return
        synchronized(lock) {
            pausedAtMs = 0L
            lastMovementMs = System.currentTimeMillis()
        }
        _state.value = snapshotRecording()
    }

    /** Build the current public-facing [RecordingState.Recording] from
     *  the running totals + rolling history. Single source of truth for
     *  state emissions, so the offer / offerSteps paths stay symmetric. */
    private fun snapshotRecording(): RecordingState.Recording = synchronized(lock) {
        val now = System.currentTimeMillis()
        val stepDelta = stepBaseline?.let { base ->
            latestSteps?.let { latest -> (latest - base).coerceAtLeast(0L) }
        }
        RecordingState.Recording(
            startedAtMillis = startedAtMillis,
            pointCount = points.size,
            stepDelta = stepDelta,
            distanceMetres = totalDistanceM,
            cadenceSpm = rollingCadenceSpm(),
            strideMetres = rollingStrideMetres(),
            avgCadenceSpm = avgCadenceSpm(now, stepDelta),
            avgStrideMetres = avgStrideMetres(stepDelta),
            lapCount = laps.size,
            lastLap = laps.lastOrNull(),
            paused = pausedAtMs != 0L,
            secondsSinceMovement = ((now - lastMovementMs) / 1000L).coerceAtLeast(0L),
            hrvRmssdMs = rmssdMs(),
        )
    }

    /** Steps per minute over the rolling window. Null until we have at
     *  least [MIN_ROLLING_MS] of data — instantaneous cadence is too
     *  jittery to surface to a runner. */
    private fun rollingCadenceSpm(): Float? {
        if (stepHistory.size < 2) return null
        val first = stepHistory.first()
        val last = stepHistory.last()
        val ms = last.first - first.first
        if (ms < MIN_ROLLING_MS) return null
        val steps = (last.second - first.second).coerceAtLeast(0L)
        return (steps.toDouble() / ms.toDouble() * 60_000.0).toFloat()
    }

    /** Distance per step over the rolling window — runner's "stride
     *  length" feedback. Walks the recorded points back to find one
     *  at or before the start of the step-history window. */
    private fun rollingStrideMetres(): Float? {
        if (stepHistory.size < 2) return null
        val first = stepHistory.first()
        val last = stepHistory.last()
        val ms = last.first - first.first
        if (ms < MIN_ROLLING_MS) return null
        val steps = (last.second - first.second).coerceAtLeast(1L)
        val winDistance = distanceSince(first.first) ?: return null
        if (winDistance < 1.0) return null   // hide flapping at stops
        return (winDistance / steps).toFloat()
    }

    /** Sum of segment lengths for points with `timeMillis >= sinceMs`. */
    private fun distanceSince(sinceMs: Long): Double? {
        if (points.size < 2) return null
        var total = 0.0
        for (i in points.indices.reversed()) {
            val p = points[i]
            if (p.timeMillis < sinceMs) break
            if (i > 0) {
                val prev = points[i - 1]
                total += flatMetres(prev.latDeg, prev.lonDeg, p.latDeg, p.lonDeg)
            }
        }
        return total
    }

    /** Average cadence since the recording started. */
    private fun avgCadenceSpm(now: Long, stepDelta: Long?): Float? {
        if (stepDelta == null || stepDelta < 5L) return null
        val ms = (now - startedAtMillis).coerceAtLeast(1L)
        return (stepDelta.toDouble() / ms.toDouble() * 60_000.0).toFloat()
    }

    /** Average stride length since the recording started. */
    private fun avgStrideMetres(stepDelta: Long?): Float? {
        if (stepDelta == null || stepDelta < 10L) return null
        if (totalDistanceM < 1.0) return null
        return (totalDistanceM / stepDelta).toFloat()
    }

    /** RMSSD over the rolling R-R buffer. The textbook short-term HRV
     *  metric: root-mean-square of successive differences. Null until
     *  we have at least 5 intervals (~3-4 seconds of data) — fewer
     *  than that and the metric is statistically meaningless. */
    private fun rmssdMs(): Int? {
        if (rrHistory.size < 5) return null
        var sumSq = 0.0
        var n = 0
        val iter = rrHistory.iterator()
        var prev: Int? = null
        while (iter.hasNext()) {
            val r = iter.next().second
            if (prev != null) {
                val d = (r - prev!!).toDouble()
                sumSq += d * d
                n++
            }
            prev = r
        }
        if (n == 0) return null
        return kotlin.math.sqrt(sumSq / n).toInt()
    }

    private fun Location.toTrailPoint(
        satsInFix: Int,
        hrBpm: Int?,
        powerWatts: Int?,
    ): TrailPoint = TrailPoint(
        timeMillis = if (time > 0) time else System.currentTimeMillis(),
        latDeg = latitude,
        lonDeg = longitude,
        eleMeters = if (hasAltitude()) altitude else null,
        speedMps = if (hasSpeed()) speed else null,
        courseDeg = if (hasBearing()) bearing else null,
        hAccuracyM = if (hasAccuracy()) accuracy else null,
        vAccuracyM = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy())
            verticalAccuracyMeters else null,
        satellitesInFix = satsInFix.takeIf { it > 0 },
        heartRateBpm = hrBpm,
        powerWatts = powerWatts,
    )

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METRES = 2.0
        const val EARTH_R = 6_371_000.0
        /** Rolling window over which cadence + stride are smoothed. */
        const val ROLLING_WINDOW_MS = 30_000L
        /** Don't surface a rolling derivation until we have at least this
         *  much data — avoids the "5:00 then 60:00 then 4:30" flapping
         *  when a recording first starts. */
        const val MIN_ROLLING_MS = 5_000L
        /** Wall-clock idle threshold above which the recorder auto-
         *  pauses. Tuned for runners at a red light / hikers at a
         *  view-point: long enough not to false-trigger between fixes,
         *  short enough to freeze accounting before a long break. */
        const val AUTO_PAUSE_AFTER_MS = 30_000L
        /** Rolling window over which HRV (RMSSD) is computed. 60 s
         *  matches the short-term HRV convention used by sports
         *  watches. */
        const val HRV_WINDOW_MS = 60_000L
        /** Below this battery percentage, the recorder widens its
         *  sample-acceptance window to conserve battery. */
        const val LOW_BATTERY_PCT = 20
        const val LOW_BATTERY_INTERVAL_MS = 5_000L

        fun flatMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val midLatRad = Math.toRadians((lat1 + lat2) / 2.0)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1) * cos(midLatRad)
            return EARTH_R * sqrt(dLat * dLat + dLon * dLon)
        }
    }
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /**
     * Active recording.
     *
     * - [stepDelta] is null until the step counter delivers its first
     *   sample (and stays null forever on devices that lack the sensor
     *   or where ACTIVITY_RECOGNITION wasn't granted).
     * - [distanceMetres] is the total path length walked since `start()`.
     * - [cadenceSpm] / [strideMetres] are rolling 30 s averages; null
     *   until enough samples have accumulated.
     * - [avgCadenceSpm] / [avgStrideMetres] are cumulative since start.
     */
    data class Recording(
        val startedAtMillis: Long,
        val pointCount: Int,
        val stepDelta: Long? = null,
        val distanceMetres: Double = 0.0,
        val cadenceSpm: Float? = null,
        val strideMetres: Float? = null,
        val avgCadenceSpm: Float? = null,
        val avgStrideMetres: Float? = null,
        /** Number of lap splits inserted via the lap button. */
        val lapCount: Int = 0,
        /** Most recent lap split, or null when none yet. The UI uses
         *  this to flash a "Lap N — X km in Y:ZZ" toast / chip
         *  on the dashboard after a tap. */
        val lastLap: LapMarker? = null,
        /** True while the recorder is auto-paused (or manually paused
         *  via [TrailRecordingController.pauseRecording]). Auto-resume
         *  on detected movement is the recorder's responsibility. */
        val paused: Boolean = false,
        /** Wall-clock seconds since the last accepted point that was
         *  more than 2 m from its predecessor. Used by the UI to flag
         *  the "looks stationary" state before auto-pause kicks in. */
        val secondsSinceMovement: Long = 0L,
        /** HRV RMSSD (ms) over the last 60 s of R-R intervals from a
         *  paired BLE chest belt. Null when the device doesn't report
         *  R-R intervals or insufficient samples have accumulated. */
        val hrvRmssdMs: Int? = null,
    ) : RecordingState
}

/** Tuple returned by [TrailRecorder.stop]. Carries both the captured
 *  points and the lap splits the user marked along the way. */
data class RecordingResult(
    val points: List<TrailPoint>,
    val laps: List<LapMarker>,
)
