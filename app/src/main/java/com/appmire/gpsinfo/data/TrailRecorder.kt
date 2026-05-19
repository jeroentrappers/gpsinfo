package com.appmire.gpsinfo.data

import android.location.Location
import android.os.Build
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.TrailPoint
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
 * This avoids ~50 identical points stacking up when the device is
 * stationary (GPS still yields fixes every second) while preserving the
 * sub-second cadence on a moving track.
 */
class TrailRecorder {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // Synchronised: writes from the VM's combine block (main dispatcher),
    // reads from save() called from a coroutine.
    private val lock = Any()
    private val points = ArrayList<TrailPoint>()
    private var startedAtMillis: Long = 0L
    private var lastAcceptedAt: Long = 0L

    fun start() {
        synchronized(lock) {
            points.clear()
            startedAtMillis = System.currentTimeMillis()
            lastAcceptedAt = 0L
        }
        _state.value = RecordingState.Recording(startedAtMillis, 0)
    }

    fun stop(): List<TrailPoint> = synchronized(lock) {
        val snapshot = points.toList()
        points.clear()
        _state.value = RecordingState.Idle
        snapshot
    }

    /**
     * Offer a fresh GNSS snapshot. Accept it iff the throttling rule
     * allows. Cheap to call at high rates — the throttling lives here so
     * callers can stay declarative.
     */
    fun offer(snapshot: GnssSnapshot) {
        if (_state.value !is RecordingState.Recording) return
        val loc = snapshot.location ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = points.lastOrNull()
            val timeOk = (now - lastAcceptedAt) >= MIN_INTERVAL_MILLIS
            val distOk = last == null || flatMetres(
                last.latDeg, last.lonDeg, loc.latitude, loc.longitude
            ) >= MIN_DISTANCE_METRES
            if (!(timeOk || distOk)) return

            points.add(loc.toTrailPoint(snapshot.satellitesInUse))
            lastAcceptedAt = now
        }
        _state.value = RecordingState.Recording(startedAtMillis, points.size)
    }

    private fun Location.toTrailPoint(satsInFix: Int): TrailPoint = TrailPoint(
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
    )

    private companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METRES = 2.0
        const val EARTH_R = 6_371_000.0

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
    data class Recording(val startedAtMillis: Long, val pointCount: Int) : RecordingState
}
