package be.appmire.gpsinfo.data.charging

/**
 * A planned trip the user saved to re-activate later: a named destination.
 * The origin is always the live position at activation time, so a saved
 * "commute" re-plans from wherever you are. Charging stops are (re)computed
 * live each time it's activated, against current SoC + live prices.
 */
data class SavedTrip(
    val id: String,
    val name: String,
    val destLat: Double,
    val destLon: Double,
    val destName: String,
)

/** The destination handed to the charging plan screen (ad-hoc or from a saved
 *  trip). Origin + SoC are resolved live when the plan is computed. */
data class PlanTarget(
    val destLat: Double,
    val destLon: Double,
    val destName: String,
)
