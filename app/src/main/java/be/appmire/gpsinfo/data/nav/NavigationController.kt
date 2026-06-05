package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.location.Location
import be.appmire.gpsinfo.data.model.GnssSnapshot
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        ) : NavState

        data class Arrived(val destLat: Double, val destLon: Double) : NavState
        data class Failed(val message: String) : NavState
    }

    private val _state = MutableStateFlow<NavState>(NavState.Idle)
    val state: StateFlow<NavState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var routeJob: Job? = null
    private var router: OfflineRouter? = null
    private var voice: VoiceGuide? = null

    /** Cumulative metres at each route point — guidance lookup table. */
    private var cumDist = DoubleArray(0)
    private var lastFixElapsedNanos = 0L
    private var offRouteCount = 0
    private var reRouting = false
    private var lastLocation: Location? = null

    /** Start navigating from the current position to ([destLat],
     *  [destLon]). Downloads missing segment tiles first. */
    fun navigateTo(context: Context, destLat: Double, destLon: Double) {
        val appContext = context.applicationContext
        val from = lastLocation
        if (from == null) {
            failTransient("No GPS fix yet")
            return
        }
        routeJob?.cancel()
        routeJob = scope.launch {
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
                                _state.value = NavState.Preparing("$tile $pct%")
                            }
                            is RoutingDataRepository.DownloadState.Failed -> {
                                android.util.Log.w(TAG, "tile download failed: ${dl.tile} ${dl.message}")
                                failTransient("Tile ${dl.tile}: ${dl.message}")
                            }
                            is RoutingDataRepository.DownloadState.Done -> Unit
                        }
                    }
                    if (_state.value is NavState.Failed) return@launch
                }
            }

            _state.value = NavState.Preparing("Computing route…")
            val route = try {
                theRouter.route(from.latitude, from.longitude, destLat, destLon)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "routing failed", e)
                null
            }
            if (route == null || route.points.size < 2) {
                failTransient("No route found")
                return@launch
            }
            installRoute(route, destLat, destLon)
            voice?.announceStart(route)
        }
    }

    @Synchronized
    fun stop() {
        routeJob?.cancel()
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
                            loc.latitude, loc.longitude, s.destLat, s.destLon,
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
        val distToTurn = nextTurn?.let {
            max(0.0, cumDist[it.trackIndex.coerceIn(cumDist.indices)] - snap.alongM)
        } ?: remaining
        // ETA scales the route's own pace over what's left.
        val pace = if (s.route.distanceMeters > 0)
            s.route.durationSeconds.toDouble() / s.route.distanceMeters else 0.0

        _state.value = s.copy(
            segmentIndex = snap.segmentIndex,
            distanceAlongM = snap.alongM,
            distanceRemainingM = remaining,
            etaSeconds = (remaining * pace).toInt(),
            nextTurn = nextTurn,
            distanceToTurnM = distToTurn,
            offRoute = snap.crossTrackM > OFF_ROUTE_M,
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
        )
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
}
