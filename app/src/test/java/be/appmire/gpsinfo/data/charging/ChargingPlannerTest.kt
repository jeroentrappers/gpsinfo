package be.appmire.gpsinfo.data.charging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingPlannerTest {

    private val vehicle = EvVehicleProfile(
        usableKwh = 58.0,
        consumptionKwh100 = 18.0,
        minArrivalSocPercent = 10,
        chargeToSocPercent = 80,
        plugType = "",
        maxDcKw = 150.0,
    )

    /** A straight west→east route at [lat], one vertex per [stepDeg] of lon. */
    private fun straightRoute(lon0: Double, lon1: Double, lat: Double, stepDeg: Double = 0.1): List<DoubleArray> {
        val pts = ArrayList<DoubleArray>()
        var lon = lon0
        while (lon <= lon1 + 1e-9) {
            pts.add(doubleArrayOf(lat, lon))
            lon += stepDeg
        }
        return pts
    }

    private fun charger(
        id: Long,
        lat: Double,
        lon: Double,
        powerKw: Double = 150.0,
        dc: Boolean = true,
        price: Double? = 0.40,
    ) = Charger(
        id = id, name = "C$id", address = "", lat = lat, lon = lon,
        powerKw = powerKw, plugType = if (dc) "IEC_62196_T2_COMBO" else "IEC_62196_T2",
        currentType = if (dc) "DC" else "AC", offRouteM = 500.0, availableCount = 4,
        comparablePriceEur = price, sessionPriceEur = null, currency = "EUR",
        statusUpdatedAtMs = null, availabilityStale = false, source = "test",
        groupTotal = 0, groupAvailable = 0,
    )

    @Test
    fun `short trip with plenty of charge needs no stop`() {
        val route = straightRoute(4.0, 4.5, 50.0) // ~36 km
        val corridor = ChargingCorridor(route, distanceMeters = 0.0, durationSeconds = 0.0, chargers = emptyList())
        val plan = ChargingPlanner.plan(vehicle, startSocPercent = 50.0, corridor = corridor)
        assertTrue(plan.reachableWithoutCharging)
        assertTrue(plan.feasible)
        assertTrue(plan.stops.isEmpty())
    }

    @Test
    fun `long trip on low charge inserts a reachable stop and respects buffers`() {
        val route = straightRoute(4.0, 9.0, 50.0) // ~358 km, beyond one charge
        val chargers = listOf(
            charger(1, 50.0, 6.8, price = 0.55), // ~200 km in
            charger(2, 50.0, 6.9, price = 0.40), // ~207 km in, cheaper
        )
        val corridor = ChargingCorridor(route, 0.0, 0.0, chargers)
        val plan = ChargingPlanner.plan(vehicle, startSocPercent = 80.0, corridor = corridor)

        assertFalse(plan.reachableWithoutCharging)
        assertTrue(plan.feasible)
        assertTrue("expected at least one stop", plan.stops.isNotEmpty())
        val first = plan.stops.first()
        // Never plan to arrive below the safety buffer, nor charge past the cap.
        assertTrue("arrival ${first.arrivalSocPercent} >= buffer", first.arrivalSocPercent >= vehicle.minArrivalSocPercent - 1e-6)
        assertTrue("target ${first.targetSocPercent} <= cap", first.targetSocPercent <= vehicle.chargeToSocPercent + 1e-6)
        assertTrue(first.kwhAdded > 0.0)
        assertTrue(first.chargeMinutes > 0.0)
    }

    @Test
    fun `cheaper charger wins among those reachable in the far band`() {
        val route = straightRoute(4.0, 9.0, 50.0)
        val cheap = charger(2, 50.0, 6.9, price = 0.35)
        val pricey = charger(1, 50.0, 6.8, price = 0.65)
        val corridor = ChargingCorridor(route, 0.0, 0.0, listOf(pricey, cheap))
        val plan = ChargingPlanner.plan(vehicle, startSocPercent = 80.0, corridor = corridor)
        assertEquals(2L, plan.stops.first().charger.id)
    }

    @Test
    fun `no charger in range is infeasible`() {
        val route = straightRoute(4.0, 9.0, 50.0)
        val corridor = ChargingCorridor(route, 0.0, 0.0, chargers = emptyList())
        val plan = ChargingPlanner.plan(vehicle, startSocPercent = 80.0, corridor = corridor)
        assertFalse(plan.feasible)
        assertTrue(plan.stops.isEmpty())
    }
}
