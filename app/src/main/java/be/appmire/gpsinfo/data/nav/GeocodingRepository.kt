package be.appmire.gpsinfo.data.nav

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One geocoder hit: a place with coordinates and a display label. */
data class GeocodeResult(
    val lat: Double,
    val lon: Double,
    val label: String,
    /** Short secondary line (city / region / country). */
    val detail: String,
)

/**
 * Address / place search via Photon (komoot's OSM geocoder) — the one
 * networked step in an otherwise offline navigation flow. A search is
 * a single tiny GeoJSON request; once the user picks a result, the
 * route, re-routing and guidance are all computed on-device from the
 * coordinates, so nothing touches the network while driving.
 *
 * Seamless-with-cache: successful queries persist to a small on-disk
 * JSON cache keyed by the normalised query, so repeating a search
 * offline returns the prior hits. A genuinely new search with no
 * connection surfaces [SearchOutcome.Offline] rather than an error.
 *
 * No API key; the public instance is fair-use. The base URL is
 * overridable so a user can point at a self-hosted Photon later.
 */
class GeocodingRepository(
    context: Context,
    private val baseUrl: String = PUBLIC_PHOTON,
) {
    private val cacheFile = File(context.applicationContext.cacheDir, "geocode-cache.json")
    private val memory = LinkedHashMap<String, List<GeocodeResult>>(16, 0.75f, true)

    sealed interface SearchOutcome {
        data class Hits(val results: List<GeocodeResult>, val fromCache: Boolean) : SearchOutcome
        data object Empty : SearchOutcome
        /** No connection and nothing cached for this query. */
        data object Offline : SearchOutcome
    }

    /**
     * Search for [query], optionally biased toward ([biasLat],
     * [biasLon]) so nearby matches rank first. Falls back to the
     * on-disk cache when the network is unavailable.
     */
    suspend fun search(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase()
        if (key.length < MIN_QUERY_LEN) return@withContext SearchOutcome.Empty

        memory[key]?.let { return@withContext SearchOutcome.Hits(it, fromCache = true) }

        val fetched = runCatching { fetch(query, biasLat, biasLon) }.getOrNull()
        if (fetched != null) {
            memory[key] = fetched
            trimMemory()
            persist(key, fetched)
            return@withContext if (fetched.isEmpty()) SearchOutcome.Empty
            else SearchOutcome.Hits(fetched, fromCache = false)
        }

        // Network failed — try the disk cache before giving up.
        loadFromDisk(key)?.let {
            memory[key] = it
            return@withContext SearchOutcome.Hits(it, fromCache = true)
        }
        SearchOutcome.Offline
    }

    private fun fetch(query: String, biasLat: Double?, biasLon: Double?): List<GeocodeResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val bias = if (biasLat != null && biasLon != null) "&lat=$biasLat&lon=$biasLon" else ""
        val url = "$baseUrl/api/?q=$q&limit=$RESULT_LIMIT$bias"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "GPSinfo/be.appmire.gpsinfo")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parsePhoton(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun trimMemory() {
        while (memory.size > MEMORY_ENTRIES) {
            val oldest = memory.keys.firstOrNull() ?: break
            memory.remove(oldest)
        }
    }

    private fun persist(key: String, results: List<GeocodeResult>) {
        val root = runCatching {
            if (cacheFile.exists()) JSONObject(cacheFile.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        root.put(key, resultsToJson(results))
        // Cap the on-disk cache so it can't grow unbounded.
        while (root.length() > DISK_ENTRIES) {
            val it = root.keys()
            if (it.hasNext()) { val k = it.next(); it.remove() } else break
        }
        runCatching { cacheFile.writeText(root.toString()) }
    }

    private fun loadFromDisk(key: String): List<GeocodeResult>? {
        if (!cacheFile.exists()) return null
        return runCatching {
            val root = JSONObject(cacheFile.readText())
            if (!root.has(key)) null else jsonToResults(root.getJSONArray(key))
        }.getOrNull()
    }

    companion object {
        const val PUBLIC_PHOTON = "https://photon.komoot.io"
        private const val MIN_QUERY_LEN = 3
        private const val RESULT_LIMIT = 8
        private const val MEMORY_ENTRIES = 40
        private const val DISK_ENTRIES = 200

        /** Parse a Photon GeoJSON FeatureCollection into results.
         *  Coordinates are [lon, lat]; properties carry the address
         *  fragments we assemble into label + detail. */
        internal fun parsePhoton(body: String): List<GeocodeResult> {
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<GeocodeResult>(features.length())
            for (i in 0 until features.length()) {
                val f = features.optJSONObject(i) ?: continue
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)
                val p = f.optJSONObject("properties") ?: JSONObject()
                out.add(GeocodeResult(lat = lat, lon = lon, label = label(p), detail = detail(p)))
            }
            return out
        }

        private fun label(p: JSONObject): String {
            val name = p.optString("name").takeIf { it.isNotBlank() }
            val street = p.optString("street").takeIf { it.isNotBlank() }
            val house = p.optString("housenumber").takeIf { it.isNotBlank() }
            return when {
                name != null && street == null -> name
                street != null && house != null -> "$street $house"
                street != null -> street
                name != null -> name
                else -> p.optString("city").ifBlank { "Unnamed place" }
            }
        }

        private fun detail(p: JSONObject): String =
            listOfNotNull(
                p.optString("postcode").takeIf { it.isNotBlank() },
                p.optString("city").takeIf { it.isNotBlank() },
                p.optString("state").takeIf { it.isNotBlank() },
                p.optString("country").takeIf { it.isNotBlank() },
            ).joinToString(", ")

        private fun resultsToJson(results: List<GeocodeResult>) =
            org.json.JSONArray().apply {
                results.forEach { r ->
                    put(
                        JSONObject()
                            .put("lat", r.lat).put("lon", r.lon)
                            .put("label", r.label).put("detail", r.detail)
                    )
                }
            }

        private fun jsonToResults(arr: org.json.JSONArray): List<GeocodeResult> =
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GeocodeResult(
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    label = o.getString("label"),
                    detail = o.optString("detail"),
                )
            }
    }
}
