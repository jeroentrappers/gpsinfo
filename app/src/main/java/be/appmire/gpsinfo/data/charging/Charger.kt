package be.appmire.gpsinfo.data.charging

/**
 * One charging option from the `charging.appmire.be` backend — a single
 * charger or a co-located same-power cluster. Mirrors the server's
 * `NearbyCharger` (only the fields the planner + UI need). Prices are LIVE
 * (OCPI/Monta tariffs aggregated server-side), which is the edge over
 * model-based planners: we rank and cost real tariffs, not estimates.
 */
data class Charger(
    val id: Long,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val powerKw: Double,
    /** OCPI connector standard, e.g. IEC_62196_T2_COMBO (CCS). */
    val plugType: String,
    /** "AC" or "DC". */
    val currentType: String,
    /** Distance from the route line (m) for corridor results; else distance
     *  from the query point. */
    val offRouteM: Double,
    val availableCount: Int,
    /** Headline comparable price (10–80% at this charger's power) — a
     *  cross-charger ranking signal. Null when the tariff is unknown. */
    val comparablePriceEur: Double?,
    /** Live price for the exact energy we asked to add, when the query
     *  carried a custom session (energy_kwh). Null otherwise. */
    val sessionPriceEur: Double?,
    val currency: String,
    val statusUpdatedAtMs: Long?,
    val availabilityStale: Boolean,
    /** Operator / data source name. */
    val source: String?,
    // Cluster fields (0 when this row is a single charger).
    val groupTotal: Int,
    val groupAvailable: Int,
) {
    val isDc: Boolean get() = currentType.equals("DC", ignoreCase = true)

    /** Best live price we have for ranking/costing: the exact session price
     *  when available, else the comparable headline. */
    val effectivePriceEur: Double? get() = sessionPriceEur ?: comparablePriceEur
}

/**
 * The backend's driving route for a corridor query, plus the chargers found
 * within the buffer of that line. [routePoints] are [lat, lon] pairs.
 */
data class ChargingCorridor(
    val routePoints: List<DoubleArray>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val chargers: List<Charger>,
)
