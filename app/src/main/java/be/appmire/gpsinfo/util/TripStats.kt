package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.TrailSummary

/**
 * Resettable cumulative trip totals — sum of distance and duration over
 * every trail recorded at or after [sinceMillis]. Older trails stay
 * around in the library for personal-records but don't contribute here.
 *
 * Mirrors a car trip computer: persistent across sessions, resettable
 * on demand.
 */
data class TripStats(
    val sinceMillis: Long,
    val trailCount: Int,
    val totalDistanceMeters: Double,
    val totalDurationMillis: Long,
) {
    val isEmpty: Boolean get() = trailCount == 0

    companion object {
        fun from(trails: List<TrailSummary>, sinceMillis: Long): TripStats {
            val eligible = trails.filter { (it.startTimeMillis ?: 0L) >= sinceMillis }
            return TripStats(
                sinceMillis = sinceMillis,
                trailCount = eligible.size,
                totalDistanceMeters = eligible.sumOf { it.distanceMeters },
                totalDurationMillis = eligible.sumOf { it.durationMillis },
            )
        }
    }
}
