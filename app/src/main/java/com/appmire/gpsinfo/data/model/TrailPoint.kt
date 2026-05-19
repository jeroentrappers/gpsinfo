package com.appmire.gpsinfo.data.model

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
)
