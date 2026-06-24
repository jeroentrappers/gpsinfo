package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.location.Location
import be.appmire.gpsinfo.data.model.GnssSnapshot
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide owner of an active offline navigation — the
 * turn-by-turn analogue of the rally/recording controllers.
 *
 * Lifecycle: [navigateTo] downloads any missing rd5 tiles, computes
 * the route with [OfflineRouter], then live [NavState.Navigating]
 * updates flow from every GNSS fix via [offer]: position snapped to
 * the route, distance to the next turn, remaining distance/ETA.
 * Drifting >[OFF_ROUTE_M] from the polyline for
 * [OFF_ROUTE_CONSECUTIVE] consecutive fixes triggers a silent local
 * re-route from the current position — the offline superpower: a
 * re-route costs a second of CPU, no bars required.
 *
 * Guidance voice ([VoiceGuide]) is announced from here so phone and
 * car surfaces stay dumb renderers of [state].
 */
object NavigationController {

    sealed interface NavState {
        data object Idle : NavState

        /** Tiles downloading / route computing. */
        data class Preparing(val detail: String) : NavState

        data class Navigating(
            val route: OfflineRoute,
            /** Index of the route segment the vehicle is on. */
            val segmentIndex: Int,
            /** Metres along the route from its start. */
            val distanceAlongM: Double,
            val distanceRemainingM: Double,
            val etaSeconds: Int,
            val nextTurn: TurnHint?,
            val distanceToTurnM: Double,
            val offRoute: Boolean,
            val destLat: Double,
            val destLon: Double,
            /** Human label for the destination (saved-place title, geocoded
             *  name, …) — shown on the cluster Trip; null when unnamed. */
            val destName: String? = null,
            /** Posted speed limit (km/h) for the segment the vehicle is
             *  on, from the route's OSM `maxspeed`; null when untagged. */
            val speedLimitKmh: Int? = null,
        ) : NavState

        data class Arrived(val destLat: Double, val destLon: Double) : NavState
        data class Failed(val message: String) : NavState
    }

    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Most recent GNSS position seen via [offer] (or [navigateTo]'s
     *  origin), null before the first fix — used to bias destination
     *  search toward where the driver actually is. */
    val lastKnownLatLon: Pair<Double, Double>?
        get() = lastLocation?.let { it.latitude to it.longitude }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var routeJob: Job? = null
    private var corridorJob: Job? = null
    private var router: OfflineRouter? = null
    private var voice: VoiceGuide? = null
    private var offlineMap: OfflineMapRepository? = null

    /** Cumulative metres at each route point — guidance lookup table. */
    private var cumDist = DoubleArray(0)
    private var lastFixElapsedNanos = 0L
    private var offRouteCount = 0
    private var reRouting = false
    private var lastLocation: Location? = null

    /** Destination label of the active route — carried into
     *  [NavState.Navigating] (and reused across silent re-routes, which
     *  keep the same destination) so the cluster can name it. */
    private var lastDestName: String? = null

    /** Start navigating from the current position to ([destLat],
     *  [destLon]). Downloads missing segment tiles first. [destName]
     *  (+ optional [destDetail]) is recorded into recent places so it
     *  can be re-picked without typing — including from the car. */
    /** Profile of the active route, reused across silent re-routes. */
    private var lastProfile = RouteProfile.FASTEST

    fun navigateTo(
        context: Context,
        destLat: Double,
        destLon: Double,
        destName: String? = null,
        destDetail: String = "",
        profile: RouteProfile = RouteProfile.FASTEST,
    ) {
        val appContext = context.applicationContext
        lastProfile = profile
        lastDestName = destName?.takeIf { it.isNotBlank() }
        if (!destName.isNullOrBlank()) {
            PlacesRepository(appContext)
                .recordVisit(destLat, destLon, destName, destDetail, System.currentTimeMillis())
        }
        routeJob?.cancel()
        routeJob = scope.launch {
            // A navigate intent (Assistant "navigate to X", a geo: deep
            // link) routinely arrives at cold start — before the first
            // GNSS lock. Waiting briefly for an origin fix, rather than
            // failing outright, is what makes the intent actually produce
            // a route in that window (the exact case a Play reviewer
            // hits when they ask the car to navigate somewhere).
            val from = awaitOrigin()
            if (from == null) {
                failTransient("No GPS fix yet")
                return@launch
            }
            runRoute(appContext, from, destLat, destLon)
        }
    }

    /** Current origin fix, or wait up to [ORIGIN_WAIT_MS] for the first
     *  one — surfacing an "acquiring GPS" banner meanwhile so the surface
     *  visibly responds to the intent. Null only if no fix ever arrives. */
    private suspend fun awaitOrigin(): Location? {
        currentOrigin()?.let { return it }
        _state.value = NavState.Preparing("Acquiring GPS…")
        return withTimeoutOrNull(ORIGIN_WAIT_MS) {
            var loc = currentOrigin()
            while (loc == null) {
                delay(ORIGIN_POLL_MS)
                loc = currentOrigin()
            }
            loc
        }
    }

    @Synchronized
    private fun currentOrigin(): Location? = lastLocation

    /** Download any missing tiles, compute the route, install it. Assumes
     *  a known [from] origin. */
    private suspend fun runRoute(appContext: Context, from: Location, destLat: Double, destLon: Double) {
        val theRouter = router ?: OfflineRouter(appContext).also { router = it }
        if (voice == null) voice = VoiceGuide(appContext)

        // Fetch any missing road-network tiles for the route bbox.
        val missing = theRouter.missingTiles(from.latitude, from.longitude, destLat, destLon)
        if (missing.isNotEmpty()) {
            val repo = RoutingDataRepository(theRouter.segmentsDir)
            for (tile in missing) {
                repo.download(tile).collect { dl ->
                    when (dl) {
                        is RoutingDataRepository.DownloadState.Progress -> {
                            val pct = if (dl.totalBytes > 0)
                                (dl.bytesRead * 100 / dl.totalBytes).toInt() else 0
                            _state.value = NavState.Preparing("Downloading map $pct%")
                        }
                        is RoutingDataRepository.DownloadState.Failed -> {
                            android.util.Log.w(TAG, "tile download failed: ${dl.tile} ${dl.message}")
                            failTransient("Map download failed")
                        }
                        is RoutingDataRepository.DownloadState.Done -> Unit
                    }
                }
                if (_state.value is NavState.Failed) return
            }
        }

        _state.value = NavState.Preparing("Computing route…")
        val route = try {
            theRouter.route(from.latitude, from.longitude, destLat, destLon, lastProfile)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "routing failed", e)
            null
        }
        if (route == null || route.points.size < 2) {
            failTransient("No route found")
            return
        }
        installRoute(route, destLat, destLon)
        voice?.announceStart(route)
        // Cache the map imagery along the route corridor for
        // offline rendering — the visual counterpart to the rd5
        // road network we just ensured. Background, best-effort;
        // navigation doesn't wait on it. Only on the initial
        // route, not on silent re-routes (which reuse the cache).
        startCorridorDownload(appContext, route)
    }

    /** Show an immediate "finding…" banner the instant a navigate intent
     *  is accepted — before geocoding/origin resolve — so the surface
     *  visibly responds to "navigate to X" right away. */
    fun indicateSearching(label: String?) {
        routeJob?.cancel()
        _state.value = NavState.Preparing(
            if (label.isNullOrBlank()) "Finding destination…" else "Finding $label…"
        )
    }

    /** A navigate intent we accepted couldn't be resolved to a place
     *  (bad geo URI, offline geocode). Clear the searching banner with a
     *  transient failure instead of leaving it spinning forever. */
    fun reportUnresolved(message: String) = failTransient(message)

    /**
     * Best-effort offline-cache of the OpenFreeMap vector tiles over
     * the route's bounding box, so the map renders without a
     * connection along the whole drive. The bbox of a long route is a
     * big rectangle, so [maxZoom] is scaled down for larger spans to
     * keep the tile count under MapLibre's region limit (a too-large
     * request simply fails and we fall back to online tiles).
     */
    private fun startCorridorDownload(context: Context, route: OfflineRoute) {
        corridorJob?.cancel()
        if (route.points.size < 2) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (p in route.points) {
            if (p.lat < minLat) minLat = p.lat
            if (p.lat > maxLat) maxLat = p.lat
            if (p.lon < minLon) minLon = p.lon
            if (p.lon > maxLon) maxLon = p.lon
        }
        val margin = 0.05
        val span = maxOf(maxLat - minLat, maxLon - minLon)
        // Span-scaled detail ceiling: a city hop gets z15, a
        // cross-country route only z10, so the rectangle's tile count
        // stays bounded. MapLibre overzooms beyond maxZoom for closer
        // views (vector tiles scale), so the map still reads fine.
        val maxZoom = when {
            span < 0.3 -> 15.0
            span < 1.0 -> 14.0
            span < 3.0 -> 12.0
            else -> 10.0
        }
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(org.maplibre.android.geometry.LatLng(maxLat + margin, maxLon + margin))
            .include(org.maplibre.android.geometry.LatLng(minLat - margin, minLon - margin))
            .build()
        val repo = offlineMap ?: OfflineMapRepository(context).also { offlineMap = it }
        corridorJob = scope.launch {
            repo.downloadRegion(
                name = "corridor-${System.currentTimeMillis()}",
                bounds = bounds,
                styleUrl = MapLibreStyle.OFFLINE_DOWNLOAD,
                minZoom = 6.0,
                maxZoom = maxZoom,
            ).collect { st ->
                if (st is OfflineMapRepository.DownloadState.Failed) {
                    android.util.Log.w(TAG, "corridor map cache failed: ${st.message}")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        routeJob?.cancel()
        // The corridor map cache keeps downloading after stop on
        // purpose — the tiles stay useful for the next drive; only a
        // new navigateTo (or process death) supersedes it.
        offRouteCount = 0
        reRouting = false
        _state.value = NavState.Idle
    }

    /** Failures are transient by design: surface the banner for a few
     *  seconds, then fall back to Idle so it never sticks around as a
     *  stale error with no dismiss affordance on the car screen. */
    private fun failTransient(message: String) {
        _state.value = NavState.Failed(message)
        scope.launch {
            kotlinx.coroutines.delay(FAILED_BANNER_MS)
            if (_state.value is NavState.Failed) _state.value = NavState.Idle
        }
    }

    /** Feed a GNSS snapshot — multi-source safe (deduped on the fix
     *  timestamp), no-op unless navigating. */
    @Synchronized
    fun offer(snapshot: GnssSnapshot) {
        val loc = snapshot.location ?: return
        if (loc.elapsedRealtimeNanos <= lastFixElapsedNanos) return
        lastFixElapsedNanos = loc.elapsedRealtimeNanos
        lastLocation = loc

        val s = _state.value as? NavState.Navigating ?: return

        val snap = snapToRoute(s.route, loc, s.segmentIndex)
        val remaining = max(0.0, cumDist.last() - snap.alongM)

        // Arrival: close to the end of the polyline.
        if (remaining < ARRIVAL_M) {
            _state.value = NavState.Arrived(s.destLat, s.destLon)
            voice?.announceArrival()
            return
        }

        // Off-route detection → silent local re-route.
        if (snap.crossTrackM > OFF_ROUTE_M) {
            offRouteCount++
            if (offRouteCount >= OFF_ROUTE_CONSECUTIVE && !reRouting) {
                reRouting = true
                val ctxRouter = router
                if (ctxRouter != null) {
                    scope.launch {
                        val fresh = ctxRouter.route(
                            loc.latitude, loc.longitude, s.destLat, s.destLon, lastProfile,
                        )
                        synchronized(this@NavigationController) {
                            reRouting = false
                            offRouteCount = 0
                            if (fresh != null && fresh.points.size >= 2 &&
                                _state.value is NavState.Navigating
                            ) {
                                installRoute(fresh, s.destLat, s.destLon)
                                voice?.announceReroute()
                            }
                        }
                    }
                }
            }
        } else {
            offRouteCount = 0
        }

        val nextTurn = s.route.turns.firstOrNull { it.trackIndex > snap.segmentIndex }
        // Clamp to the distance that's actually left: a turn can never be
        // farther away than the destination. Guards against a stray
        // voice-hint index or a bad snap producing a nonsensical value.
        val distToTurn = (nextTurn?.let {
            max(0.0, cumDist[it.trackIndex.coerceIn(cumDist.indices)] - snap.alongM)
        } ?: remaining).coerceAtMost(remaining)
        // ETA scales the route's own pace over what's left.
        val pace = if (s.route.distanceMeters > 0)
            s.route.durationSeconds.toDouble() / s.route.distanceMeters else 0.0

        android.util.Log.d(
            "NavDiag",
            "offer seg=${snap.segmentIndex} along=${snap.alongM.toInt()}m cross=${snap.crossTrackM.toInt()}m " +
                "nextIdx=${nextTurn?.trackIndex} cmd=${nextTurn?.command} " +
                "distToTurn=${distToTurn.toInt()}m remain=${remaining.toInt()}m",
        )
        _state.value = s.copy(
            segmentIndex = snap.segmentIndex,
            distanceAlongM = snap.alongM,
            distanceRemainingM = remaining,
            etaSeconds = (remaining * pace).toInt(),
            nextTurn = nextTurn,
            distanceToTurnM = distToTurn,
            offRoute = snap.crossTrackM > OFF_ROUTE_M,
            speedLimitKmh = speedLimitAt(s.route, snap.segmentIndex),
        )
        if (nextTurn != null) voice?.maybeAnnounceTurn(nextTurn, distToTurn)
    }

    @Synchronized
    private fun installRoute(route: OfflineRoute, destLat: Double, destLon: Double) {
        cumDist = DoubleArray(route.points.size)
        var acc = 0.0
        for (i in 1 until route.points.size) {
            acc += haversineM(route.points[i - 1], route.points[i])
            cumDist[i] = acc
        }
        android.util.Log.d(
            "NavDiag",
            "install cumLast=${cumDist.lastOrNull()?.toInt()}m pts=${route.points.size} " +
                "turns=${route.turns.size} firstTurnIdx=${route.turns.firstOrNull()?.trackIndex} " +
                "p0=(${"%.5f".format(route.points.first().lat)},${"%.5f".format(route.points.first().lon)}) " +
                "pN=(${"%.5f".format(route.points.last().lat)},${"%.5f".format(route.points.last().lon)})",
        )
        voice?.resetAnnouncements()
        _state.value = NavState.Navigating(
            route = route,
            segmentIndex = 0,
            distanceAlongM = 0.0,
            distanceRemainingM = cumDist.last(),
            etaSeconds = route.durationSeconds,
            nextTurn = route.turns.firstOrNull(),
            distanceToTurnM = route.turns.firstOrNull()
                ?.let { cumDist[it.trackIndex.coerceIn(cumDist.indices)] } ?: cumDist.last(),
            offRoute = false,
            destLat = destLat,
            destLon = destLon,
            destName = lastDestName,
            speedLimitKmh = speedLimitAt(route, 0),
        )
    }

    /** Posted limit for the segment the vehicle is on: the node at the
     *  end of that segment carries the way's `maxspeed`. */
    private fun speedLimitAt(route: OfflineRoute, segmentIndex: Int): Int? {
        if (route.points.isEmpty()) return null
        val idx = (segmentIndex + 1).coerceIn(route.points.indices)
        return route.points[idx].maxspeedKmh
    }

    // ── Guidance math (pure, unit-testable) ────────────────────────

    internal data class RouteSnap(
        val segmentIndex: Int,
        val alongM: Double,
        val crossTrackM: Double,
    )

    /** Nearest point on the route, searched in a window around the
     *  last known segment so a 500-point route costs ~60 segment
     *  projections per fix, not 500. Falls back to a full scan when
     *  the window misses badly (e.g. after a re-route). */
    internal fun snapToRoute(route: OfflineRoute, loc: Location, lastIndex: Int): RouteSnap {
        val windowed = snapScan(
            route, loc,
            from = (lastIndex - 5).coerceAtLeast(0),
            to = (lastIndex + 60).coerceAtMost(route.points.size - 2),
        )
        if (windowed.crossTrackM < OFF_ROUTE_M * 2) return windowed
        return snapScan(route, loc, 0, route.points.size - 2)
    }

    private fun snapScan(route: OfflineRoute, loc: Location, from: Int, to: Int): RouteSnap {
        var bestIdx = from
        var bestCross = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in from..to) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val proj = projectOnSegment(loc.latitude, loc.longitude, a, b)
            if (proj.distM < bestCross) {
                bestCross = proj.distM
                bestIdx = i
                bestAlong = cumDist[i] + proj.alongSegM
            }
        }
        return RouteSnap(bestIdx, bestAlong, bestCross)
    }

    private data class SegProj(val distM: Double, val alongSegM: Double)

    /** Project a point onto segment a→b in a local equirectangular
     *  frame — exact enough at street scale. */
    private fun projectOnSegment(lat: Double, lon: Double, a: RoutePoint, b: RoutePoint): SegProj {
        val mLat = 111_320.0
        val mLon = 111_320.0 * Math.cos(Math.toRadians(lat))
        val ax = (a.lon - lon) * mLon
        val ay = (a.lat - lat) * mLat
        val bx = (b.lon - lon) * mLon
        val by = (b.lat - lat) * mLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return SegProj(
            distM = Math.sqrt(px * px + py * py),
            alongSegM = t * Math.sqrt(len2),
        )
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

    private const val TAG = "NavCtl"
    private const val OFF_ROUTE_M = 50.0
    private const val OFF_ROUTE_CONSECUTIVE = 3
    private const val ARRIVAL_M = 40.0
    private const val FAILED_BANNER_MS = 6_000L
    /** How long a navigate intent waits for the first GNSS fix before
     *  giving up — long enough to cover a cold-start acquisition. */
    private const val ORIGIN_WAIT_MS = 30_000L
    private const val ORIGIN_POLL_MS = 250L
}
