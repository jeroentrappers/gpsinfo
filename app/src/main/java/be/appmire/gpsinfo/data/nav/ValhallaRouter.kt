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
 * see docs/design/nav-engine-v2.md and deploy/ansible/roles/valhalla). Gives
 * profile-aware routing (fastest/shortest/economic), fast (re)routing, and —
 * where the data supports it — lane guidance.
 *
 * Needs connectivity; [CompositeRouter] falls back to BRouter offline. Plain
 * HttpURLConnection + org.json to match the rest of data.nav (no new deps).
 *
 * NOTE (verify-vs-live): the maneuver→[TurnCommand] mapping follows Valhalla's
 * documented `type` ids and the lane parsing is best-effort; confirm both
 * against the live `/route` response once the tile build finishes.
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
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            parseTrip(json.optJSONObject("trip") ?: return@withContext null)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "valhalla route failed", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    // ── Request ────────────────────────────────────────────────────

    private fun requestJson(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, profile: RouteProfile,
    ): JSONObject {
        val locations = JSONArray()
            .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
            .put(JSONObject().put("lat", toLat).put("lon", toLon))
        // All profiles use the `auto` cost model; options shape the cost.
        val autoOpts = JSONObject()
        when (profile) {
            RouteProfile.FASTEST -> {}
            RouteProfile.SHORTEST -> autoOpts.put("shortest", true)
            // Economic ≈ lean off motorways + tolls (steadier, cheaper run).
            RouteProfile.ECONOMIC -> autoOpts.put("use_highways", 0.3).put("use_tolls", 0.2)
        }
        return JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("costing_options", JSONObject().put("auto", autoOpts))
            .put("directions_options", JSONObject().put("units", "kilometers"))
    }

    // ── Response ───────────────────────────────────────────────────

    private fun parseTrip(trip: JSONObject): OfflineRoute? {
        val legs = trip.optJSONArray("legs") ?: return null
        if (legs.length() == 0) return null
        val points = ArrayList<RoutePoint>()
        val turns = ArrayList<TurnHint>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val base = points.size // shape indices are per-leg; offset by what we have
            decodePolyline(leg.optString("shape")).forEach { points.add(RoutePoint(it[0], it[1])) }
            val maneuvers = leg.optJSONArray("maneuvers") ?: JSONArray()
            for (mi in 0 until maneuvers.length()) {
                val m = maneuvers.getJSONObject(mi)
                val cmd = maneuverType(m.optInt("type", 0))
                // Skip the synthetic start/destination markers as "turns".
                if (cmd == TurnCommand.UNKNOWN && mi == 0) continue
                turns.add(
                    TurnHint(
                        lat = points.getOrNull(base + m.optInt("begin_shape_index"))?.lat ?: 0.0,
                        lon = points.getOrNull(base + m.optInt("begin_shape_index"))?.lon ?: 0.0,
                        command = cmd,
                        exitNumber = m.optInt("roundabout_exit_count", 0),
                        distanceToNextMeters = m.optDouble("length", 0.0) * 1000.0,
                        trackIndex = base + m.optInt("begin_shape_index"),
                        lanes = parseLanes(m.optJSONArray("lanes")),
                    ),
                )
            }
        }
        if (points.size < 2) return null
        val summary = trip.optJSONObject("summary") ?: JSONObject()
        return OfflineRoute(
            points = points,
            distanceMeters = (summary.optDouble("length", 0.0) * 1000.0).toInt(),
            durationSeconds = summary.optDouble("time", 0.0).toInt(),
            turns = turns,
        )
    }

    /** Best-effort lane parse — Valhalla encodes lane directions as a bitmask.
     *  Verify the exact shape against the live response. */
    private fun parseLanes(arr: JSONArray?): List<Lane>? {
        if (arr == null || arr.length() == 0) return null
        val lanes = ArrayList<Lane>(arr.length())
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            val dirs = laneDirections(l.optInt("directions", l.optInt("valid", 0)))
            val active = l.optInt("active", 0) != 0 || l.optBoolean("active", false)
            lanes.add(Lane(dirs, active))
        }
        return lanes.ifEmpty { null }
    }

    /** Valhalla lane direction bitmask → our [TurnCommand]s. */
    private fun laneDirections(mask: Int): List<TurnCommand> {
        val out = ArrayList<TurnCommand>()
        // kTurnLaneNone=1, Through=2, SharpLeft=4, Left=8, SlightLeft=16,
        // SlightRight=32, Right=64, SharpRight=128, Reverse=256.
        if (mask and 2 != 0) out += TurnCommand.STRAIGHT
        if (mask and 4 != 0) out += TurnCommand.TURN_SHARP_LEFT
        if (mask and 8 != 0) out += TurnCommand.TURN_LEFT
        if (mask and 16 != 0) out += TurnCommand.TURN_SLIGHT_LEFT
        if (mask and 32 != 0) out += TurnCommand.TURN_SLIGHT_RIGHT
        if (mask and 64 != 0) out += TurnCommand.TURN_RIGHT
        if (mask and 128 != 0) out += TurnCommand.TURN_SHARP_RIGHT
        if (mask and 256 != 0) out += TurnCommand.U_TURN
        return out
    }

    /** Valhalla maneuver `type` id → app [TurnCommand]. */
    private fun maneuverType(t: Int): TurnCommand = when (t) {
        9, 23 -> TurnCommand.TURN_SLIGHT_RIGHT
        10, 18, 20 -> TurnCommand.TURN_RIGHT
        11 -> TurnCommand.TURN_SHARP_RIGHT
        16, 24 -> TurnCommand.TURN_SLIGHT_LEFT
        15, 19, 21 -> TurnCommand.TURN_LEFT
        14 -> TurnCommand.TURN_SHARP_LEFT
        12, 13 -> TurnCommand.U_TURN
        26, 27 -> TurnCommand.ROUNDABOUT
        8, 17, 22, 25, 1, 7 -> TurnCommand.STRAIGHT // continue / ramp-straight / merge / start
        else -> TurnCommand.UNKNOWN
    }

    /** Decode a Valhalla/Google encoded polyline (precision 1e6) to lat/lon. */
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
