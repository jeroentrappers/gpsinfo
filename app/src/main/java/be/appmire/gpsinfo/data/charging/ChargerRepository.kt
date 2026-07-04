package be.appmire.gpsinfo.data.charging

import be.appmire.gpsinfo.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Client for the `charging.appmire.be` REST API (huma). Plain
 * [HttpURLConnection] + org.json on IO, matching the rest of `data.nav`.
 *
 * The backend aggregates AFIR/Monta + OCPI 2.2.1 with LIVE availability and
 * LIVE tariffs, and prices sessions server-side (so we don't re-implement the
 * OCPI tariff engine). Two calls the planner needs:
 *  - [alongRoute]  — corridor search: a route + priced chargers near the line.
 *  - [cheapestAt]  — price a specific spot for an exact energy amount.
 *
 * Empty [BuildConfig.CHARGING_BASE_URL] disables charging features (returns
 * null / empty).
 */
class ChargerRepository {

    private val base = BuildConfig.CHARGING_BASE_URL.trimEnd('/')

    val isConfigured: Boolean get() = base.isNotEmpty()

    /**
     * Chargers along the driving route from ([fromLat],[fromLon]) to
     * ([toLat],[toLon]), within [bufferM] of the line. The backend routes
     * (self-hosted OSRM) and returns its route so overlays line up.
     */
    suspend fun alongRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        bufferM: Int = 2500,
        minPowerKw: Double = 0.0,
        plug: String? = null,
        onlyAvailable: Boolean = false,
    ): ChargingCorridor? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        val q = buildString {
            append("?from_lat=").append(fromLat)
            append("&from_lon=").append(fromLon)
            append("&to_lat=").append(toLat)
            append("&to_lon=").append(toLon)
            append("&buffer=").append(bufferM)
            if (minPowerKw > 0) append("&min_power=").append(minPowerKw)
            if (!plug.isNullOrEmpty()) append("&plug=").append(enc(plug))
            if (onlyAvailable) append("&available=true")
        }
        val obj = getJson("/chargers/along-route$q") ?: return@withContext null
        val route = obj.optJSONObject("route")
        val points = ArrayList<DoubleArray>()
        route?.optJSONArray("points")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                points.add(doubleArrayOf(p.optDouble("lat"), p.optDouble("lon")))
            }
        }
        val chargers = parseChargers(obj)
        ChargingCorridor(
            routePoints = points,
            distanceMeters = route?.optDouble("distance_m", 0.0) ?: 0.0,
            durationSeconds = route?.optDouble("duration_s", 0.0) ?: 0.0,
            chargers = chargers,
        )
    }

    /**
     * Cheapest chargers near ([lat],[lon]), priced by the backend for a custom
     * session of [energyKwh] added at [powerKw] (0 = as fast as possible),
     * using the car's [usableKwh] + [consumptionKwh100]. Used to attach an
     * exact live [Charger.sessionPriceEur] to a planned stop.
     */
    suspend fun cheapestAt(
        lat: Double,
        lon: Double,
        energyKwh: Double,
        powerKw: Double = 0.0,
        usableKwh: Double = 0.0,
        consumptionKwh100: Double = 0.0,
        radiusM: Int = 400,
        minPowerKw: Double = 0.0,
        plug: String? = null,
        limit: Int = 20,
    ): List<Charger> = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext emptyList()
        val q = buildString {
            append("?lat=").append(lat)
            append("&lon=").append(lon)
            append("&radius=").append(radiusM)
            append("&limit=").append(limit)
            if (energyKwh > 0) append("&energy_kwh=").append(energyKwh)
            if (powerKw > 0) append("&power_kw=").append(powerKw)
            if (usableKwh > 0) append("&usable_kwh=").append(usableKwh)
            if (consumptionKwh100 > 0) append("&consumption_kwh100=").append(consumptionKwh100)
            if (minPowerKw > 0) append("&min_power=").append(minPowerKw)
            if (!plug.isNullOrEmpty()) append("&plug=").append(enc(plug))
        }
        val obj = getJson("/chargers/cheapest$q") ?: return@withContext emptyList()
        parseChargers(obj)
    }

    // ── parsing ─────────────────────────────────────────────────────

    private fun parseChargers(obj: JSONObject): List<Charger> {
        val arr = obj.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Charger>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            out.add(
                Charger(
                    id = c.optLong("id"),
                    name = c.optString("name", ""),
                    address = c.optString("address", ""),
                    lat = c.optDouble("lat"),
                    lon = c.optDouble("lon"),
                    powerKw = c.optDouble("power_kw", 0.0),
                    plugType = c.optString("plug_type", ""),
                    currentType = c.optString("current_type", ""),
                    offRouteM = c.optDouble("distance_m", 0.0),
                    availableCount = c.optInt("available_count", 0),
                    comparablePriceEur = c.optDoubleOrNull("comparable_price_eur"),
                    sessionPriceEur = c.optDoubleOrNull("session_price_eur"),
                    currency = c.optString("currency", "EUR"),
                    statusUpdatedAtMs = null,
                    availabilityStale = c.optBoolean("availability_stale", false),
                    source = c.optString("source", null),
                    groupTotal = c.optInt("group_total", 0),
                    groupAvailable = c.optInt("group_available", 0),
                ),
            )
        }
        return out
    }

    private fun getJson(path: String): JSONObject? {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            connectTimeout = 8_000
            readTimeout = 12_000
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** org.json returns NaN for a missing/null double; treat that as null so a
     *  charger with no tariff doesn't read as €NaN. */
    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key) || !has(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN()) null else v
    }
}
