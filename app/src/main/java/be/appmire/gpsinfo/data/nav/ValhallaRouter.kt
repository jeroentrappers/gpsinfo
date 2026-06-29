package be.appmire.gpsinfo.data.nav

import be.appmire.gpsinfo.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Online routing via the server-side Valhalla service (valhalla.appmire.be,
 * see docs/design/nav-engine-v2.md). Profile-aware (fastest/shortest/
 * economic), fast (re)routing, and **lane guidance**.
 *
 * We request Valhalla's **OSRM-compatible output** (`format=osrm` +
 * `turn_lanes=true`): confirmed empirically that lane data only appears there
 * (`steps[].intersections[].lanes`), NOT in Valhalla's native `maneuvers`
 * array. Bonus: this is the exact OSRM response shape, so the same parser
 * also fits the charging project's OSRM (a future offline/fallback engine).
 *
 * Needs connectivity; [CompositeRouter]/NavigationController fall back to
 * BRouter offline. Plain HttpURLConnection + org.json to match data.nav.
 */
class ValhallaRouter : Router {

    override fun missingTiles(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<String> =
        emptyList() // server-side: no on-device routing tiles

    override suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile,
    ): OfflineRoute? = withContext(Dispatchers.IO) {
        val base = BuildConfig.ROUTING_BASE_URL.trimEnd('/')
        if (base.isEmpty()) return@withContext null
        val key = BuildConfig.TILES_API_KEY
        val url = URL(base + "/route" + if (key.isNotEmpty()) "?key=$key" else "")

        val body = requestJson(fromLat, fromLon, toLat, toLon, profile).toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            parseOsrm(JSONObject(conn.inputStream.bufferedReader().readText()))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "valhalla route failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Map-match a short GPS [trace] ([lat, lon] points, oldest→newest) and
     * return the posted speed limit (km/h) of the road at the most recent point,
     * or null. Uses Valhalla `/trace_attributes` with `map_snap`; the last
     * matched edge is the road we're currently on. Online only — the caller
     * ([SpeedLimitProvider]) keeps the offline value when this is unreachable.
     */
    suspend fun speedLimit(trace: List<DoubleArray>): Int? = withContext(Dispatchers.IO) {
        val base = BuildConfig.ROUTING_BASE_URL.trimEnd('/')
        if (base.isEmpty() || trace.size < 2) return@withContext null
        val key = BuildConfig.TILES_API_KEY
        val url = URL(base + "/trace_attributes" + if (key.isNotEmpty()) "?key=$key" else "")
        val shape = JSONArray()
        trace.forEach { shape.put(JSONObject().put("lat", it[0]).put("lon", it[1])) }
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", "auto")
            .put("shape_match", "map_snap")
            .put(
                "filters",
                JSONObject()
                    .put("attributes", JSONArray().put("edge.speed_limit"))
                    .put("action", "include"),
            )
            .toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val edges = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("edges")
            val n = edges?.length() ?: 0
            if (n == 0) return@withContext null
            val sl = edges!!.getJSONObject(n - 1).optInt("speed_limit", 0)
            if (sl in 1..200) sl else null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "valhalla speedLimit failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── Request ────────────────────────────────────────────────────

    private fun requestJson(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, profile: RouteProfile,
        alternates: Int = 0,
    ): JSONObject {
        val locations = JSONArray()
            .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
            .put(JSONObject().put("lat", toLat).put("lon", toLon))
        val autoOpts = JSONObject()
        when (profile) {
            RouteProfile.FASTEST -> {}
            RouteProfile.SHORTEST -> autoOpts.put("shortest", true)
            RouteProfile.ECONOMIC -> autoOpts.put("use_highways", 0.3).put("use_tolls", 0.2)
        }
        val req = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("costing_options", JSONObject().put("auto", autoOpts))
            // OSRM-compatible output is the only one that carries lane data;
            // polyline6 keeps the 1e6 precision our decoder expects.
            .put("format", "osrm")
            .put("turn_lanes", true)
            .put("geometries", "polyline6")
        if (alternates > 0) req.put("alternates", alternates)
        return req
    }

    /**
     * Compute up to [count] route alternatives from ([fromLat],[fromLon]) to
     * the destination — used for en-route "fork in the road" suggestions.
     * Returns all routes Valhalla offers (the first is the primary), or an
     * empty list when offline / on error.
     */
    suspend fun routeAlternatives(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double,
        profile: RouteProfile, count: Int = 2,
    ): List<OfflineRoute> = withContext(Dispatchers.IO) {
        val base = BuildConfig.ROUTING_BASE_URL.trimEnd('/')
        if (base.isEmpty()) return@withContext emptyList()
        val key = BuildConfig.TILES_API_KEY
        val url = URL(base + "/route" + if (key.isNotEmpty()) "?key=$key" else "")
        val body = requestJson(fromLat, fromLon, toLat, toLon, profile, alternates = count).toString()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()
            parseAllOsrm(JSONObject(conn.inputStream.bufferedReader().readText()))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "valhalla alternates failed", e)
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    // ── Response (OSRM format) ─────────────────────────────────────

    private fun parseOsrm(d: JSONObject): OfflineRoute? {
        if (d.optString("code") != "Ok") return null
        val routes = d.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        return parseRouteObj(routes.getJSONObject(0))
    }

    /** Every route in an OSRM response (primary first), for alternatives. */
    private fun parseAllOsrm(d: JSONObject): List<OfflineRoute> {
        if (d.optString("code") != "Ok") return emptyList()
        val routes = d.optJSONArray("routes") ?: return emptyList()
        val out = ArrayList<OfflineRoute>(routes.length())
        for (i in 0 until routes.length()) {
            parseRouteObj(routes.getJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun parseRouteObj(route: JSONObject): OfflineRoute? {
        // Build the point list from the per-step geometries (deduping the
        // shared boundary point) so each maneuver's track index lines up
        // with the polyline — equivalent to route.geometry, but indexable.
        val points = ArrayList<RoutePoint>()
        val turns = ArrayList<TurnHint>()
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val step = steps.getJSONObject(si)
                val stepPts = decodePolyline(step.optString("geometry"))
                val startIdx = points.size
                var from = 0
                if (points.isNotEmpty() && stepPts.isNotEmpty()) {
                    val last = points.last()
                    if (kotlin.math.abs(last.lat - stepPts[0][0]) < 1e-7 &&
                        kotlin.math.abs(last.lon - stepPts[0][1]) < 1e-7
                    ) from = 1
                }
                for (i in from until stepPts.size) points.add(RoutePoint(stepPts[i][0], stepPts[i][1]))

                val man = step.optJSONObject("maneuver") ?: continue
                if (man.optString("type") == "depart") continue // route start, not a turn
                turns.add(
                    TurnHint(
                        lat = stepPts.firstOrNull()?.get(0) ?: 0.0,
                        lon = stepPts.firstOrNull()?.get(1) ?: 0.0,
                        command = osrmManeuver(man.optString("type"), man.optString("modifier")),
                        exitNumber = man.optInt("exit", 0),
                        distanceToNextMeters = step.optDouble("distance", 0.0),
                        trackIndex = startIdx,
                        lanes = lanesForStep(step),
                    ),
                )
            }
        }
        if (points.size < 2) return null
        return OfflineRoute(
            points = points,
            distanceMeters = route.optDouble("distance", 0.0).toInt(),
            durationSeconds = route.optDouble("duration", 0.0).toInt(),
            turns = turns,
        )
    }

    /** Lane set to show for a step's maneuver: OSRM puts the turn lanes on
     *  the step's intersections; intersections[0] is the maneuver location.
     *  Take the first intersection that carries a lanes array. (Exact
     *  maneuver↔lane association may want live tuning.) */
    private fun lanesForStep(step: JSONObject): List<Lane>? {
        val ints = step.optJSONArray("intersections") ?: return null
        for (k in 0 until ints.length()) {
            val arr = ints.getJSONObject(k).optJSONArray("lanes") ?: continue
            val lanes = ArrayList<Lane>(arr.length())
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val inds = l.optJSONArray("indications")
                val dirs = ArrayList<TurnCommand>()
                if (inds != null) for (j in 0 until inds.length()) {
                    osrmIndication(inds.optString(j))?.let { dirs.add(it) }
                }
                // Valhalla's osrm output adds "active"; plain OSRM has "valid".
                val active = if (l.has("active")) l.optBoolean("active") else l.optBoolean("valid", false)
                lanes.add(Lane(dirs, active))
            }
            return lanes.ifEmpty { null }
        }
        return null
    }

    /** OSRM lane indication string → [TurnCommand]. */
    private fun osrmIndication(s: String): TurnCommand? = when (s) {
        "straight" -> TurnCommand.STRAIGHT
        "left" -> TurnCommand.TURN_LEFT
        "slight left" -> TurnCommand.TURN_SLIGHT_LEFT
        "sharp left" -> TurnCommand.TURN_SHARP_LEFT
        "right" -> TurnCommand.TURN_RIGHT
        "slight right" -> TurnCommand.TURN_SLIGHT_RIGHT
        "sharp right" -> TurnCommand.TURN_SHARP_RIGHT
        "uturn" -> TurnCommand.U_TURN
        else -> null // "none" / empty
    }

    /** OSRM maneuver type + modifier → [TurnCommand]. */
    private fun osrmManeuver(type: String, modifier: String): TurnCommand {
        if (type == "roundabout" || type == "rotary" || type == "roundabout turn") {
            return TurnCommand.ROUNDABOUT
        }
        return when (modifier) {
            "left" -> TurnCommand.TURN_LEFT
            "right" -> TurnCommand.TURN_RIGHT
            "slight left" -> TurnCommand.TURN_SLIGHT_LEFT
            "slight right" -> TurnCommand.TURN_SLIGHT_RIGHT
            "sharp left" -> TurnCommand.TURN_SHARP_LEFT
            "sharp right" -> TurnCommand.TURN_SHARP_RIGHT
            "uturn" -> TurnCommand.U_TURN
            "straight" -> TurnCommand.STRAIGHT
            else -> when (type) {
                "continue", "merge", "new name", "on ramp", "off ramp", "fork",
                "end of road", "arrive", "notification",
                -> TurnCommand.STRAIGHT
                else -> TurnCommand.UNKNOWN
            }
        }
    }

    /** Decode an encoded polyline (precision 1e6 = polyline6) to lat/lon. */
    private fun decodePolyline(encoded: String, precision: Double = 1e6): List<DoubleArray> {
        if (encoded.isEmpty()) return emptyList()
        val poly = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0
        var lng = 0
        while (index < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            poly.add(doubleArrayOf(lat / precision, lng / precision))
        }
        return poly
    }

    private companion object {
        const val TAG = "ValhallaRouter"
    }
}
