package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * Heart-rate zone configuration. Five zones (Z1–Z5) defined as
 * percentages of [maxBpm]; the four boundaries between them are the
 * user-tunable bits, since real athletes don't fit the textbook
 * "60/70/80/90 %" model.
 *
 * Defaults follow the widely-cited Karvonen-style breakdown for the
 * average adult (60 %, 70 %, 80 %, 90 % of max). The user can override
 * any of them in Settings.
 *
 * A per-trail override is planned (one recording can use a different
 * zone profile, e.g. "recovery" or "intervals") — see backlog. For now
 * every recording uses the global config.
 */
@Immutable
data class HrZoneConfig(
    val maxBpm: Int = DEFAULT_MAX_BPM,
    /** Lower bound of Z2 as a fraction of [maxBpm] (e.g. 0.60 = 60 %). */
    val z2Fraction: Float = 0.60f,
    val z3Fraction: Float = 0.70f,
    val z4Fraction: Float = 0.80f,
    val z5Fraction: Float = 0.90f,
) {
    /** BPM boundary at the bottom of each zone. Z1's bottom is 0. */
    fun zoneBottomBpm(zone: Int): Int = when (zone) {
        1 -> 0
        2 -> (maxBpm * z2Fraction).toInt()
        3 -> (maxBpm * z3Fraction).toInt()
        4 -> (maxBpm * z4Fraction).toInt()
        5 -> (maxBpm * z5Fraction).toInt()
        else -> 0
    }

    /** Which zone (1–5) the given BPM falls in. */
    fun zoneFor(bpm: Int): Int = when {
        bpm < zoneBottomBpm(2) -> 1
        bpm < zoneBottomBpm(3) -> 2
        bpm < zoneBottomBpm(4) -> 3
        bpm < zoneBottomBpm(5) -> 4
        else -> 5
    }

    companion object {
        /** Reasonable default for "no input from user yet" — sits in the
         *  middle of typical adult max-HR for a 30-year-old (~190 bpm).
         *  Users on either tail (younger / older / very fit / sedentary)
         *  should adjust in Settings. */
        const val DEFAULT_MAX_BPM = 190
    }
}
