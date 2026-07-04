package be.appmire.gpsinfo.data.charging

/**
 * The user's EV, as far as trip planning cares: how much energy the pack
 * holds, how fast it's spent, how much buffer to keep, and the DC charge
 * ceiling. Fed to the live-pricing charger queries and the charging-stop
 * planner. Live OBD SoC (when connected) supplies the *starting* charge; this
 * profile supplies the physics.
 */
data class EvVehicleProfile(
    /** Usable battery capacity (kWh). */
    val usableKwh: Double,
    /** Real-world consumption (kWh per 100 km). */
    val consumptionKwh100: Double,
    /** Don't let the plan arrive anywhere below this SoC (safety buffer). */
    val minArrivalSocPercent: Int,
    /** Cap each DC fast-charge stop at this SoC (charging past ~80% is slow). */
    val chargeToSocPercent: Int,
    /** Preferred OCPI connector standard, or "" for any. */
    val plugType: String,
    /** Car's maximum DC charge power (kW) — caps the charge-time estimate. */
    val maxDcKw: Double,
) {
    /** Range (km) obtainable from a given SoC delta at the profile's consumption. */
    fun rangeKmFor(socPercent: Double): Double =
        if (consumptionKwh100 <= 0) 0.0
        else (usableKwh * socPercent / 100.0) / consumptionKwh100 * 100.0

    /** Energy (kWh) to move [socPercent] points of the pack. */
    fun kwhForSoc(socPercent: Double): Double = usableKwh * socPercent / 100.0

    companion object {
        /** A mid-size EV, so the feature is usable before customisation. */
        val DEFAULT = EvVehicleProfile(
            usableKwh = 58.0,
            consumptionKwh100 = 18.0,
            minArrivalSocPercent = 10,
            chargeToSocPercent = 80,
            plugType = "",
            maxDcKw = 150.0,
        )
    }
}
