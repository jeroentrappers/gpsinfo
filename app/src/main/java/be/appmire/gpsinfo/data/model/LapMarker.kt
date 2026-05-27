package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * One lap split inside a recording. Manually inserted via the "Lap"
 * button on the dashboard; future versions may also auto-insert every
 * km / mile.
 *
 * Persisted in GPX as private `gpsinfo:lap` extension elements at the
 * trail level — kept out of `<trkpt>` because laps are independent
 * markers in time, not properties of any single fix.
 *
 * @param index               1-based sequence number ("Lap 1", "Lap 2", …)
 * @param timeMillis          wall-clock UTC at the moment the lap was marked
 * @param cumulativeDistanceM total trail distance at this lap
 * @param lapDistanceM        distance covered since the previous lap
 *                            (or trail start for lap 1)
 * @param lapDurationMs       elapsed time of this lap
 * @param avgHrBpm            average heart rate during the lap, when a
 *                            BLE HR monitor was connected — null otherwise
 */
@Immutable
data class LapMarker(
    val index: Int,
    val timeMillis: Long,
    val cumulativeDistanceM: Double,
    val lapDistanceM: Double,
    val lapDurationMs: Long,
    val avgHrBpm: Int? = null,
)
