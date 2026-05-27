package be.appmire.gpsinfo.util

import be.appmire.gpsinfo.data.TrailSummary

/**
 * Aggregated personal-best stats computed from the entire trail library.
 *
 * Pure derivation — no I/O, no preferences. Trails with too few points
 * to have meaningful derived fields (e.g. a one-point trail with zero
 * distance) are silently skipped.
 *
 * Average-speed records require a [MIN_AVG_SPEED_DISTANCE_M] floor on
 * the underlying trail's distance; otherwise a 20-metre noisy fix burst
 * can post a 60 km/h "personal best" that's pure GPS jitter.
 */
data class PersonalRecords(
    val trailCount: Int,
    val totalDistanceMeters: Double,
    val totalDurationMillis: Long,
    val longest: TrailSummary?,
    val fastestAvg: TrailSummary?,
    val biggestClimb: TrailSummary?,
    val longestDuration: TrailSummary?,
) {
    val hasAny: Boolean
        get() = trailCount > 0

    companion object {
        const val MIN_AVG_SPEED_DISTANCE_M: Double = 500.0

        fun from(trails: List<TrailSummary>): PersonalRecords {
            if (trails.isEmpty()) {
                return PersonalRecords(
                    trailCount = 0,
                    totalDistanceMeters = 0.0,
                    totalDurationMillis = 0L,
                    longest = null,
                    fastestAvg = null,
                    biggestClimb = null,
                    longestDuration = null,
                )
            }
            return PersonalRecords(
                trailCount = trails.size,
                totalDistanceMeters = trails.sumOf { it.distanceMeters },
                totalDurationMillis = trails.sumOf { it.durationMillis },
                longest = trails.maxByOrNull { it.distanceMeters }
                    ?.takeIf { it.distanceMeters > 0.0 },
                fastestAvg = trails
                    .filter { it.distanceMeters >= MIN_AVG_SPEED_DISTANCE_M && it.avgSpeedKmh > 0f }
                    .maxByOrNull { it.avgSpeedKmh },
                biggestClimb = trails.maxByOrNull { it.ascentMeters }
                    ?.takeIf { it.ascentMeters > 0.0 },
                longestDuration = trails.maxByOrNull { it.durationMillis }
                    ?.takeIf { it.durationMillis > 0L },
            )
        }
    }
}
