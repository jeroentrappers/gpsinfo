package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The posted speed limit of the road being driven, shown on the cluster
 * whether or not we're navigating.
 *
 * While navigating, the active route already carries a segment-accurate,
 * offline `maxspeed` (from BRouter) — that wins. Free-driving, we look the
 * current road up ourselves, **offline-first then refined online**:
 *
 *  1. **Offline (BRouter):** route a short hop ahead along the current course
 *     and read the first segment's OSM `maxspeed`. Works with no connectivity.
 *  2. **Online (Valhalla):** map-match the recent GPS trace
 *     ([ValhallaRouter.speedLimit]) and override with its result when reachable
 *     — more accurate and not dependent on a routable hop.
 *
 * Ground speed + course are derived from successive fixes rather than trusting
 * `Location.speed` (the emulator and some Bluetooth GPS report 0 while moving —
 * the same reason the map renderer dead-reckons). Lookups are throttled and run
 * only while moving; a transient miss keeps the last value rather than blanking
 * the sign. Coverage is bounded by OSM `maxspeed` tagging.
 */
object SpeedLimitProvider {

    private val _limit = MutableStateFlow<Int?>(null)

    /** Current limit (km/h), or null when unknown. */
    val limit: StateFlow<Int?> = _limit.asStateFlow()

    private var appContext: Context? = null
    private var offline: OfflineRouter? = null
    private val online = ValhallaRouter()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Recent fixes as [lat, lon, elapsedMs] (oldest→newest). */
    private val trace = ArrayDeque<DoubleArray>()
    private var lastLookupMs = 0L
    private var busy = false

    /**
     * Feed one fix. [navLimitKmh] is the route's own limit while [navigating];
     * it's authoritative (offline + segment-accurate) and short-circuits the
     * lookup. Otherwise this throttles and resolves the current road's limit.
     */
    fun offer(context: Context, loc: Location, navigating: Boolean, navLimitKmh: Int?) {
        if (appContext == null) appContext = context.applicationContext

        if (navigating) {
            _limit.value = navLimitKmh
            trace.clear()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val prev = trace.lastOrNull()
        trace.addLast(doubleArrayOf(loc.latitude, loc.longitude, now.toDouble()))
        while (trace.size > TRACE_MAX) trace.removeFirst()
        if (prev == null) return

        // Derive speed + course from successive fixes — don't trust the chip's
        // speed (emulator / some BT GPS report 0 while clearly moving).
        val moved = haversineM(prev[0], prev[1], loc.latitude, loc.longitude)
        val dt = (now - prev[2]) / 1000.0
        val mps = maxOf(
            if (dt > 0.05) moved / dt else 0.0,
            if (loc.hasSpeed()) loc.speed.toDouble() else 0.0,
        )
        // Only while moving — the road can't change at a standstill, and a hop
        // ahead needs a course. Keep showing the last value meanwhile.
        if (mps < MIN_MOVE_MPS) return
        if (busy || now - lastLookupMs < LOOKUP_MS) return
        lastLookupMs = now
        busy = true

        val pts = trace.map { doubleArrayOf(it[0], it[1]) }
        val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > MIN_MOVE_MPS) {
            loc.bearing.toDouble()
        } else {
            bearingDeg(prev[0], prev[1], loc.latitude, loc.longitude)
        }
        val lat = loc.latitude
        val lon = loc.longitude
        scope.launch {
            try {
                // Offline first (shows immediately), then refine online.
                offlineLimit(lat, lon, bearing)?.let { _limit.value = it }
                online.speedLimit(pts)?.let { _limit.value = it }
            } finally {
                busy = false
            }
        }
    }

    /** First segment's OSM `maxspeed` on a short hop ahead, via BRouter. */
    private suspend fun offlineLimit(lat: Double, lon: Double, bearingDeg: Double): Int? {
        val ctx = appContext ?: return null
        val router = offline ?: OfflineRouter(ctx).also { offline = it }
        val aheadLat = lat + AHEAD_M * cos(Math.toRadians(bearingDeg)) / 111_320.0
        val aheadLon = lon + AHEAD_M * sin(Math.toRadians(bearingDeg)) /
            (111_320.0 * cos(Math.toRadians(lat)))
        val route = runCatching {
            router.route(lat, lon, aheadLat, aheadLon, RouteProfile.FASTEST)
        }.getOrNull() ?: return null
        return route.points.firstOrNull { it.maxspeedKmh != null }?.maxspeedKmh
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000.0 * asin(minOf(1.0, sqrt(a)))
    }

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private const val TRACE_MAX = 6
    private const val LOOKUP_MS = 4_000L
    private const val MIN_MOVE_MPS = 1.5
    private const val AHEAD_M = 70.0
}
