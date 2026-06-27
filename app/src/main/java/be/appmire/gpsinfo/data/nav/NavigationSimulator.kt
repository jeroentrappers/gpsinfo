package be.appmire.gpsinfo.data.nav

import android.location.Location
import android.os.SystemClock
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.data.model.GnssSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Auto-drive ("test drive") simulator — the [androidx.car.app.navigation.NavigationManagerCallback.onAutoDriveEnabled]
 * counterpart Google Play requires of NAVIGATION-category apps to verify
 * turn-by-turn (the reviewer runs
 * `adb shell dumpsys activity service <CarAppService> AUTO_DRIVE`).
 *
 * When [enable]d, [snapshots] walks the vehicle along the active
 * [NavigationController] route at a plausible speed and emits synthetic
 * [GnssSnapshot]s. The car screen routes these through the SAME pipeline as
 * real fixes (renderer + [NavigationController.offer] + rally + speed
 * limit), so the whole surface "drives" itself to the destination —
 * arrival, re-routes and guidance all behave as in a real drive.
 *
 * It seeds from the last known position so a route can still be computed
 * after auto-drive replaces the live GPS, and stands down on [disable]
 * (session destroy / arrival).
 */
object NavigationSimulator {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun enable() { _active.value = true }

    fun disable() { _active.value = false }

    /**
     * Synthetic fix stream for the active route. Collected by the car screen
     * only while [active] (it swaps this in for the real GPS flow), so this
     * may assume it's the live source. Before a route exists it holds at the
     * last known position — enough for [NavigationController.navigateTo] to
     * resolve an origin — then walks the polyline once navigating.
     */
    fun snapshots(): Flow<GnssSnapshot> = flow {
        // Identity of the route we're currently walking, so a silent re-route
        // (new OfflineRoute instance) resets the cursor onto the fresh line.
        var walkedRoute: OfflineRoute? = null
        var cum = DoubleArray(0)
        var alongM = 0.0
        // Sim position before a route exists, so we keep feeding an origin.
        var seedLat: Double? = null
        var seedLon: Double? = null

        while (_active.value) {
            val nav = NavigationController.state.value as? NavigationController.NavState.Navigating
            if (nav == null) {
                // No route yet — keep the last known position alive so the
                // controller can compute one once a destination is chosen.
                val seed = NavigationController.lastKnownLatLon
                if (seed != null) {
                    seedLat = seed.first; seedLon = seed.second
                    emit(stationarySnapshot(seed.first, seed.second))
                }
                walkedRoute = null
                delay(IDLE_POLL_MS)
                continue
            }

            val pts = nav.route.points
            if (pts.size < 2) { delay(FRAME_MS); continue }

            // (Re)initialise the cursor when the route changes underneath us.
            if (nav.route !== walkedRoute) {
                walkedRoute = nav.route
                cum = cumulative(pts)
                // Resume near the current sim position so a re-route doesn't
                // teleport the vehicle back to the route origin.
                alongM = nearestAlong(pts, cum, seedLat, seedLon)
            }

            val total = cum.last()
            if (alongM >= total) {
                // At the end — let NavigationController.offer flip to Arrived
                // from the final emitted fix, then idle.
                emit(routeSnapshot(pts, cum, total, 0.0))
                delay(FRAME_MS)
                continue
            }

            val speedMps = targetSpeedMps(pts, cum, alongM)
            val snap = routeSnapshot(pts, cum, alongM, speedMps)
            snap.location?.let { seedLat = it.latitude; seedLon = it.longitude }
            emit(snap)

            alongM = (alongM + speedMps * FRAME_MS / 1000.0).coerceAtMost(total)
            delay(FRAME_MS)
        }
    }

    /** A non-moving fix at [lat]/[lon] (pre-route hold). */
    private fun stationarySnapshot(lat: Double, lon: Double): GnssSnapshot =
        wrap(makeLocation(lat, lon, speedMps = 0f, bearing = null))

    /** A fix at [alongM] metres along the route, heading toward the next
     *  point, moving at [speedMps]. */
    private fun routeSnapshot(
        pts: List<RoutePoint>,
        cum: DoubleArray,
        alongM: Double,
        speedMps: Double,
    ): GnssSnapshot {
        val (lat, lon, brg) = pointAt(pts, cum, alongM)
        return wrap(makeLocation(lat, lon, speedMps.toFloat(), brg))
    }

    private fun wrap(loc: Location): GnssSnapshot = GnssSnapshot(
        location = loc,
        fix = FixStatus.THREE_D,
        satellites = emptyList(),
        firstFixMillis = System.currentTimeMillis(),
        lastUpdateElapsedRealtime = SystemClock.elapsedRealtime(),
    )

    private fun makeLocation(lat: Double, lon: Double, speedMps: Float, bearing: Float?): Location =
        Location(PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = 4f
            speed = speedMps
            if (bearing != null) this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

    /** Target ground speed at [alongM]: the segment's posted limit (capped),
     *  or a default cruising speed when untagged. */
    private fun targetSpeedMps(pts: List<RoutePoint>, cum: DoubleArray, alongM: Double): Double {
        val idx = segmentIndexAt(cum, alongM)
        val limit = pts.getOrNull(idx + 1)?.maxspeedKmh ?: pts.getOrNull(idx)?.maxspeedKmh
        val kmh = (limit?.toDouble() ?: DEFAULT_KMH).coerceIn(MIN_KMH, MAX_KMH)
        return kmh / 3.6
    }

    // ── Polyline geometry ───────────────────────────────────────────

    private fun cumulative(pts: List<RoutePoint>): DoubleArray {
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + haversineM(pts[i - 1], pts[i])
        }
        return cum
    }

    /** Index of the segment (point i → i+1) containing [alongM]. */
    private fun segmentIndexAt(cum: DoubleArray, alongM: Double): Int {
        if (cum.size < 2) return 0
        var lo = 0
        var hi = cum.size - 1
        // cum is sorted ascending — binary search for the segment.
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= alongM) lo = mid else hi = mid
        }
        return lo.coerceIn(0, cum.size - 2)
    }

    /** Interpolated (lat, lon, bearing) at [alongM] metres along the route. */
    private fun pointAt(pts: List<RoutePoint>, cum: DoubleArray, alongM: Double): Triple<Double, Double, Float> {
        val i = segmentIndexAt(cum, alongM)
        val a = pts[i]
        val b = pts[i + 1]
        val segLen = (cum[i + 1] - cum[i]).coerceAtLeast(1e-6)
        val t = ((alongM - cum[i]) / segLen).coerceIn(0.0, 1.0)
        val lat = a.lat + (b.lat - a.lat) * t
        val lon = a.lon + (b.lon - a.lon) * t
        return Triple(lat, lon, bearingDeg(a.lat, a.lon, b.lat, b.lon))
    }

    /** Along-distance of the route point nearest to [lat]/[lon], used to
     *  resume the cursor after a re-route. 0 when no seed position. */
    private fun nearestAlong(pts: List<RoutePoint>, cum: DoubleArray, lat: Double?, lon: Double?): Double {
        if (lat == null || lon == null) return 0.0
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in pts.indices) {
            val dLat = pts[i].lat - lat
            val dLon = pts[i].lon - lon
            val d = dLat * dLat + dLon * dLon
            if (d < best) { best = d; bestAlong = cum[i] }
        }
        return bestAlong
    }

    private fun haversineM(a: RoutePoint, b: RoutePoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = Math.toRadians(b.lat - a.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val p1 = Math.toRadians(aLat)
        val p2 = Math.toRadians(bLat)
        val dl = Math.toRadians(bLon - aLon)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private const val PROVIDER = "simulated"
    private const val FRAME_MS = 250L
    private const val IDLE_POLL_MS = 300L
    private const val DEFAULT_KMH = 50.0
    private const val MIN_KMH = 20.0
    private const val MAX_KMH = 120.0
}
