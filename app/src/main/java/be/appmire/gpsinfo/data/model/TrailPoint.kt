package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * One captured GPS sample inside a [Trail].
 *
 * Fields map 1:1 to GPX 1.1 plus the Garmin `TrackPointExtension v2`
 * namespace (speed/course) so files round-trip through Strava, Garmin
 * Connect, OsmAnd and friends. Nullables represent "field absent at
 * capture time" — we never invent values to satisfy the schema.
 *
 * @param timeMillis   wall-clock UTC at capture (System.currentTimeMillis)
 * @param latDeg       WGS84 latitude
 * @param lonDeg       WGS84 longitude
 * @param eleMeters    altitude above WGS84 ellipsoid; null when no 3D fix
 * @param speedMps     instantaneous speed in m/s (from Location.speed)
 * @param courseDeg    course over ground in degrees (from Location.bearing)
 * @param hAccuracyM   horizontal accuracy at this fix
 * @param vAccuracyM   vertical accuracy (API 26+; null on older or 2D fixes)
 * @param satellitesInFix number of satellites contributing to the fix
 * @param heartRateBpm  beats per minute from a paired BLE HR monitor; null
 *                      when no monitor was connected at capture time.
 *                      Serialised via Garmin's `gpxtpx:hr` extension —
 *                      Strava, Garmin Connect and most fitness platforms
 *                      read it back.
 * @param targetPaceSecondsPerKm per-segment target pace (sec/km), set
 *                      by the user when planning a paced run. Drives the
 *                      live pace-deviation feedback for the SEGMENT
 *                      leading to this point. Falls back to the trail's
 *                      overall target when absent. Stored in a private
 *                      GPSinfo extension so other tools ignore it.
 */
@Immutable
data class TrailPoint(
    val timeMillis: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val eleMeters: Double? = null,
    val speedMps: Float? = null,
    val courseDeg: Float? = null,
    val hAccuracyM: Float? = null,
    val vAccuracyM: Float? = null,
    val satellitesInFix: Int? = null,
    val heartRateBpm: Int? = null,
    val targetPaceSecondsPerKm: Float? = null,
    /** Instantaneous power in watts from a paired BLE cycling power
     *  meter (Cycling Power Service `0x1818`); null when no meter
     *  was connected at capture. Serialised via Garmin's
     *  `gpxpx:PowerInWatts` extension — Strava and Garmin Connect
     *  read it back. */
    val powerWatts: Int? = null,
)

