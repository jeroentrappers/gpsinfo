package be.appmire.gpsinfo.data.charging

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One planned charging stop along the route. */
data class ChargingStop(
    val charger: Charger,
    /** Distance from the origin along the route to this stop (m). */
    val alongDistanceM: Double,
    /** SoC on arrival at this charger (%). */
    val arrivalSocPercent: Double,
    /** SoC we charge up to before leaving (%). */
    val targetSocPercent: Double,
    /** Energy added at this stop (kWh). */
    val kwhAdded: Double,
    /** Estimated charge time (minutes) at the effective power. */
    val chargeMinutes: Double,
    /** Live cost for [kwhAdded], filled by [ChargingService] from the
     *  backend's tariff engine; null until priced. */
    val costEur: Double? = null,
)

/** The result of planning a trip's charging. */
data class ChargingPlan(
    /** True when the destination is reachable on the current charge with no
     *  stop (still honouring the arrival buffer). */
    val reachableWithoutCharging: Boolean,
    val stops: List<ChargingStop>,
    val feasible: Boolean,
    val message: String? = null,
) {
    val totalChargeMinutes: Double get() = stops.sumOf { it.chargeMinutes }
    val totalCostEur: Double? get() =
        stops.mapNotNull { it.costEur }.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * Greedy charging-stop planner. Pure (no I/O) so it unit-tests cleanly: given
 * the vehicle, the starting SoC (live from OBD, or manual), and a corridor of
 * priced chargers, it decides where to stop and how much to add.
 *
 * Strategy: drive as far as the current charge allows (keeping the arrival
 * buffer), then among the chargers reachable near the far end of that range
 * pick the best by live price / power / detour, charge enough to reach the
 * destination (capped per DC stop), and repeat. Ranking uses the backend's
 * live comparable price — the edge over model-based planners.
 */
object ChargingPlanner {

    // Don't count a charger as a useful stop below this power (kW).
    private const val MIN_USEFUL_KW = 20.0
    // Charge-time fudge for the DC taper we don't model in detail.
    private const val TAPER = 1.15
    // Safety valve against a pathological loop.
    private const val MAX_STOPS = 8

    fun plan(
        profile: EvVehicleProfile,
        startSocPercent: Double,
        corridor: ChargingCorridor,
    ): ChargingPlan {
        val totalM = corridor.distanceMeters.takeIf { it > 0 }
            ?: cumulativeMeters(corridor.routePoints).lastOrNull() ?: 0.0
        if (totalM <= 0) {
            return ChargingPlan(true, emptyList(), feasible = true, message = "No route")
        }

        // Project each charger onto the route to get its distance-from-origin.
        val cum = cumulativeMeters(corridor.routePoints)
        val candidates = corridor.chargers
            .filter { it.powerKw >= MIN_USEFUL_KW }
            .map { it to alongDistanceM(it, corridor.routePoints, cum) }
            .sortedBy { it.second }

        val buffer = profile.minArrivalSocPercent.toDouble()
        var soc = startSocPercent
        var atM = 0.0
        val stops = ArrayList<ChargingStop>()

        // Already there on the current charge?
        if (socForMeters(profile, totalM) + buffer <= soc) {
            return ChargingPlan(true, emptyList(), feasible = true)
        }

        while (stops.size < MAX_STOPS) {
            val reachM = metersForSoc(profile, soc - buffer)
            // Destination in reach now → done.
            if (atM + reachM >= totalM) {
                return ChargingPlan(false, stops, feasible = true)
            }
            val frontier = atM + reachM
            val pick = chooseCharger(candidates, fromM = atM, toM = frontier)
                ?: return ChargingPlan(
                    false, stops, feasible = false,
                    message = "No charger reachable before the next ${
                        "%.0f".format((frontier - atM) / 1000)
                    } km — try a lower arrival buffer or a longer detour.",
                )
            val (charger, alongM) = pick
            if (alongM <= atM + 1.0) {
                return ChargingPlan(false, stops, feasible = false, message = "No forward progress")
            }

            val arrivalSoc = soc - socForMeters(profile, alongM - atM)
            // Charge just enough to reach the destination + arrival buffer, but
            // never past the per-DC-stop cap; if the destination is still out of
            // range from the cap, the loop simply adds another stop.
            val socToDest = socForMeters(profile, totalM - alongM) + buffer
            val cap = profile.chargeToSocPercent.toDouble()
            val finalTarget = min(cap, maxOf(socToDest, arrivalSoc))
            val addSoc = (finalTarget - arrivalSoc).coerceAtLeast(0.0)
            val kwh = profile.kwhForSoc(addSoc)
            val effPower = if (charger.isDc) min(charger.powerKw, profile.maxDcKw) else charger.powerKw
            val minutes = if (effPower > 0) kwh / effPower * 60.0 * TAPER else 0.0

            stops.add(
                ChargingStop(
                    charger = charger,
                    alongDistanceM = alongM,
                    arrivalSocPercent = arrivalSoc,
                    targetSocPercent = finalTarget,
                    kwhAdded = kwh,
                    chargeMinutes = minutes,
                ),
            )
            soc = finalTarget
            atM = alongM
        }
        return ChargingPlan(false, stops, feasible = false, message = "Too many stops needed")
    }

    /** Pick the best charger reachable in (fromM, toM]. Bias toward the far end
     *  (fewer stops), relaxing the bias if nothing's there; among the biased
     *  set, prefer the cheapest live price, then higher power, then less
     *  detour. */
    private fun chooseCharger(
        candidates: List<Pair<Charger, Double>>,
        fromM: Double,
        toM: Double,
    ): Pair<Charger, Double>? {
        val reachable = candidates.filter { it.second > fromM && it.second <= toM }
        if (reachable.isEmpty()) return null
        val span = toM - fromM
        for (bias in doubleArrayOf(0.6, 0.3, 0.0)) {
            val cut = fromM + span * bias
            val set = reachable.filter { it.second >= cut }
            if (set.isNotEmpty()) return set.minWithOrNull(byValue)
        }
        return reachable.minWithOrNull(byValue)
    }

    // Cheapest live price first (nulls last), then higher power, then less detour.
    private val byValue = Comparator<Pair<Charger, Double>> { a, b ->
        val pa = a.first.effectivePriceEur ?: Double.MAX_VALUE
        val pb = b.first.effectivePriceEur ?: Double.MAX_VALUE
        when {
            pa != pb -> pa.compareTo(pb)
            a.first.powerKw != b.first.powerKw -> b.first.powerKw.compareTo(a.first.powerKw)
            else -> a.first.offRouteM.compareTo(b.first.offRouteM)
        }
    }

    // ── geometry / energy ───────────────────────────────────────────

    private fun socForMeters(p: EvVehicleProfile, meters: Double): Double {
        if (p.usableKwh <= 0) return 0.0
        val kwh = (meters / 1000.0) * p.consumptionKwh100 / 100.0
        return kwh / p.usableKwh * 100.0
    }

    private fun metersForSoc(p: EvVehicleProfile, socPercent: Double): Double =
        p.rangeKmFor(socPercent.coerceAtLeast(0.0)) * 1000.0

    private fun cumulativeMeters(pts: List<DoubleArray>): DoubleArray {
        if (pts.isEmpty()) return DoubleArray(0)
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            out[i] = out[i - 1] + haversine(pts[i - 1], pts[i])
        }
        return out
    }

    /** Distance from origin along the route to the nearest route vertex to the
     *  charger (good enough for stop spacing at corridor scale). */
    private fun alongDistanceM(c: Charger, pts: List<DoubleArray>, cum: DoubleArray): Double {
        if (pts.isEmpty()) return 0.0
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in pts.indices) {
            val d = haversine(pts[i], doubleArrayOf(c.lat, c.lon))
            if (d < bestD) { bestD = d; best = i }
        }
        return cum.getOrElse(best) { 0.0 }
    }

    private fun haversine(a: DoubleArray, b: DoubleArray): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b[0] - a[0])
        val dLon = Math.toRadians(b[1] - a[1])
        val la1 = Math.toRadians(a[0])
        val la2 = Math.toRadians(b[0])
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
