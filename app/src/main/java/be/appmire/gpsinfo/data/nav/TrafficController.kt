package be.appmire.gpsinfo.data.nav

import be.appmire.gpsinfo.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One live traffic event, WGS84, as served by the traffic service. */
data class TrafficIncident(
    val id: String,
    val source: String,
    val category: String, // congestion|accident|roadworks|laneClosure|rerouting|other
    val subtype: String,
    val severity: String,
    val updated: String,
    /** Polyline (or single point) as [lon, lat] pairs. */
    val geometry: List<DoubleArray>,
)

/**
 * Process-wide client for the live traffic service (traffic.appmire.be) —
 * the analogue of [NavigationController] for traffic. It holds the
 * Server-Sent-Events connection and, on each `update` notification,
 * re-pulls the bbox-scoped snapshot for the current viewport. A periodic
 * safety fetch doubles as the polling fallback when SSE can't connect.
 *
 * Consumed by both surfaces (the car renderer and the phone map): they
 * [start] it, feed a viewport via [setRoute]/[setLocation], and render
 * [incidents]. No-op when no traffic service is configured.
 */
object TrafficController {

    private val _incidents = MutableStateFlow<List<TrafficIncident>>(emptyList())
    val incidents: StateFlow<List<TrafficIncident>> = _incidents.asStateFlow()

    private val base = BuildConfig.TRAFFIC_BASE_URL.trimEnd('/')
    private val key = BuildConfig.TILES_API_KEY
    private val configured get() = base.isNotEmpty()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var jobs = mutableListOf<Job>()
    private var started = false

    /** Current query viewport [minLon, minLat, maxLon, maxLat], or null
     *  (fetch everything). Rounded so small movements don't refetch. */
    @Volatile
    private var bbox: DoubleArray? = null

    @Volatile
    private var conn: HttpURLConnection? = null

    @Synchronized
    fun start() {
        if (started || !configured) return
        started = true
        jobs += scope.launch { sseLoop() }
        jobs += scope.launch { periodicLoop() }
    }

    @Synchronized
    fun stop() {
        started = false
        runCatching { conn?.disconnect() }
        conn = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        _incidents.value = emptyList()
    }

    /** Set the viewport from the active route's bounds (+ margin). */
    fun setRoute(points: List<DoubleArray>) {
        if (points.isEmpty()) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in points) {
            minLat = min(minLat, p[0]); maxLat = max(maxLat, p[0])
            minLon = min(minLon, p[1]); maxLon = max(maxLon, p[1])
        }
        setViewport(minLon - ROUTE_MARGIN, minLat - ROUTE_MARGIN, maxLon + ROUTE_MARGIN, maxLat + ROUTE_MARGIN)
    }

    /** Set the viewport to a box around the current location (free driving). */
    fun setLocation(lat: Double, lon: Double) {
        setViewport(lon - LOCAL_HALF, lat - LOCAL_HALF, lon + LOCAL_HALF, lat + LOCAL_HALF)
    }

    private fun setViewport(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        val next = doubleArrayOf(round2(minLon), round2(minLat), round2(maxLon), round2(maxLat))
        val cur = bbox
        if (cur != null && cur.contentEquals(next)) return
        bbox = next
        if (started) scope.launch { fetchSnapshot() }
    }

    // ── SSE + polling ────────────────────────────────────────────────

    private suspend fun sseLoop() {
        var backoff = 2_000L
        while (scope.isActive && started) {
            try {
                streamEvents() // blocks until the stream ends/errors
                backoff = 2_000L
            } catch (_: Exception) {
                // fall through to backoff
            }
            if (!started) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** Open the SSE stream and fetch a fresh snapshot on every `update`. */
    private suspend fun streamEvents() {
        val c = (URL("$base/events${keyQuery()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 10_000
            readTimeout = 0 // stream stays open
        }
        conn = c
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP ${c.responseCode}")
            fetchSnapshot() // initial load on (re)connect
            c.inputStream.bufferedReader().use { r ->
                while (scope.isActive && started) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("event: update")) fetchSnapshot()
                }
            }
        } finally {
            runCatching { c.disconnect() }
            if (conn === c) conn = null
        }
    }

    /** Safety net: refetch periodically even without SSE (polling fallback
     *  + covers viewport drift between SSE events). */
    private suspend fun periodicLoop() {
        while (scope.isActive && started) {
            fetchSnapshot()
            delay(90_000L)
        }
    }

    @Volatile
    private var fetching = false

    private suspend fun fetchSnapshot() {
        if (fetching) return
        fetching = true
        try {
            val url = URL("$base/traffic${keyQuery()}${bboxQuery()}")
            val c = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                if (c.responseCode != HttpURLConnection.HTTP_OK) return
                val body = c.inputStream.bufferedReader().readText()
                _incidents.value = parse(body)
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            // keep the last good snapshot
        } finally {
            fetching = false
        }
    }

    private fun parse(body: String): List<TrafficIncident> {
        val arr = JSONObject(body).optJSONArray("incidents") ?: return emptyList()
        val out = ArrayList<TrafficIncident>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val g = o.optJSONArray("geometry")
            val geo = ArrayList<DoubleArray>(g?.length() ?: 0)
            if (g != null) for (j in 0 until g.length()) {
                val pt = g.optJSONArray(j) ?: continue
                if (pt.length() >= 2) geo.add(doubleArrayOf(pt.optDouble(0), pt.optDouble(1)))
            }
            out.add(
                TrafficIncident(
                    id = o.optString("id"),
                    source = o.optString("source"),
                    category = o.optString("category", "other"),
                    subtype = o.optString("subtype", ""),
                    severity = o.optString("severity", ""),
                    updated = o.optString("updated", ""),
                    geometry = geo,
                ),
            )
        }
        return out
    }

    private fun keyQuery(): String = if (key.isNotEmpty()) "?key=$key" else ""

    private fun bboxQuery(): String {
        val b = bbox ?: return ""
        val sep = if (key.isNotEmpty()) "&" else "?"
        return "${sep}bbox=${b[0]},${b[1]},${b[2]},${b[3]}"
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    private const val ROUTE_MARGIN = 0.05 // ~5 km padding around the route bounds
    private const val LOCAL_HALF = 0.15   // ~15 km half-box around the user
}
