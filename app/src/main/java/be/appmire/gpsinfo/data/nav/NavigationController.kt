package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.location.Location
import be.appmire.gpsinfo.BuildConfig
import be.appmire.gpsinfo.data.model.GnssSnapshot
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
            /** Significant en-route alternatives offered at an upcoming fork
             *  (computed periodically), with their time/distance trade-offs.
             *  Empty when nothing worthwhile diverges soon. */
            val alternatives: List<RouteAlternative> = emptyList(),
        ) : NavState

        data class Arrived(val destLat: Double, val destLon: Double) : NavState
        data class Failed(val message: String) : NavState
    }

    /**
     * An alternative route offered mid-drive at a real upcoming fork.
     * Deltas are vs. the remaining current route: negative = saving
     * (faster / shorter), positive = cost. [forkDistanceM] is how far ahead
     * the alternative diverges from the current route.
     */
    data class RouteAlternative(
        val route: OfflineRoute,
        val deltaSeconds: Int,
        val deltaMeters: Int,
        val forkDistanceM: Double,
    )

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
    private var altJob: Job? = null
    private var router: OfflineRouter? = null

    /** Online engine (server-side Valhalla): tried first for profile-aware,
     *  fast routing; [router] (BRouter) is the offline fallback. */
    private val onlineRouter: Router = ValhallaRouter()
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
        if (voice == null) voice = VoiceGuide(appContext)
        _state.value = NavState.Preparing("Computing route…")

        // 1) Online engine first (Valhalla): fast, profile-aware, no tiles.
        val online = try {
            onlineRouter.route(from.latitude, from.longitude, destLat, destLon, lastProfile)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "online routing failed", e); null
        }
        if (online != null && online.points.size >= 2) {
            installRoute(online, destLat, destLon)
            voice?.announceStart(online)
            startCorridorDownload(appContext, online)
            return
        }

        // 2) Offline fallback: BRouter — download missing rd5 tiles, then route.
        val theRouter = router ?: OfflineRouter(appContext).also { router = it }

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
     * Compute route alternatives (one per [RouteProfile]) for the chooser —
     * ONLINE only (Valhalla), in parallel, no tile download. Returns empty
     * when offline or before the first fix, so the caller falls straight
     * through to [navigateTo] (BRouter handles the offline case). Profiles
     * that collapse to the same route are deduped, so identical options
     * don't clutter the list.
     */
    suspend fun previewOptions(destLat: Double, destLon: Double): List<RouteOption> {
        val from = currentOrigin() ?: return emptyList()
        val computed = coroutineScope {
            listOf(RouteProfile.FASTEST, RouteProfile.SHORTEST, RouteProfile.ECONOMIC).map { p ->
                async {
                    runCatching {
                        onlineRouter.route(from.latitude, from.longitude, destLat, destLon, p)
                    }.getOrNull()?.takeIf { it.points.size >= 2 }?.let { RouteOption(p, it) }
                }
            }.awaitAll().filterNotNull()
        }
        // Dedup near-identical routes (rounded distance + duration), keeping
        // the first profile that produced each — FASTEST/SHORTEST/ECONOMIC.
        val seen = HashSet<Pair<Int, Int>>()
        return computed.filter { o ->
            seen.add(o.route.distanceMeters / 100 to o.route.durationSeconds / 30)
        }
    }

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
        altJob?.cancel()
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

        // Where the next maneuver is — computed before off-route handling so a
        // missed turn can reroute faster than generic drift. Clamp to the
        // distance actually left: a turn can't be farther than the destination
        // (guards a stray voice-hint index or a bad snap).
        val nextTurn = s.route.turns.firstOrNull { it.trackIndex > snap.segmentIndex }
        val distToTurn = (nextTurn?.let {
            max(0.0, cumDist[it.trackIndex.coerceIn(cumDist.indices)] - snap.alongM)
        } ?: remaining).coerceAtMost(remaining)

        // Off-route detection → silent re-route. A driver who ignores the next
        // turn is off-route *right at the maneuver*, so inside the turn zone we
        // reroute far sooner than the generic 3-fix drift debounce: immediately
        // when the miss is unambiguous (well past the corridor), otherwise
        // within ~2 fixes. Generic drift on open road keeps the 3-fix debounce
        // so GPS noise doesn't trigger spurious recalculations. Every reroute
        // recomputes from the CURRENT position, so the engine picks the new
        // best path itself — a safe U-turn or an alternative route.
        if (snap.crossTrackM > OFF_ROUTE_M) {
            offRouteCount++
            val nearTurn = distToTurn < MISSED_TURN_ZONE_M
            val needed = when {
                nearTurn && snap.crossTrackM > OFF_ROUTE_M * 2 -> 1
                nearTurn -> MISSED_TURN_CONSECUTIVE
                else -> OFF_ROUTE_CONSECUTIVE
            }
            if (offRouteCount >= needed && !reRouting) {
                reRouting = true
                val ctxRouter = router
                scope.launch {
                    // Online reroute first (fast, profile-aware); BRouter if offline.
                    val fresh = onlineRouter.route(
                        loc.latitude, loc.longitude, s.destLat, s.destLon, lastProfile,
                    ) ?: ctxRouter?.route(
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
        } else {
            offRouteCount = 0
        }

        // ETA scales the route's own pace over what's left.
        val pace = if (s.route.distanceMeters > 0)
            s.route.durationSeconds.toDouble() / s.route.distanceMeters else 0.0

        if (BuildConfig.DEBUG) android.util.Log.d(
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
        if (BuildConfig.DEBUG) android.util.Log.d(
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
        startAlternativesLoop(destLat, destLon)
    }

    /** Switch to a suggested alternative (user accepted the fork). */
    @Synchronized
    fun acceptAlternative(alt: RouteAlternative) {
        val s = _state.value as? NavState.Navigating ?: return
        installRoute(alt.route, s.destLat, s.destLon)
        voice?.announceReroute()
    }

    /**
     * Periodically (while navigating) compute route alternatives from the
     * current position and keep only those that diverge at a real upcoming
     * fork with a worthwhile time/distance trade-off. Online only (Valhalla);
     * a fresh [installRoute] restarts the loop with the new destination.
     */
    private fun startAlternativesLoop(destLat: Double, destLon: Double) {
        altJob?.cancel()
        if (BuildConfig.ROUTING_BASE_URL.isEmpty()) return
        val valhalla = onlineRouter as? ValhallaRouter ?: return
        altJob = scope.launch {
            while (isActive) {
                delay(ALT_INTERVAL_MS)
                val s = _state.value as? NavState.Navigating ?: break
                if (reRouting) continue
                val loc = lastLocation ?: continue
                val alts = runCatching {
                    valhalla.routeAlternatives(loc.latitude, loc.longitude, destLat, destLon, lastProfile, 2)
                }.getOrDefault(emptyList())
                val ranked = rankAlternatives(alts, s)
                synchronized(this@NavigationController) {
                    val cur = _state.value as? NavState.Navigating ?: return@synchronized
                    if (cur.alternatives != ranked) _state.value = cur.copy(alternatives = ranked)
                }
            }
        }
    }

    /** Keep alternatives that diverge at a near fork and save time or
     *  distance vs. the remaining current route (better on ≥1 axis). */
    private fun rankAlternatives(alts: List<OfflineRoute>, s: NavState.Navigating): List<RouteAlternative> {
        if (alts.isEmpty()) return emptyList()
        val from = s.segmentIndex.coerceIn(0, (s.route.points.size - 1).coerceAtLeast(0))
        val currentAhead = s.route.points.subList(from, s.route.points.size)
        val out = ArrayList<RouteAlternative>()
        for (alt in alts) {
            if (alt.points.size < 2) continue
            val fork = forkDistance(alt.points, currentAhead) ?: continue
            if (fork < FORK_MIN_M || fork > FORK_HORIZON_M) continue
            val dSec = alt.durationSeconds - s.etaSeconds
            val dM = (alt.distanceMeters - s.distanceRemainingM).toInt()
            val faster = dSec <= -MIN_TIME_SAVE_S
            val shorter = dM <= -MIN_DIST_SAVE_M
            if (!faster && !shorter) continue
            out.add(RouteAlternative(alt, dSec, dM, fork))
        }
        return out.sortedBy { it.deltaSeconds }.take(MAX_ALTERNATIVES)
    }

    /** Distance along [altPts] to where it first leaves [current] (the fork),
     *  or null if it stays on the current route through the scan window
     *  (i.e. it's the same path, not a distinct alternative). */
    private fun forkDistance(altPts: List<RoutePoint>, current: List<RoutePoint>): Double? {
        if (altPts.size < 2 || current.isEmpty()) return null
        var cum = 0.0
        val limit = minOf(altPts.size, ALT_SCAN_PTS)
        for (i in 1 until limit) {
            cum += haversineM(altPts[i - 1], altPts[i])
            if (nearestDistM(altPts[i], current) > SAME_PATH_M) return cum
        }
        return null
    }

    private fun nearestDistM(p: RoutePoint, poly: List<RoutePoint>): Double {
        var best = Double.MAX_VALUE
        val limit = minOf(poly.size, CUR_SCAN_PTS)
        for (i in 0 until limit) {
            val d = haversineM(p, poly[i])
            if (d < best) best = d
        }
        return best
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
        val n = route.points.size
        val windowed = snapScan(
            route, loc,
            from = (lastIndex - SNAP_WINDOW_BACK).coerceAtLeast(0),
            to = (lastIndex + SNAP_WINDOW_AHEAD).coerceAtMost(n - 2),
        )
        // Solidly within the local corridor — trust it.
        if (windowed.crossTrackM < OFF_ROUTE_M * 2) return windowed
        // Off the local corridor. A naive global-nearest scan here is what
        // produced the "130 km straight" bug: on a long route that passes
        // near itself (parallel carriageways, overpasses, returning to the
        // same town) the closest segment by lat/lon can be hundreds of points
        // ahead — teleporting segmentIndex past every real turn so only the
        // final "arrive" maneuver is left, shown as "straight" for the whole
        // remaining distance. Only ADOPT a global re-snap when it's
        // unambiguous: genuinely on that segment (small cross-track) AND not
        // an implausible jump in route distance from where we were (no vehicle
        // covers > a km-ish between ~1 Hz fixes).
        val global = snapScan(route, loc, 0, n - 2)
        if (cumDist.isEmpty()) return windowed
        val li = lastIndex.coerceIn(cumDist.indices)
        val gi = global.segmentIndex.coerceIn(cumDist.indices)
        val jumpM = cumDist[gi] - cumDist[li]
        val plausible = global.crossTrackM <= OFF_ROUTE_M &&
            jumpM <= MAX_RESNAP_FORWARD_M && jumpM >= -MAX_RESNAP_BACK_M
        // When the global match isn't a trustworthy re-snap, keep the local
        // (off-route) result so the off-route counter triggers a real reroute
        // instead of the maneuver logic silently teleporting down the route.
        return if (plausible && global.crossTrackM < windowed.crossTrackM) global else windowed
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
    /** Within this distance of the next maneuver, an off-route reading is
     *  treated as a missed turn and rerouted on the fast path. */
    private const val MISSED_TURN_ZONE_M = 60.0
    /** Consecutive off-route fixes that trigger a reroute inside the turn
     *  zone (vs [OFF_ROUTE_CONSECUTIVE] for generic open-road drift). */
    private const val MISSED_TURN_CONSECUTIVE = 2
    // ── En-route alternatives ("fork in the road") ──
    private const val ALT_INTERVAL_MS = 60_000L
    /** A fork must be at least this far ahead (not on top of us) and no
     *  farther than the horizon (still actionable / relevant). */
    private const val FORK_MIN_M = 150.0
    private const val FORK_HORIZON_M = 6_000.0
    /** Minimum saving to bother offering an alternative (better on ≥1 axis). */
    private const val MIN_TIME_SAVE_S = 60
    private const val MIN_DIST_SAVE_M = 1_000
    private const val MAX_ALTERNATIVES = 2
    /** Points within this of the current route count as "same road". */
    private const val SAME_PATH_M = 30.0
    private const val ALT_SCAN_PTS = 120
    private const val CUR_SCAN_PTS = 250
    /** Local snap search window (points) around the last matched index. */
    private const val SNAP_WINDOW_BACK = 5
    private const val SNAP_WINDOW_AHEAD = 60
    /** Max plausible forward re-snap (m) when off the local corridor; beyond
     *  this a "nearest" global match is a route self-proximity lookalike, not
     *  real progress, so it's rejected to avoid teleporting past real turns. */
    private const val MAX_RESNAP_FORWARD_M = 1500.0
    private const val MAX_RESNAP_BACK_M = 300.0
    private const val ARRIVAL_M = 40.0
    private const val FAILED_BANNER_MS = 6_000L
    /** How long a navigate intent waits for the first GNSS fix before
     *  giving up — long enough to cover a cold-start acquisition. */
    private const val ORIGIN_WAIT_MS = 30_000L
    private const val ORIGIN_POLL_MS = 250L
}
