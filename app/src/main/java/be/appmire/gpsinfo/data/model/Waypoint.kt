package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * A user-captured point of interest, optionally with a media
 * attachment. Separate from [TrailPoint] (the GPS sample stream) and
 * from navigation targets — waypoints exist independently of any
 * active recording so a user can flag "old well", "trail junction",
 * "view of the bay" without first starting a trail.
 *
 * Persistence is JSON-on-disk; see `WaypointRepository`. Media files
 * live under `filesDir/waypoints/` and are referenced by relative
 * filename so the JSON stays portable across backup / restore.
 */
@Immutable
data class Waypoint(
    /** Stable, opaque id — UUIDv4. */
    val id: String,
    /** UTC wall-clock at capture. */
    val timeMillis: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val eleMeters: Double? = null,
    /** Free-form user text. Empty when only media was captured. */
    val note: String = "",
    /** Media attachment, if any. */
    val media: WaypointMedia = WaypointMedia.None,
)

/**
 * Discriminated union of waypoint attachments. Each non-None variant
 * carries the filename of the media file under `filesDir/waypoints/`.
 * The filename is the only persisted reference — the actual bytes
 * live on disk so the JSON list stays small.
 */
@Immutable
sealed interface WaypointMedia {
    @Immutable data object None : WaypointMedia

    /** Voice note recorded via `MediaRecorder` as AAC-LC in an M4A
     *  container. Universal playback target — every Android version
     *  back to API 24 can play it without a transcode. */
    @Immutable data class Voice(val fileName: String, val durationMs: Long) : WaypointMedia

    /** Photo captured via the system camera intent
     *  (`ActivityResultContracts.TakePicture`). Stored as a JPEG. */
    @Immutable data class Photo(val fileName: String) : WaypointMedia
}
