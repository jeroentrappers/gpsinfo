package be.appmire.gpsinfo.data.charging

import kotlin.math.min

/**
 * Ties the charger backend to the pure [ChargingPlanner]: pulls the priced
 * corridor, plans the stops, then prices each chosen stop LIVE for the exact
 * energy it adds (the backend's OCPI tariff engine), so the trip cost reflects
 * real tariffs — not a model. The result is the same [ChargingPlan] with each
 * stop's [ChargingStop.costEur] filled where a live price is available.
 */
class ChargingService(
    private val repo: ChargerRepository = ChargerRepository(),
) {
    val isConfigured: Boolean get() = repo.isConfigured

    suspend fun plan(
        profile: EvVehicleProfile,
        startSocPercent: Double,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): ChargingPlan {
        if (!repo.isConfigured) {
            return ChargingPlan(false, emptyList(), feasible = false, message = "Charging backend not configured")
        }
        val plug = profile.plugType.ifEmpty { null }
        val corridor = repo.alongRoute(
            fromLat, fromLon, toLat, toLon,
            plug = plug,
        ) ?: return ChargingPlan(false, emptyList(), feasible = false, message = "Couldn't load chargers along the route")

        val plan = ChargingPlanner.plan(profile, startSocPercent, corridor)
        if (plan.stops.isEmpty()) return plan

        // Price each stop live for the exact energy it adds.
        val priced = plan.stops.map { stop ->
            val results = repo.cheapestAt(
                lat = stop.charger.lat,
                lon = stop.charger.lon,
                energyKwh = stop.kwhAdded,
                powerKw = if (stop.charger.isDc) min(stop.charger.powerKw, profile.maxDcKw) else stop.charger.powerKw,
                usableKwh = profile.usableKwh,
                consumptionKwh100 = profile.consumptionKwh100,
                plug = plug,
            )
            val match = results.firstOrNull { it.id == stop.charger.id } ?: results.firstOrNull()
            stop.copy(costEur = match?.sessionPriceEur ?: match?.comparablePriceEur ?: stop.charger.effectivePriceEur)
        }
        return plan.copy(stops = priced)
    }
}
