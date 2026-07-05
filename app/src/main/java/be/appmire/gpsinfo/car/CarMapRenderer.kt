package be.appmire.gpsinfo.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.model.GForceSample
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.rally.RallyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min

/**
 * Draws the Waze-style car map: OSM tile base layer, the in-flight
 * trail breadcrumb, a position marker, and a HUD cluster (speed bubble
 * + bottom info strip) — all straight onto the Android Auto video
 * surface that navigation-category apps get via [AppManager].
 *
 * Camera model:
 *   - **2.5D tilt** (default on, toggleable): the flat map layer is
 *     rendered into an oversized offscreen bitmap, then drawn through
 *     a [Camera].rotateX perspective matrix pivoted on the position
 *     anchor — near field magnified, far field receding to a hazed
 *     horizon. Pure raster trick: no vector tiles, no GL.
 *   - **Speed-adaptive zoom**: a continuous (fractional) zoom level
 *     glides between [ZOOM_STANDSTILL] and [ZOOM_FAST] as ground speed
 *     rises — zoomed in while crawling, wide while cruising. The
 *     +/- buttons and pinch gestures nudge a persistent manual bias
 *     on top of the automatic level rather than fighting it.
 *     Fractional zoom renders integer-zoom tiles scaled by
 *     2^frac (1..2), so there's never a level-snap.
 *
 * Rendering model: the screen feeds every GNSS/recording emission into
 * [update]; we redraw at most once per main-loop pass (renders are
 * coalesced through [scheduleRender]) which caps us at the 1 Hz GNSS
 * cadence plus tile-arrival repaints. No animation loop — the car
 * screen is glanceable, not a game.
 *
 * Tiles come from the same osmdroid source + on-disk cache as the
 * phone's live map ([MapnikBulkOk] — see TrailMapScreen.kt), so areas
 * the user has browsed on the phone work offline in the car. The
 * provider downloads misses asynchronously and pings
 * [tileArrivedHandler], which repaints.
 *
 * Heading-up rotation uses GPS course-over-ground (never the
 * magnetometer — see the phone dashboard for why) and engages above a
 * walking pace, holding the last rotation while stationary so the map
 * doesn't snap back to north at every red light.
 */
class CarMapRenderer(
    private val carContext: CarContext,
    sessionLifecycle: Lifecycle,
) : DefaultLifecycleObserver, SurfaceCallback {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var surfaceContainer: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    private var snapshot: GnssSnapshot = GnssSnapshot()
    private var recording: RecordingState = RecordingState.Idle
    private var rally: RallyState = RallyState.Idle
    private var lastDrawnLocation: Location? = null

    /** Breadcrumb of the active recording as lat/lon pairs. Appended
     *  on a >3 m move, decimated 2:1 above [BREADCRUMB_CAP] so a long
     *  drive can't grow the per-frame path cost without bound. */
    private val breadcrumb = ArrayList<DoubleArray>()
    private var breadcrumbMinStepM = 3f
    private var wasRecording = false

    /** Continuous zoom level actually rendered; glides toward the
     *  speed-derived target a step per GNSS tick. */
    private var currentZoom = ZOOM_STANDSTILL
    /** Frame-eased zoom the camera actually uses, so zoom changes glide
     *  instead of snapping between levels. */
    private var smoothedZoom = ZOOM_STANDSTILL
    /** Manual nudge from buttons/pinch on top of the automatic level. */
    private var zoomBias = 0.0
    /** Map view mode, cycled from the map action strip's tilt button:
     *  flat top-down → 2.5D with flat buildings → 2.5D with 3D buildings. */
    private var viewMode = MapViewMode.TILTED_FLAT
    /** Tilt is on for both 2.5D modes; used by the camera pitch and the
     *  ground-plane marker foreshortening. */
    private val tilted get() = viewMode != MapViewMode.FLAT

    /** Smoothed course-over-ground driving the heading-up rotation. */
    private var smoothedBearingDeg = 0f
    private var hasBearing = false
    /** Accumulated pinch factor from [onScale]; folded into [zoomBias]. */
    private var pinchAccumulator = 1f

    /** Pan mode (host's Action.PAN toggle). While active, [onScroll]
     *  drags a free camera around; exiting snaps back to follow. The
     *  offset lives in degrees so it survives zoom changes. */
    private var panMode = false
    private var panLatOffset = 0.0
    private var panLonOffset = 0.0

    private var renderPending = false

    private val instruments = CarInstruments()

    /** MapLibre vector-map snapshotter — renders the base map (with
     *  native bearing + pitch) off-screen to a bitmap we blit onto the
     *  car surface. A new snapshot redraws the frame via [scheduleRender]. */
    private val snapshotter = CarMapSnapshotter(carContext) { scheduleRender() }

    // ── Live-GL backend (opt-in, off by default) ────────────────────
    // When enabled, MapLibre renders the base map straight onto the AA
    // surface via EGL (org.maplibre.android.maps.CarGlMap); overlays are
    // drawn to an off-screen bitmap and composited over each frame. The
    // snapshotter above stays the fallback whenever this is off.
    private var glEnabled = false
    private var glMap: org.maplibre.android.maps.CarGlMap? = null
    /** True while a frame is being drawn for the GL backend, so [drawFrame]
     *  skips the opaque background fill and [drawVectorMap] skips the
     *  bitmap blit — the overlay canvas must stay transparent over the map. */
    private var glMode = false
    /** Two overlay bitmaps ping-ponged so the render thread can read the
     *  last frame while the main thread draws the next. */
    private val overlayBuffers = arrayOfNulls<android.graphics.Bitmap>(2)
    private var overlayBufIdx = 0

    /** Toggle the live-GL backend (from the settings flow). */
    fun updateLiveGlMap(enabled: Boolean) {
        if (enabled == glEnabled) return
        glEnabled = enabled
        if (enabled) ensureGlBound() else teardownGl()
        scheduleRender()
    }

    /** Create + bind the GL map to the current surface if we have one. */
    private fun ensureGlBound() {
        if (!glEnabled) return
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return
        val gl = glMap ?: org.maplibre.android.maps.CarGlMap(
            carContext,
            be.appmire.gpsinfo.data.nav.MapLibreStyle.LIBERTY,
            onStyleReady = { mainHandler.post { scheduleRender() } },
            onNeedRepaint = { mainHandler.post { scheduleRender() } },
        ).also { glMap = it }
        gl.onSurfaceAvailable(surface, w, h)
    }

    private fun teardownGl() {
        glMode = false
        glMap?.destroy()
        glMap = null
    }

    /** Instantaneous drive power in kW for the energy meter — null
     *  until an OBD2 source feeds [updatePower]; the dial parks at 0
     *  with a dimmed readout meanwhile. */
    private var powerKw: Double? = null

    fun updatePower(kw: Double?) {
        powerKw = kw
        if (kw != null) {
            val now = SystemClock.elapsedRealtime()
            peakBuf.addLast(doubleArrayOf(now.toDouble(), kw))
            while (peakBuf.isNotEmpty() && peakBuf.first()[0] < now - PEAK_WINDOW_MS) peakBuf.removeFirst()
        }
        scheduleRender()
    }

    /** 30 s rolling power-peak window [elapsedMs, kw] (peak-hold telltale) and a
     *  2 s efficiency window [elapsedMs, kw, mps] for the kWh/100km readout. */
    private val peakBuf = ArrayDeque<DoubleArray>()
    private val effBuf = ArrayDeque<DoubleArray>()
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Highest power in the last 30 s, or the live value if the window is empty. */
    private fun currentPeakKw(): Double {
        val now = SystemClock.elapsedRealtime()
        while (peakBuf.isNotEmpty() && peakBuf.first()[0] < now - PEAK_WINDOW_MS) peakBuf.removeFirst()
        return peakBuf.maxOfOrNull { it[1] } ?: dispPowerKw
    }

    /** Instant efficiency (kWh/100km) from the 2 s mean of power & speed — energy
     *  over distance. Null below walking pace, where it would blow up. */
    private fun currentConsumption(): Double? {
        val now = SystemClock.elapsedRealtime()
        while (effBuf.isNotEmpty() && effBuf.first()[0] < now - EFF_WINDOW_MS) effBuf.removeFirst()
        if (effBuf.isEmpty()) return null
        val ak = effBuf.sumOf { it[1] } / effBuf.size
        val kmh = (effBuf.sumOf { it[2] } / effBuf.size) * 3.6
        return if (kmh > 3.0) ak / kmh * 100.0 else null
    }

    /** Whether a live OBD2 adapter is currently connected. The power /
     *  energy dial is the EV-dashboard half of the cluster, so it only
     *  earns its top-left slot when there's a real feed behind it;
     *  otherwise that corner is left to the map. */
    private var obdConnected = false

    fun updateObdConnected(connected: Boolean) {
        if (connected == obdConnected) return
        obdConnected = connected
        scheduleRender()
    }

    /** Distance driven this projection session (metres), accumulated
     *  from successive fixes — the trip odometer shown on the speed
     *  dial. Jitter (<1 m) and teleports (>200 m between fixes) are
     *  excluded so it tracks real travel. */
    private var odometerM = 0.0

    /** Battery state of charge (0..100) and range remaining (km) for the
     *  energy meter, or null when no source (Car API / OBD2) provides
     *  them — the readouts then show a dash. */
    private var batterySocPct: Float? = null
    private var rangeRemainingKm: Float? = null

    fun updateEnergy(socPercent: Float?, rangeKm: Float?) {
        batterySocPct = socPercent
        rangeRemainingKm = rangeKm
        scheduleRender()
    }

    /** Remaining distance to the destination (km) while navigating, used
     *  to estimate range + SOC on arrival. Null when not navigating. */
    private var navRemainingKm: Double? = null

    fun updateNavProgress(remainingMeters: Double?) {
        navRemainingKm = remainingMeters?.div(1000.0)
        scheduleRender()
    }

    /** Posted speed limit (km/h) for the road being driven, or null —
     *  shown as a road-sign badge on the map. */
    private var speedLimitKmh: Int? = null

    fun updateSpeedLimit(kmh: Int?) {
        if (kmh == speedLimitKmh) return
        speedLimitKmh = kmh
        scheduleRender()
    }

    /** Outside (ambient) air temperature in °C from OBD, or null — shown
     *  as a small badge top-left of the map. */
    private var ambientTempC: Double? = null

    fun updateAmbientTemp(celsius: Double?) {
        if (celsius == ambientTempC) return
        ambientTempC = celsius
        scheduleRender()
    }

    /** Latest G-force sample + a short fading trail for the G-meter
     *  dial (bottom-right corner). Fed from the phone's sensor stream,
     *  sampled down for the car so renders stay glanceable. */
    private var gForce: GForceSample = GForceSample(0f, 0f, 0f)
    private val gForceTrail = ArrayDeque<GForceSample>()

    fun updateGForce(sample: GForceSample) {
        gForce = sample
        if (gForceTrail.lastOrNull() != sample) {
            gForceTrail.addLast(sample)
            while (gForceTrail.size > GFORCE_TRAIL) gForceTrail.removeFirst()
        }
        scheduleRender()
    }

    /** Active navigation route as packed (lat, lon) pairs, or null
     *  when not navigating — projected onto the vector map per frame. */
    private var navRoute: List<DoubleArray>? = null

    fun updateNavigationRoute(points: List<be.appmire.gpsinfo.data.nav.RoutePoint>?) {
        navRoute = points?.map { doubleArrayOf(it.lat, it.lon) }
        scheduleRender()
    }

    /** One-line navigation status (route computing, failure reason) —
     *  without it those phases are invisible and navigation looks like
     *  it silently did nothing. */
    private var navStatusText: String? = null

    fun updateNavigationStatus(text: String?) {
        navStatusText = text
        scheduleRender()
    }

    /** Live traffic incidents (TrafficController), projected onto the map as
     *  coloured lines so jams/closures/roadworks read against the route. */
    private var traffic: List<be.appmire.gpsinfo.data.nav.TrafficIncident> = emptyList()

    fun updateTraffic(incidents: List<be.appmire.gpsinfo.data.nav.TrafficIncident>) {
        traffic = incidents
        scheduleRender()
    }

    /** En-route alternatives (NavigationController) — drawn dimmed beneath the
     *  route, with the best one's trade-off in a banner. */
    private var alternatives: List<be.appmire.gpsinfo.data.nav.NavigationController.RouteAlternative> = emptyList()

    fun updateAlternatives(alts: List<be.appmire.gpsinfo.data.nav.NavigationController.RouteAlternative>) {
        alternatives = alts
        scheduleRender()
    }

    /** Which optional overlays to draw on top of the navigation map. Starts
     *  at the navigation-only baseline (only the speed + speed-limit badge);
     *  the screen feeds the user's choices via [updateOverlayConfig]. */
    private var overlayConfig: CarOverlayConfig = CarOverlayConfig()

    fun updateOverlayConfig(config: CarOverlayConfig) {
        if (config == overlayConfig) return
        overlayConfig = config
        scheduleRender()
    }

    // ── Dynamic overlay layout (drag / pinch, per nav state) ─────────
    /** User drag/scale overrides for the CURRENT state, pushed by the screen
     *  (which selects the nav vs idle bucket). */
    private var overlayLayout: Map<OverlayElement, LayoutOverride> = emptyMap()
    private var overlayState: OverlayState = OverlayState.IDLE
    /** Parked-only "edit layout" mode: gestures reposition/resize the
     *  selected overlay instead of panning/zooming the map. */
    private var editMode = false
    private var selectedElement: OverlayElement? = null
    /** Post-transform bounds of each overlay drawn this frame, for hit-testing
     *  taps in edit mode. */
    private val elementBounds = HashMap<OverlayElement, RectF>()
    private val dirtyElements = HashSet<OverlayElement>()
    private var surfaceW = 0
    private var surfaceH = 0

    /** Persist callback — the screen writes the override to DataStore in the
     *  right state bucket. Set by the owner. */
    var onLayoutChanged: ((OverlayState, OverlayElement, LayoutOverride) -> Unit)? = null

    /** Reset callback — the screen clears the whole state bucket in DataStore. */
    var onLayoutReset: ((OverlayState) -> Unit)? = null

    fun updateOverlayLayout(state: OverlayState, layout: Map<OverlayElement, LayoutOverride>) {
        // Don't let a persisted value flowing back clobber a live drag.
        if (editMode) return
        if (state == overlayState && layout == overlayLayout) return
        overlayState = state
        overlayLayout = layout
        scheduleRender()
    }

    fun setEditMode(active: Boolean) {
        if (active == editMode) return
        editMode = active
        if (!active) {
            // Persist everything touched this session.
            val cb = onLayoutChanged
            if (cb != null) {
                for (el in dirtyElements) cb(overlayState, el, overlayLayout[el] ?: LayoutOverride())
            }
            dirtyElements.clear()
            selectedElement = null
        }
        scheduleRender()
    }

    fun isEditMode(): Boolean = editMode

    /** Reset the current state's layout to defaults (clears all overrides for
     *  the active NAV/IDLE preset). Only meaningful in edit mode. */
    fun resetLayout() {
        overlayLayout = emptyMap()
        dirtyElements.clear()
        selectedElement = null
        scheduleRender()
        onLayoutReset?.invoke(overlayState)
    }

    /** Apply [transform] to the selected element's override and repaint. */
    private fun mutateSelected(transform: (LayoutOverride) -> LayoutOverride) {
        val el = selectedElement ?: return
        val next = transform(overlayLayout[el] ?: LayoutOverride())
        overlayLayout = overlayLayout.toMutableMap().apply { put(el, next) }
        dirtyElements.add(el)
        scheduleRender()
    }

    init {
        sessionLifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(frameRunnable)
        snapshotter.destroy()
        teardownGl()
    }

    // ── Smooth animation loop ──────────────────────────────────────
    // GNSS arrives ~1 Hz; a modest frame loop dead-reckons the vehicle
    // forward between fixes and eases the gauge needles so the surface
    // glides instead of stepping once a second. It runs ONLY while the
    // car is moving (or a value is still easing) and idles otherwise —
    // a free-running loop re-snapshots MapLibre every frame and the host
    // kills slow apps. [FRAME_MS] is the tunable cadence.
    private var animating = false
    private val frameRunnable = Runnable { onAnimationFrame() }
    private var targetBearingDeg = 0f
    /** Eased gauge scalars, toward the latest fix / OBD values. */
    private var dispSpeedMps = 0f
    private var dispPowerKw = 0.0

    /** Frame cadence: the GL backend renders on the GPU so it runs at ~30 fps;
     *  the snapshotter stays coarse (each frame re-snapshots MapLibre). */
    private fun frameDelayMs(): Long = if (glEnabled) GL_FRAME_MS else FRAME_MS

    private fun ensureAnimating() {
        if (animating) return
        animating = true
        mainHandler.postDelayed(frameRunnable, frameDelayMs())
    }

    private fun onAnimationFrame() {
        val more = stepAnimation()
        renderFrame()
        if (more) mainHandler.postDelayed(frameRunnable, frameDelayMs()) else animating = false
    }

    /** Advance the eased values one frame; return whether more animation
     *  is pending (still moving, or a needle hasn't settled). */
    private fun stepAnimation(): Boolean {
        val loc = lastDrawnLocation
        val targetMps = if (loc != null && loc.hasSpeed()) loc.speed else 0f
        dispSpeedMps += (targetMps - dispSpeedMps) * EASE
        val targetKw = powerKw ?: 0.0
        dispPowerKw += (targetKw - dispPowerKw) * EASE
        if (hasBearing) {
            var d = targetBearingDeg - smoothedBearingDeg
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            smoothedBearingDeg = (smoothedBearingDeg + d * EASE + 360f) % 360f
        }
        // Ease the map zoom toward its target so auto-zoom steps and pinches
        // glide instead of snapping between levels (the camera reads
        // smoothedZoom, not currentZoom).
        smoothedZoom += (currentZoom - smoothedZoom) * ZOOM_EASE
        // Feed the 2 s efficiency window from the eased (displayed) values.
        val now = SystemClock.elapsedRealtime()
        effBuf.addLast(doubleArrayOf(now.toDouble(), dispPowerKw, dispSpeedMps.toDouble()))
        while (effBuf.isNotEmpty() && effBuf.first()[0] < now - EFF_WINDOW_MS) effBuf.removeFirst()
        val moving = targetMps > MIN_MOVE_MPS
        val settling = abs(targetMps - dispSpeedMps) > 0.05f || abs(targetKw - dispPowerKw) > 0.5 ||
            abs(currentZoom - smoothedZoom) > 0.005
        return moving || settling
    }

    /** The vehicle position to draw THIS frame: the last fix dead-reckoned
     *  forward along its course by the time elapsed since the fix, so the
     *  map glides between ~1 Hz fixes. Carries the eased speed for the
     *  needle. Extrapolation is capped so a dropped fix can't fling the
     *  marker down the road. */
    private fun displayedLocation(): Location? {
        val fix = lastDrawnLocation ?: return null
        val out = Location(fix)
        out.speed = dispSpeedMps
        if (!fix.hasSpeed() || fix.speed < MIN_MOVE_MPS || !fix.hasBearing()) return out
        val dt = ((SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1e9)
            .coerceIn(0.0, MAX_EXTRAPOLATE_SEC)
        val dist = fix.speed * dt
        val br = Math.toRadians(fix.bearing.toDouble())
        out.latitude = fix.latitude + dist * cos(br) / 111_320.0
        out.longitude = fix.longitude +
            dist * Math.sin(br) / (111_320.0 * cos(Math.toRadians(fix.latitude)))
        return out
    }

    // ── Data in ────────────────────────────────────────────────────

    /** Feed the latest GNSS + recording + rally state; repaints. */
    fun update(gnss: GnssSnapshot, rec: RecordingState, rallyState: RallyState = RallyState.Idle) {
        snapshot = gnss
        recording = rec
        rally = rallyState

        val isRecording = rec is RecordingState.Recording
        if (isRecording && !wasRecording) {
            breadcrumb.clear()
            breadcrumbMinStepM = 3f
        }
        wasRecording = isRecording

        val prev = lastDrawnLocation
        val loc = gnss.location?.let { withDerivedMotion(it, prev) }
        if (loc != null) {
            // Trip odometer: real travel only — ignore sub-metre jitter
            // and provider/mock teleports (no road car jumps >200 m
            // between ~1 Hz fixes).
            if (prev != null && loc !== prev) {
                val moved = prev.distanceTo(loc)
                if (moved in 1f..200f) odometerM += moved
            }
            lastDrawnLocation = loc
            // Course-over-ground target for the heading-up rotation; the
            // frame loop eases [smoothedBearingDeg] toward it between fixes.
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > MIN_HEADING_UP_SPEED_MPS) {
                targetBearingDeg = loc.bearing
                hasBearing = true
            }
            if (isRecording) appendBreadcrumb(loc)
        }
        stepAutoZoom(loc)
        // Glide the map/needles between this fix and the next.
        ensureAnimating()
        scheduleRender()
    }

    /** Synthesize speed + course from successive fixes when the chip
     *  reports (near-)standstill while the position clearly moved.
     *  Real GNSS chips deliver Doppler speed, but the emulator's NMEA
     *  injection and some BT GPS mice report vel=0 on moving fixes —
     *  without this the speed bubble, speed-adaptive zoom and
     *  heading-up rotation all stay dormant on those sources. */
    private fun withDerivedMotion(loc: Location, prev: Location?): Location {
        if (prev == null) return loc
        // The repository re-emits the same fix on satellite-status
        // ticks; keep the already-synthesized version instead of
        // letting the raw re-emission clobber its derived motion.
        if (loc.elapsedRealtimeNanos <= prev.elapsedRealtimeNanos) return prev
        val dtSec = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
        if (dtSec < 0.2 || dtSec > 10.0) return loc
        val derived = (prev.distanceTo(loc) / dtSec).toFloat()
        // Trust the chip whenever it claims real motion itself, and
        // never synthesize from a teleport (mock-location jumps,
        // provider switches) — no road car does 90 m/s.
        if (derived <= 1f || derived > 90f || (loc.hasSpeed() && loc.speed > 0.5f)) return loc
        return Location(loc).apply {
            speed = derived
            bearing = (prev.bearingTo(loc) + 360f) % 360f
        }
    }

    /** Glide the rendered zoom toward the speed target. One bounded
     *  step per GNSS tick (~1 Hz) ≈ a smooth two-to-three-second ease
     *  across the whole speed range. */
    private fun stepAutoZoom(loc: Location?) {
        val kmh = if (loc != null && loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val t = (kmh.coerceIn(0.0, ZOOM_FAST_KMH) / ZOOM_FAST_KMH)
        val target = (ZOOM_STANDSTILL - (ZOOM_STANDSTILL - ZOOM_FAST) * t + zoomBias)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
        val delta = (target - currentZoom).coerceIn(-ZOOM_STEP_PER_TICK, ZOOM_STEP_PER_TICK)
        currentZoom += delta
    }

    fun zoomIn() = nudgeZoom(+0.5)

    fun zoomOut() = nudgeZoom(-0.5)

    /** Cycle flat → 2.5D flat buildings → 2.5D 3D buildings → flat. */
    fun cycleViewMode() {
        viewMode = when (viewMode) {
            MapViewMode.FLAT -> MapViewMode.TILTED_FLAT
            MapViewMode.TILTED_FLAT -> MapViewMode.TILTED_3D
            MapViewMode.TILTED_3D -> MapViewMode.FLAT
        }
        scheduleRender()
    }

    /** Host entered/left pan mode (Action.PAN). Leaving recentres. */
    fun setPanMode(active: Boolean) {
        mainHandler.post {
            panMode = active
            if (!active) {
                panLatOffset = 0.0
                panLonOffset = 0.0
            }
            scheduleRender()
        }
    }

    /** Repaint with current state — e.g. after a day/night flip. */
    fun repaint() = scheduleRender()

    private fun nudgeZoom(delta: Double) {
        zoomBias = (zoomBias + delta).coerceIn(-ZOOM_BIAS_RANGE, ZOOM_BIAS_RANGE)
        // Apply immediately rather than waiting for the next GNSS tick.
        currentZoom = (currentZoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Run the frame loop so smoothedZoom eases to the new level (glide,
        // not snap) even when parked.
        ensureAnimating()
        scheduleRender()
    }

    private fun appendBreadcrumb(loc: Location) {
        val last = breadcrumb.lastOrNull()
        if (last != null) {
            val out = FloatArray(1)
            Location.distanceBetween(last[0], last[1], loc.latitude, loc.longitude, out)
            if (out[0] < breadcrumbMinStepM) return
        }
        breadcrumb.add(doubleArrayOf(loc.latitude, loc.longitude))
        if (breadcrumb.size > BREADCRUMB_CAP) {
            // Halve resolution, double the accept threshold. Repeats
            // as the trail keeps growing — classic geometric decimation.
            val kept = ArrayList<DoubleArray>(BREADCRUMB_CAP / 2 + 1)
            for (i in breadcrumb.indices step 2) kept.add(breadcrumb[i])
            breadcrumb.clear()
            breadcrumb.addAll(kept)
            breadcrumbMinStepM *= 2f
        }
    }

    // ── SurfaceCallback ────────────────────────────────────────────

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        mainHandler.post {
            this.surfaceContainer = surfaceContainer
            if (glEnabled) ensureGlBound()
            scheduleRender()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mainHandler.post {
            glMap?.onSurfaceDestroyed()
            this.surfaceContainer = null
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        mainHandler.post {
            this.visibleArea = visibleArea
            scheduleRender()
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        mainHandler.post {
            this.stableArea = stableArea
            scheduleRender()
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        mainHandler.post {
            // Edit mode: pinch resizes the selected overlay instead of the map.
            if (editMode && selectedElement != null) {
                mutateSelected { it.copy(scale = (it.scale * scaleFactor).coerceIn(MIN_OVERLAY_SCALE, MAX_OVERLAY_SCALE)) }
                return@post
            }
            pinchAccumulator *= scaleFactor
            if (pinchAccumulator > SCALE_STEP || pinchAccumulator < 1f / SCALE_STEP) {
                nudgeZoom(log2(pinchAccumulator.toDouble()))
                pinchAccumulator = 1f
            }
        }
    }

    /** Taps select an overlay to edit (edit mode only). */
    override fun onClick(x: Float, y: Float) {
        mainHandler.post {
            if (!editMode) return@post
            // Smallest box containing the tap wins, so a small badge layered
            // over the big cluster is still selectable.
            selectedElement = elementBounds.entries
                .filter { it.value.contains(x, y) }
                .minByOrNull { it.value.width() * it.value.height() }
                ?.key
            scheduleRender()
        }
    }

    /** Drag in pan mode. Convert the screen-space delta into a lat/lon
     *  shift using the latest snapshot's own projection — two unprojects
     *  (centre, and centre+delta) give the geographic offset for this
     *  drag, accounting for MapLibre's zoom, bearing and pitch exactly. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            // Edit mode: drag repositions the selected overlay. distanceX/Y are
            // (old - new), so negate to make the overlay follow the finger.
            if (editMode && selectedElement != null) {
                val w = surfaceW.coerceAtLeast(1)
                val h = surfaceH.coerceAtLeast(1)
                mutateSelected {
                    it.copy(
                        dx = (it.dx - distanceX / w).coerceIn(-MAX_OVERLAY_OFFSET, MAX_OVERLAY_OFFSET),
                        dy = (it.dy - distanceY / h).coerceIn(-MAX_OVERLAY_OFFSET, MAX_OVERLAY_OFFSET),
                    )
                }
                return@post
            }
            if (!panMode) return@post
            // Live-GL: shift the camera analytically from the screen delta
            // (metres-per-pixel at the current zoom, rotated by the map bearing
            // for heading-up). Backend-independent — doesn't depend on a
            // main-thread projection round-trip, which is why the projected
            // path didn't move the GL camera.
            if (glMode) {
                val loc0 = lastDrawnLocation ?: return@post
                val lat0 = loc0.latitude
                val mpp = 156_543.03392 * cos(Math.toRadians(lat0)) / Math.pow(2.0, smoothedZoom)
                val b = Math.toRadians(if (hasBearing) smoothedBearingDeg.toDouble() else 0.0)
                // Match the projected path's semantics: to = point under
                // (centre + delta), pan toward it. Screen +y is down.
                val headingM = -distanceY * mpp
                val rightM = distanceX * mpp
                val north = headingM * cos(b) - rightM * Math.sin(b)
                val east = headingM * Math.sin(b) + rightM * cos(b)
                panLatOffset = (panLatOffset + north / 111_320.0)
                    .coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
                panLonOffset = (panLonOffset + east / (111_320.0 * cos(Math.toRadians(lat0))))
                    .coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
                scheduleRender()
                return@post
            }
            val snap: MapProjector = snapshotter.latest?.let { SnapshotProjector(it) }
                ?: return@post
            val w = snap.width
            val h = snap.height
            val cx = w / 2f
            val cy = h / 2f
            val from = snap.latLngForPixel(android.graphics.PointF(cx, cy))
            val to = snap.latLngForPixel(android.graphics.PointF(cx + distanceX, cy + distanceY))
            panLatOffset = (panLatOffset + (to.latitude - from.latitude))
                .coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
            panLonOffset = (panLonOffset + (to.longitude - from.longitude))
                .coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
            scheduleRender()
        }
    }

    // ── Rendering ──────────────────────────────────────────────────

    /** Coalesce bursts (GNSS tick + several tile arrivals) into one
     *  draw per main-loop pass. */
    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        mainHandler.post {
            renderPending = false
            renderFrame()
        }
    }

    private fun renderFrame() {
        val gl = glMap
        if (glEnabled && gl != null) {
            renderFrameGl(gl)
            return
        }
        glMode = false
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            // Surface torn down between isValid and lock — next
            // onSurfaceAvailable will repaint.
            null
        } ?: return
        try {
            // Drive the frame off the LIVE locked-canvas dimensions, not the
            // cached SurfaceContainer size. Hosts resize the surface (split
            // screen, window drag, inset changes) without always re-delivering
            // onSurfaceAvailable — the container size then goes stale and the
            // map stops tracking the available space. The locked canvas always
            // reflects the real current buffer, so the snapshotter re-renders
            // at the new size and the layout stays reactive.
            val w = if (canvas.width > 0) canvas.width else container.width
            val h = if (canvas.height > 0) canvas.height else container.height
            drawFrame(canvas, w, h)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
                // Same race on the way out; nothing to recover.
            }
        }
    }

    /** GL backend frame: MapLibre draws the base map onto the surface; we
     *  draw the overlays (route/puck/traffic/cluster/HUD) onto a transparent
     *  bitmap and hand it to the render thread to composite over the map. No
     *  surface lock — the EGL thread owns the surface. */
    private fun renderFrameGl(gl: org.maplibre.android.maps.CarGlMap) {
        glMode = true
        val container = surfaceContainer ?: return
        val w = if (container.width > 0) container.width else surfaceW
        val h = if (container.height > 0) container.height else surfaceH
        if (w <= 0 || h <= 0) return
        // Track surface-size changes to the GL map (resizeView + backend).
        gl.onSurfaceResized(w, h)
        val bmp = nextOverlayBuffer(w, h)
        bmp.eraseColor(Color.TRANSPARENT)
        drawFrame(Canvas(bmp), w, h)
        gl.present(bmp)
    }

    /** Ping-pong the two overlay bitmaps so the GL thread reads one while we
     *  draw the other. Reallocated (not recycled — the GL thread may still
     *  hold the old one) when the surface size changes. */
    private fun nextOverlayBuffer(w: Int, h: Int): android.graphics.Bitmap {
        overlayBufIdx = overlayBufIdx xor 1
        var b = overlayBuffers[overlayBufIdx]
        if (b == null || b.width != w || b.height != h) {
            b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            overlayBuffers[overlayBufIdx] = b
        }
        return b
    }

    private fun drawFrame(canvas: Canvas, w: Int, h: Int) {
        val dark = carContext.isDarkMode
        val bg = if (dark) BG_DARK else BG_LIGHT
        // GL backend: the map fills the surface underneath; keep the overlay
        // canvas transparent so it shows through. Snapshotter: opaque fill.
        if (!glMode) canvas.drawColor(bg)
        surfaceW = w
        surfaceH = h
        elementBounds.clear()

        // The last fix dead-reckoned forward to this frame (see
        // displayedLocation), so the map + needles glide between the
        // ~1 Hz GNSS fixes; falls back to the raw snapshot pre-first-fix.
        val loc = displayedLocation() ?: snapshot.location

        // The map IS the surface — full-bleed across the whole video
        // surface so it reads as a navigation map, not a gauge panel with
        // a map beside it. The instrument dials are small corner widgets
        // layered on top, anchored to the host's safe area so they never
        // cover the host's turn card, ETA or action strips — the
        // navigation instructions stay fully visible by construction.
        if (loc != null) {
            drawVectorMap(canvas, 0f, w, h, loc, dark)
        } else {
            drawWaitingForFix(canvas, 0f, w.toFloat(), h, dark)
        }

        // Optional overlays — opt-in, default OFF (Play Auto policy: keep
        // the nav template to map + driving info). The full gauge cluster
        // already shows speed + limit, so the minimal badge is the
        // navigation-only fallback shown when the cluster is hidden.
        if (overlayConfig.cluster) {
            // drawCluster wraps each piece (background / gauges / text / compass)
            // in its own overlay() so they're individually editable.
            drawCluster(canvas, w, h, loc)
        } else {
            drawSpeedBadge(canvas, w, h, loc, dark)
        }
        drawHud(canvas, w, h, dark, showRecordingStrip = overlayConfig.recordingStrip)
        // Centre the banners on the safe area, not the raw screen, so they
        // track the host's chrome layout instead of hiding under it.
        val safe = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val bannerLeft = safe.left.toFloat().coerceIn(0f, w.toFloat())
        val bannerRight = safe.right.toFloat().coerceIn(bannerLeft, w.toFloat())
        if (overlayConfig.rallyPanel) drawRallyPanel(canvas, bannerLeft, bannerRight, h, dark)
        drawNavStatus(canvas, bannerLeft, bannerRight, h, dark)
        drawAltBanner(canvas, bannerLeft, bannerRight, h, dark)

        if (editMode) drawEditHint(canvas, w, h)
    }

    // ── Overlay transform + edit-mode decoration ────────────────────

    private val overlayMatrix = android.graphics.Matrix()

    /** Draw [element] through its user drag/scale override. [natRect] is the
     *  element's designed bounds (its centre is the scale pivot); [draw]
     *  renders at the natural position. Records the transformed bounds for
     *  tap hit-testing and, in edit mode, outlines the element. */
    private inline fun overlay(canvas: Canvas, element: OverlayElement, natRect: RectF, draw: () -> Unit) {
        val ov = overlayLayout[element] ?: LayoutOverride()
        val dxPx = ov.dx * surfaceW
        val dyPx = ov.dy * surfaceH
        val cx = natRect.centerX()
        val cy = natRect.centerY()
        canvas.save()
        canvas.translate(dxPx, dyPx)
        canvas.scale(ov.scale, ov.scale, cx, cy)
        draw()
        canvas.restore()
        overlayMatrix.reset()
        overlayMatrix.postScale(ov.scale, ov.scale, cx, cy)
        overlayMatrix.postTranslate(dxPx, dyPx)
        val tb = RectF(natRect)
        overlayMatrix.mapRect(tb)
        elementBounds[element] = tb
        if (editMode) drawEditDecoration(element, tb, canvas)
    }

    private fun drawEditDecoration(element: OverlayElement, bounds: RectF, canvas: Canvas) {
        editPaint.style = Paint.Style.STROKE
        val selected = element == selectedElement
        editPaint.strokeWidth = if (selected) 5f else 2.5f
        editPaint.color = EDIT_SELECT
        editPaint.alpha = if (selected) 255 else 150
        canvas.drawRoundRect(bounds, 14f, 14f, editPaint)
        editPaint.alpha = 255
    }

    /** Banner explaining the edit gestures, top-centre, while editing. */
    private fun drawEditHint(canvas: Canvas, w: Int, h: Int) {
        val text = if (selectedElement != null) {
            carContext.getString(be.appmire.gpsinfo.R.string.car_edit_hint_selected)
        } else {
            carContext.getString(be.appmire.gpsinfo.R.string.car_edit_hint_tap)
        }
        hudTextPaint.textAlign = Paint.Align.CENTER
        hudTextPaint.isFakeBoldText = false
        hudTextPaint.textSize = h * 0.035f
        val tw = hudTextPaint.measureText(text)
        val cx = w / 2f
        val pad = h * 0.025f
        val top = h * 0.5f - h * 0.04f
        bubblePaint.color = EDIT_HINT_BG
        val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + h * 0.04f + pad)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bubblePaint)
        hudTextPaint.color = Color.WHITE
        canvas.drawText(text, cx, top + h * 0.04f + pad * 0.1f, hudTextPaint)
    }

    /** The instrument cluster, overlaid on the full-bleed map. The host surface
     *  shape decides the layout: a wide surface gets the split cockpit (edge-HUD
     *  gauges flanking the map), a narrow one falls back to the single integrated
     *  gauge. The map is always full-bleed beneath — navigation stays the
     *  surface, the gauges are overlays. */
    private fun drawCluster(canvas: Canvas, w: Int, h: Int, loc: Location?) {
        val d = buildClusterData(loc)
        val showCompass = overlayConfig.compass
        if (w >= h * COCKPIT_MIN_ASPECT) {
            // Wide surface → cockpit, drawn as separate pieces so each is an
            // independently draggable/scalable overlay (background, left gauge,
            // speed text, right gauge, energy text, compass, clock). Laid out
            // inside the host's safe rectangle so it dodges the turn/ETA cards.
            val safe = visibleArea ?: stableArea ?: Rect(0, 0, w, h)
            val g = instruments.cockpitGeom(w, h, RectF(safe))
            // Scrims are drawn plainly (not an editable element): they're the
            // near-invisible full-bleed background, and wrapping them made a
            // screen-covering hit-box that swallowed taps meant for the gauges.
            instruments.ckScrims(canvas, g, d)
            overlay(canvas, OverlayElement.CL_CLOCK, g.clockRect) { instruments.ckClock(canvas, g, d) }
            overlay(canvas, OverlayElement.CL_SPEED, g.speedRect) { instruments.ckSpeedGauge(canvas, g, d) }
            overlay(canvas, OverlayElement.CL_SPEED_TXT, g.speedTextRect) { instruments.ckSpeedText(canvas, g, d) }
            if (d.obd) {
                overlay(canvas, OverlayElement.CL_ENERGY, g.energyRect) { instruments.ckEnergyGauge(canvas, g, d) }
                overlay(canvas, OverlayElement.CL_ENERGY_TXT, g.energyTextRect) { instruments.ckEnergyText(canvas, g, d) }
            }
            if (showCompass) overlay(canvas, OverlayElement.COMPASS, g.compassRect) { instruments.ckCompass(canvas, g, d) }
        } else {
            // Narrow surface → the single integrated gauge, as one element.
            val s = min(w.toFloat(), h.toFloat()) * 0.98f
            val cx = w / 2f
            val cy = h / 2f
            val cell = RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
            overlay(canvas, OverlayElement.CLUSTER, cell) {
                instruments.drawIntegrated(canvas, cell, d, showCompass)
            }
        }
    }

    /** Assemble the per-frame cluster snapshot from the renderer's live state.
     *  Speed/power use the eased (displayed) values so the gauges glide; unknown
     *  inputs (no OBD/Car API) stay null and render as a dash. */
    private fun buildClusterData(loc: Location?): ClusterData {
        val accKmh = if (loc != null && loc.hasSpeed() &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            loc.hasSpeedAccuracy()
        ) loc.speedAccuracyMetersPerSecond * 3.6f else null
        return ClusterData(
            kmh = dispSpeedMps * 3.6,
            hasSpeed = loc != null && loc.hasSpeed(),
            speedAccKmh = accKmh,
            kw = if (obdConnected) dispPowerKw else null,
            peakKw = if (obdConnected) currentPeakKw() else null,
            consKwh100 = if (obdConnected) currentConsumption() else null,
            socPct = batterySocPct,
            rangeKm = rangeRemainingKm,
            headingDeg = smoothedBearingDeg,
            hasHeading = hasBearing,
            latG = gForce.lateralG,
            lonG = gForce.longitudinalG,
            speedLimitKmh = speedLimitKmh,
            odometerKm = odometerM / 1000.0,
            ambientTempC = ambientTempC,
            clock = clockFmt.format(Date()),
            obd = obdConnected,
        )
    }

    /** Pill banner, top-centre of the map, for navigation phases that
     *  have no other on-screen representation. */
    private fun drawNavStatus(canvas: Canvas, mapLeft: Float, mapRight: Float, h: Int, dark: Boolean) {
        val text = navStatusText ?: return
        // The rally panel owns the same slot while a stage is armed or
        // running — don't fight it.
        if (rally !is RallyState.Idle) return
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, mapRight.toInt(), h)
        val pad = h * 0.03f
        val cx = (mapLeft + mapRight) / 2f
        hudTextPaint.textAlign = Paint.Align.CENTER
        hudTextPaint.isFakeBoldText = false
        hudTextPaint.textSize = h * 0.04f
        val tw = hudTextPaint.measureText(text)
        val top = inset.top + pad
        val panelH = h * 0.04f + pad * 1.5f
        val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + panelH)
        overlay(canvas, OverlayElement.NAV_BANNER, rect) {
            bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
            canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubblePaint)
            canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubbleStrokePaint)
            hudTextPaint.textAlign = Paint.Align.CENTER
            hudTextPaint.textSize = h * 0.04f
            hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
            canvas.drawText(text, cx, top + panelH - pad * 0.85f, hudTextPaint)
        }
    }

    /**
     * Vector map for the car: blit the latest MapLibre snapshot (which
     * MapLibre renders with native bearing + pitch) into the map area,
     * then project the route / breadcrumb / vehicle overlays onto it
     * via the snapshot's own projection — no mercator math, no
     * perspective matrix. Requesting the next snapshot for the current
     * camera is what keeps the map live.
     */
    private fun drawVectorMap(
        canvas: Canvas,
        mapLeft: Float,
        mapW: Int,
        h: Int,
        loc: Location,
        dark: Boolean,
    ) {
        // Camera follows the vehicle (+ any pan offset). Heading-up bakes
        // the course into the map bearing; tilt becomes MapLibre's native
        // pitch so labels stay upright and the road recedes.
        //
        // Look-ahead: while moving (not panning) push the camera TARGET
        // ahead of the vehicle along the course, so the puck sits low on
        // the surface (~80% down) with the road ahead filling the view —
        // a nav-style camera instead of the puck dead-centre.
        val bearing = if (hasBearing) smoothedBearingDeg.toDouble() else 0.0
        val lookAheadM = if (hasBearing && !panMode) {
            val mpp = 156_543.03392 * cos(Math.toRadians(loc.latitude)) /
                Math.pow(2.0, smoothedZoom)
            LOOK_AHEAD_FRACTION * mpp * h
        } else 0.0
        val brRad = Math.toRadians(bearing)
        val camLat = loc.latitude + panLatOffset +
            lookAheadM * cos(brRad) / 111_320.0
        val camLon = loc.longitude + panLonOffset +
            lookAheadM * Math.sin(brRad) / (111_320.0 * cos(Math.toRadians(loc.latitude)))
        val pitch = if (tilted) CAR_PITCH_DEG else 0.0

        // Live-GL backend: MapLibre renders the base map straight onto the
        // surface, so here we only set the camera and draw the overlays via
        // its projection. No bitmap blit, and no stale-snapshot shift — the
        // camera is exact this frame, and the look-ahead already seats the
        // puck low on screen.
        val gl = glMap
        if (glMode && gl != null) {
            gl.setBuildings3d(viewMode == MapViewMode.TILTED_3D)
            gl.setCamera(camLat, camLon, smoothedZoom, bearing, pitch)
            val glProj = gl.currentProjector() ?: return
            val vehPf = glProj.pixelForLatLng(
                org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude),
            )
            val puckProjected = android.graphics.PointF(mapLeft + vehPf.x, vehPf.y)
            drawAlternatives(canvas, glProj, mapLeft)
            drawProjectedRoute(canvas, glProj, mapLeft, loc, puckProjected)
            drawTraffic(canvas, glProj, mapLeft)
            drawProjectedBreadcrumb(canvas, glProj, mapLeft)
            drawProjectedMarker(canvas, puckProjected)
            return
        }

        // 3D building extrusions only in the dedicated 3D mode; the other
        // two show flat footprints (the snapshotter hides the
        // `building-3d` layer).
        snapshotter.setBuildings3d(viewMode == MapViewMode.TILTED_3D)
        val cam = org.maplibre.android.camera.CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(camLat, camLon))
            .zoom(smoothedZoom)
            .bearing(bearing)
            .tilt(pitch)
            .build()
        snapshotter.request(mapW, h, cam)

        val snap = snapshotter.latest
        if (snap == null) {
            // Style/first snapshot still loading — keep the bg, show a
            // hint instead of a blank panel.
            drawWaitingForFix(canvas, mapLeft, mapLeft + mapW, h, dark)
            return
        }
        val bmp = snap.bitmap
        val proj: MapProjector = SnapshotProjector(snap)
        val following = hasBearing && !panMode
        // Where the current (dead-reckoned) vehicle projects on this — possibly
        // stale — snapshot. While following, shift the whole map so that point
        // sits under the fixed puck anchor: the puck lands on the road, and the
        // shift growing between snapshots pans the map smoothly (less jank).
        val vehPf = proj.pixelForLatLng(org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude))
        val puckProjected = android.graphics.PointF(mapLeft + vehPf.x, vehPf.y)
        var shiftX = 0f
        var shiftY = 0f
        if (following) {
            val anchorX = mapLeft + mapW / 2f
            val anchorY = h * PUCK_SCREEN_FRACTION
            shiftX = (anchorX - puckProjected.x).coerceIn(-mapW * MAX_MAP_SHIFT, mapW * MAX_MAP_SHIFT)
            shiftY = (anchorY - puckProjected.y).coerceIn(-h * MAX_MAP_SHIFT, h * MAX_MAP_SHIFT)
        }
        canvas.save()
        canvas.translate(shiftX, shiftY)
        if (bmp.width == mapW && bmp.height == h) {
            canvas.drawBitmap(bmp, mapLeft, 0f, layerPaint)
        } else {
            // Surface resized since this snapshot (a correctly-sized one is
            // already requested). Stretch this one to fill for the transition.
            canvas.drawBitmap(bmp, null, RectF(mapLeft, 0f, mapLeft + mapW, h.toFloat()), layerPaint)
        }
        // Overlays share the shift so they stay glued to the map; the puck is
        // drawn at the projected vehicle position, which the shift places back
        // at the fixed anchor.
        drawAlternatives(canvas, proj, mapLeft)
        drawProjectedRoute(canvas, proj, mapLeft, loc, puckProjected)
        drawTraffic(canvas, proj, mapLeft)
        drawProjectedBreadcrumb(canvas, proj, mapLeft)
        drawProjectedMarker(canvas, puckProjected)
        canvas.restore()
        if (dark) canvas.drawRect(mapLeft, 0f, mapLeft + mapW, h.toFloat(), darkScrimPaint)
    }

    /** The vehicle puck's drawn position this frame: a fixed low-centre
     *  anchor while following (the map scrolls under it), or the real
     *  projected location when panning. Shared by the marker and the route
     *  line so the line always emanates from the puck — no gap. */
    private fun puckScreenPoint(
        snap: MapProjector,
        mapLeft: Float,
        loc: Location,
        following: Boolean,
    ): android.graphics.PointF {
        return if (following) {
            android.graphics.PointF(
                mapLeft + snap.width / 2f,
                snap.height * PUCK_SCREEN_FRACTION,
            )
        } else {
            val pf = snap.pixelForLatLng(
                org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude),
            )
            android.graphics.PointF(mapLeft + pf.x, pf.y)
        }
    }

    /** Dimmed indigo lines for the en-route alternatives, drawn beneath the
     *  active route so the upcoming fork reads at a glance. */
    private fun drawAlternatives(
        canvas: Canvas,
        snap: MapProjector,
        mapLeft: Float,
    ) {
        if (alternatives.isEmpty()) return
        val maxJump = snap.height * 4f
        altPaint.color = ALT_ROUTE_COLOR
        altPaint.strokeWidth = 12f
        for (alt in alternatives) {
            val pts = alt.route.points
            if (pts.size < 2) continue
            val step = (pts.size + MAX_PROJECTED_POINTS - 1) / MAX_PROJECTED_POINTS
            val path = Path()
            var started = false
            var lastX = 0f
            var lastY = 0f
            var i = 0
            while (i < pts.size) {
                val p = pts[i]
                val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(p.lat, p.lon))
                val x = mapLeft + pf.x
                val y = pf.y
                if (!started) {
                    path.moveTo(x, y); started = true
                } else if (kotlin.math.hypot((x - lastX).toDouble(), (y - lastY).toDouble()) > maxJump) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                lastX = x; lastY = y
                if (i == pts.size - 1) break
                i = (i + step).coerceAtMost(pts.size - 1)
            }
            canvas.drawPath(path, altPaint)
        }
    }

    /** Best alternative's trade-off, e.g. "Alt: 2 min longer · 10 km shorter",
     *  in a pill below the top edge. Returns the on-screen rect (for nothing
     *  yet — accept is via the action strip). */
    private fun drawAltBanner(canvas: Canvas, mapLeft: Float, mapRight: Float, h: Int, dark: Boolean) {
        val alt = alternatives.firstOrNull() ?: return
        if (rally !is RallyState.Idle || navStatusText != null) return
        val text = carContext.getString(be.appmire.gpsinfo.R.string.car_alt_banner, altTradeoffText(alt))
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, mapRight.toInt(), h)
        val pad = h * 0.03f
        val cx = (mapLeft + mapRight) / 2f
        hudTextPaint.textAlign = Paint.Align.CENTER
        hudTextPaint.isFakeBoldText = false
        hudTextPaint.textSize = h * 0.04f
        val tw = hudTextPaint.measureText(text)
        val top = inset.top + pad
        val panelH = h * 0.04f + pad * 1.5f
        bubblePaint.color = ALT_BANNER_BG
        val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + panelH)
        canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubblePaint)
        canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubbleStrokePaint)
        hudTextPaint.color = Color.WHITE
        canvas.drawText(text, cx, top + panelH - pad * 0.85f, hudTextPaint)
    }

    /** "2 min longer · 10 km shorter" (metric) from an alternative's deltas. */
    private fun altTradeoffText(alt: be.appmire.gpsinfo.data.nav.NavigationController.RouteAlternative): String {
        val parts = ArrayList<String>(2)
        val mins = abs(alt.deltaSeconds) / 60
        val timeSaves = alt.deltaSeconds <= 0
        if (mins >= 1) {
            parts.add(carContext.getString(if (timeSaves) be.appmire.gpsinfo.R.string.nav_alt_faster else be.appmire.gpsinfo.R.string.nav_alt_slower, mins))
        }
        val distSaves = alt.deltaMeters <= 0
        if (abs(alt.deltaMeters) >= 500) {
            val km = "%.0f km".format(Locale.ROOT, abs(alt.deltaMeters) / 1000.0)
            parts.add(carContext.getString(if (distSaves) be.appmire.gpsinfo.R.string.nav_alt_shorter else be.appmire.gpsinfo.R.string.nav_alt_longer, km))
        }
        if (parts.isEmpty()) return carContext.getString(be.appmire.gpsinfo.R.string.nav_alt_title)
        val sep = if (parts.size == 2 && timeSaves != distSaves) " ${carContext.getString(be.appmire.gpsinfo.R.string.nav_alt_but)} " else " · "
        return parts.joinToString(sep)
    }

    private fun drawProjectedRoute(
        canvas: Canvas,
        snap: MapProjector,
        mapLeft: Float,
        loc: Location,
        puck: android.graphics.PointF,
    ) {
        val path = routeAheadPath(navRoute, snap, mapLeft, loc, puck) ?: return
        navCasingPaint.strokeWidth = 16f
        navRoutePaint.strokeWidth = 10f
        canvas.drawPath(path, navCasingPaint)
        canvas.drawPath(path, navRoutePaint)
    }

    /** The route line to draw: start at the point nearest the vehicle
     *  (so the part already driven vanishes *live*, at frame rate, not at
     *  the ~1 Hz segment cadence) and walk CONSECUTIVE points forward —
     *  full fidelity, no decimation — up to [ROUTE_DRAW_POINTS]. The
     *  visible nav view only spans the next ~km anyway, so this is cheap
     *  and the line actually follows the streets (decimating the whole
     *  route to 120 points was what made it cut corners). */
    private fun routeAheadPath(
        pts: List<DoubleArray>?,
        snap: MapProjector,
        mapLeft: Float,
        loc: Location,
        puck: android.graphics.PointF,
    ): Path? {
        if (pts == null || pts.size < 2) return null
        // Nearest point to the vehicle — cheap squared-degree scan.
        var startIdx = 0
        var best = Double.MAX_VALUE
        for (i in pts.indices) {
            val dLat = pts[i][0] - loc.latitude
            val dLon = pts[i][1] - loc.longitude
            val d = dLat * dLat + dLon * dLon
            if (d < best) { best = d; startIdx = i }
        }
        // Start the line AT the puck and only walk points strictly ahead of
        // the vehicle: the nearest point is usually just behind the puck, so
        // including it would draw a tiny backward stub and reopen the gap the
        // line is meant to close.
        val firstAhead = (startIdx + 1).coerceAtMost(pts.size)
        val end = (firstAhead + ROUTE_DRAW_POINTS).coerceAtMost(pts.size)
        val maxJump = snap.height * 4f
        val path = Path()
        path.moveTo(puck.x, puck.y)
        var lastX = puck.x
        var lastY = puck.y
        for (i in firstAhead until end) {
            val p = pts[i]
            val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(p[0], p[1]))
            val x = mapLeft + pf.x
            val y = pf.y
            if (kotlin.math.hypot((x - lastX).toDouble(), (y - lastY).toDouble()) > maxJump) {
                path.moveTo(x, y) // discontinuity — don't streak a line to it
            } else {
                path.lineTo(x, y)
            }
            lastX = x; lastY = y
        }
        return path
    }

    /** Live traffic incidents projected onto the map: coloured lines for
     *  jams/closures/roadworks, a dot for point incidents. Budget-capped so a
     *  busy feed can't flood the per-frame JNI projection cost. */
    private fun drawTraffic(
        canvas: Canvas,
        snap: MapProjector,
        mapLeft: Float,
    ) {
        if (traffic.isEmpty()) return
        var budget = MAX_PROJECTED_POINTS * 4
        val maxJump = snap.height * 4f
        for (inc in traffic) {
            if (budget <= 0) break
            val geo = inc.geometry
            if (geo.isEmpty()) continue
            budget -= geo.size
            val color = trafficColor(inc.category)
            if (geo.size == 1) {
                val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(geo[0][1], geo[0][0]))
                trafficFillPaint.color = color
                canvas.drawCircle(mapLeft + pf.x, pf.y, 9f, trafficFillPaint)
                continue
            }
            val path = Path()
            var started = false
            var lastX = 0f
            var lastY = 0f
            for (p in geo) {
                val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(p[1], p[0]))
                val x = mapLeft + pf.x
                val y = pf.y
                if (!started) {
                    path.moveTo(x, y); started = true
                } else if (kotlin.math.hypot((x - lastX).toDouble(), (y - lastY).toDouble()) > maxJump) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
                lastX = x; lastY = y
            }
            trafficPaint.color = color
            trafficPaint.strokeWidth = 12f
            canvas.drawPath(path, trafficPaint)
        }
    }

    private fun trafficColor(category: String): Int = when (category) {
        "accident" -> TRAFFIC_ACCIDENT
        "congestion" -> TRAFFIC_CONGESTION
        "roadworks" -> TRAFFIC_ROADWORKS
        "laneClosure" -> TRAFFIC_CLOSURE
        else -> TRAFFIC_OTHER
    }

    private fun drawProjectedBreadcrumb(
        canvas: Canvas,
        snap: MapProjector,
        mapLeft: Float,
    ) {
        val pts = breadcrumb.map { doubleArrayOf(it[0], it[1]) }
        val path = projectedPath(pts, snap, mapLeft) ?: return
        trailCasingPaint.strokeWidth = 15f
        trailPaint.strokeWidth = 9f
        canvas.drawPath(path, trailCasingPaint)
        canvas.drawPath(path, trailPaint)
    }

    /** Project a lat/lon polyline onto the snapshot, decimated to at
     *  most [MAX_PROJECTED_POINTS] so a long route/breadcrumb can't
     *  flood the per-frame JNI projection cost (each pixelForLatLng is
     *  a native call) and jank the surface — the host kills slow apps.
     *  The last point is always included so the line meets the marker. */
    private fun projectedPath(
        pts: List<DoubleArray>?,
        snap: MapProjector,
        mapLeft: Float,
    ): Path? {
        if (pts == null || pts.size < 2) return null
        val step = (pts.size + MAX_PROJECTED_POINTS - 1) / MAX_PROJECTED_POINTS
        val path = Path()
        // A point far outside the view (or a tilted-horizon point) can
        // project to a wild pixel; a lineTo to it streaks a nonsensical
        // line across the surface. Break the path on any jump larger than
        // this many surface-heights and resume with a moveTo.
        val maxJump = snap.height * 4f
        var started = false
        var lastX = 0f
        var lastY = 0f
        var i = 0
        while (i < pts.size) {
            val p = pts[i]
            val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(p[0], p[1]))
            val x = mapLeft + pf.x
            val y = pf.y
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else if (kotlin.math.hypot((x - lastX).toDouble(), (y - lastY).toDouble()) > maxJump) {
                path.moveTo(x, y) // discontinuity — don't streak a line to it
            } else {
                path.lineTo(x, y)
            }
            lastX = x
            lastY = y
            if (i == pts.size - 1) break
            i = (i + step).coerceAtMost(pts.size - 1)
        }
        return path
    }

    /** Vehicle marker at its projected position. Whenever the car is
     *  moving the camera bakes the course into the map bearing
     *  (heading-up), so the chevron always points up.
     *
     *  When the map is tilted (2.5D), the marker is laid onto the ground
     *  plane: the canvas is foreshortened vertically by the camera pitch
     *  about the anchor, so the disc reads as an ellipse on the road and
     *  the chevron rakes toward the horizon — matching the perspective
     *  instead of floating flat-on. The surrounding disc is drawn
     *  semi-transparent so the road underneath stays visible. */
    private fun drawProjectedMarker(
        canvas: Canvas,
        puck: android.graphics.PointF,
    ) {
        // The puck position is resolved once per frame (see puckScreenPoint)
        // and shared with the route line so the line starts exactly here.
        val x = puck.x
        val y = puck.y
        val r = MARKER_RADIUS
        canvas.save()
        if (tilted) canvas.scale(1f, PITCH_FORESHORTEN, x, y)
        canvas.drawCircle(x, y, r + 4f, markerRingPaint)
        canvas.drawCircle(x, y, r, markerFillPaint)
        val chevron = Path().apply {
            moveTo(x, y - r * 0.62f)
            lineTo(x - r * 0.45f, y + r * 0.40f)
            lineTo(x, y + r * 0.12f)
            lineTo(x + r * 0.45f, y + r * 0.40f)
            close()
        }
        canvas.drawPath(chevron, markerChevronPaint)
        canvas.restore()
    }

    private fun drawWaitingForFix(canvas: Canvas, mapLeft: Float, mapRight: Float, h: Int, dark: Boolean) {
        hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
        hudTextPaint.textSize = h * 0.05f
        hudTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            carContext.getString(be.appmire.gpsinfo.R.string.car_waiting_fix),
            (mapLeft + mapRight) / 2f,
            h / 2f,
            hudTextPaint,
        )
    }

    /** Map-area HUD over the full-bleed map: the OBD ambient-temp badge,
     *  the recording trip strip, and the OSM attribution — each placed
     *  clear of the corner instrument dials in [layout]. */
    /** Map-area HUD over the full-bleed map: the recording trip strip and the
     *  required OSM attribution, both bottom-centre over the map (clear of the
     *  edge-HUD gauges). The ambient temperature now lives in the cluster. */
    private fun drawHud(canvas: Canvas, w: Int, h: Int, dark: Boolean, showRecordingStrip: Boolean) {
        val pad = h * 0.03f
        val unit = h * 0.13f
        val cx = w / 2f

        // Recording trip strip (distance · duration + REC dot), bottom-centre.
        // Opt-in: a recording readout isn't navigation info, so it's off by
        // default and only drawn when the user enabled it.
        val rec = recording as? RecordingState.Recording
        if (rec != null && showRecordingStrip) {
            val stripText = "%.1f km   %s".format(
                Locale.ROOT,
                rec.distanceMetres / 1000.0,
                formatDuration(System.currentTimeMillis() - rec.startedAtMillis),
            )
            hudTextPaint.textAlign = Paint.Align.LEFT
            hudTextPaint.textSize = unit * 0.34f
            val tw = hudTextPaint.measureText(stripText)
            val recDotSpace = unit * 0.55f
            val totalW = tw + recDotSpace
            val sx = cx - totalW / 2f
            val sy = h - pad * 2.6f
            val strip = RectF(sx - pad / 2, sy - unit * 0.42f, sx + totalW + pad / 2, sy + unit * 0.22f)
            overlay(canvas, OverlayElement.RECORDING_STRIP, strip) {
                bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubblePaint)
                canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubbleStrokePaint)
                hudTextPaint.textAlign = Paint.Align.LEFT
                hudTextPaint.textSize = unit * 0.34f
                hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                canvas.drawText(stripText, sx, sy, hudTextPaint)
                recDotPaint.color = if (rec.paused) REC_PAUSED else REC_ACTIVE
                canvas.drawCircle(sx + tw + recDotSpace / 2, sy - unit * 0.10f, unit * 0.13f, recDotPaint)
            }
        }

        // OSM attribution (tile-policy requirement). Tiny, bottom-centre.
        hudTextPaint.textAlign = Paint.Align.CENTER
        hudTextPaint.textSize = h * 0.013f
        hudTextPaint.color = if (dark) HUD_MUTED_DARK else HUD_MUTED_LIGHT
        canvas.drawText("© OpenStreetMap", cx, h - 4f, hudTextPaint)
    }

    /** Minimal driving-info badge for the navigation-only surface (drawn when
     *  the full gauge cluster is hidden): a compact speed pill and an EU
     *  speed-limit roundel, bottom-left inside the host's safe area, clear of
     *  the bottom-centre attribution / recording strip. Both are individually
     *  toggleable; speed + posted limit are explicitly permitted driving
     *  information under the Auto nav-template policy. */
    private fun drawSpeedBadge(canvas: Canvas, w: Int, h: Int, loc: Location?, dark: Boolean) {
        if (!overlayConfig.speed && !overlayConfig.speedLimit) return
        val safe = visibleArea ?: stableArea ?: Rect(0, 0, w, h)
        val pad = h * 0.03f
        val left = safe.left.toFloat().coerceIn(0f, w.toFloat()) + pad
        val bottom = safe.bottom.toFloat().coerceIn(0f, h.toFloat()) - pad

        // Speed pill — big number + km/h unit, bottom-left. Designed bounds are
        // fixed (independent of the limit sign) so each can be dragged alone.
        if (overlayConfig.speed) {
            val numText = if (loc != null && loc.hasSpeed())
                "%.1f".format(Locale.ROOT, dispSpeedMps * 3.6f) else "––"
            val unitText = " km/h"
            hudTextPaint.isFakeBoldText = true
            hudTextPaint.textSize = h * 0.085f
            val numW = hudTextPaint.measureText(numText)
            hudTextPaint.isFakeBoldText = false
            hudTextPaint.textSize = h * 0.04f
            val unitW = hudTextPaint.measureText(unitText)
            val pillH = h * 0.105f
            val pillW = numW + unitW + pad * 1.6f
            val pill = RectF(left, bottom - pillH, left + pillW, bottom)
            overlay(canvas, OverlayElement.SPEED, pill) {
                bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                canvas.drawRoundRect(pill, pillH / 2, pillH / 2, bubblePaint)
                canvas.drawRoundRect(pill, pillH / 2, pillH / 2, bubbleStrokePaint)
                val baseY = pill.bottom - pillH * 0.30f
                hudTextPaint.textAlign = Paint.Align.LEFT
                hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                hudTextPaint.isFakeBoldText = true
                hudTextPaint.textSize = h * 0.085f
                canvas.drawText(numText, pill.left + pad * 0.8f, baseY, hudTextPaint)
                hudTextPaint.isFakeBoldText = false
                hudTextPaint.color = if (dark) HUD_MUTED_DARK else HUD_MUTED_LIGHT
                hudTextPaint.textSize = h * 0.04f
                canvas.drawText(unitText, pill.left + pad * 0.8f + numW, baseY, hudTextPaint)
            }
        }

        // EU speed-limit roundel — white disc, red ring, black number — sits
        // above the speed pill's slot by default; draggable independently.
        val limit = speedLimitKmh
        if (overlayConfig.speedLimit && limit != null) {
            val r = h * 0.06f
            val cx = left + r
            val cy = bottom - h * 0.105f - pad * 0.8f - r
            val natRect = RectF(cx - r, cy - r, cx + r, cy + r)
            overlay(canvas, OverlayElement.SPEED_LIMIT, natRect) {
                limitFillPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r, limitFillPaint)
                limitRingPaint.color = SIGN_RED
                limitRingPaint.strokeWidth = r * 0.22f
                canvas.drawCircle(cx, cy, r * 0.84f, limitRingPaint)
                val s = limit.toString()
                hudTextPaint.textAlign = Paint.Align.CENTER
                hudTextPaint.isFakeBoldText = true
                hudTextPaint.color = Color.BLACK
                var fs = r * 1.0f
                hudTextPaint.textSize = fs
                while (hudTextPaint.measureText(s) > r * 1.4f && fs > 4f) {
                    fs *= 0.9f; hudTextPaint.textSize = fs
                }
                canvas.drawText(s, cx, cy + fs * 0.36f, hudTextPaint)
                hudTextPaint.isFakeBoldText = false
            }
        }
    }

    /** Regularity-test panel, top-centre: the early/late delta is THE
     *  number during an RT, so it gets the biggest type on the screen.
     *  Armed shows a one-line "waiting for the marshal" banner. */
    private fun drawRallyPanel(canvas: Canvas, mapLeft: Float, mapRight: Float, h: Int, dark: Boolean) {
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, mapRight.toInt(), h)
        val pad = h * 0.03f
        val mapCx = (mapLeft + mapRight) / 2f
        when (val r = rally) {
            is RallyState.Running -> {
                val delta = r.deltaSeconds
                val deltaColor = when {
                    abs(delta) < 1.0 -> RALLY_ON_TIME
                    abs(delta) < 3.0 -> RALLY_NEAR
                    else -> RALLY_OFF
                }
                val deltaText = "%+.0f s".format(Locale.ROOT, delta)
                val subText = "→ %.0f km/h   %.2f km · %s".format(
                    Locale.ROOT,
                    r.targetSpeedKmh,
                    r.drivenKm,
                    when {
                        r.wheelSensorsFresh > 1 -> "WHEEL ×${r.wheelSensorsFresh}"
                        r.usingWheel -> "WHEEL"
                        else -> "GPS"
                    },
                )
                val cx = mapCx
                hudTextPaint.textAlign = Paint.Align.CENTER
                hudTextPaint.isFakeBoldText = true
                hudTextPaint.textSize = h * 0.14f
                val deltaWidth = hudTextPaint.measureText(deltaText)
                hudTextPaint.textSize = h * 0.045f
                val subWidth = hudTextPaint.measureText(subText)
                val panelW = maxOf(deltaWidth, subWidth) + pad * 3
                val panelH = h * 0.14f + h * 0.045f + pad * 2.5f
                val top = inset.top + pad
                val rect = RectF(cx - panelW / 2, top, cx + panelW / 2, top + panelH)
                overlay(canvas, OverlayElement.RALLY_PANEL, rect) {
                    bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                    canvas.drawRoundRect(rect, pad, pad, bubblePaint)
                    canvas.drawRoundRect(rect, pad, pad, bubbleStrokePaint)
                    hudTextPaint.textAlign = Paint.Align.CENTER
                    hudTextPaint.isFakeBoldText = true
                    hudTextPaint.textSize = h * 0.14f
                    hudTextPaint.color = deltaColor
                    canvas.drawText(deltaText, cx, top + pad * 0.6f + h * 0.12f, hudTextPaint)
                    hudTextPaint.isFakeBoldText = false
                    hudTextPaint.textSize = h * 0.045f
                    hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                    canvas.drawText(subText, cx, top + panelH - pad, hudTextPaint)
                }
            }
            is RallyState.Armed -> {
                val text = carContext.getString(be.appmire.gpsinfo.R.string.car_rally_armed)
                val cx = mapCx
                hudTextPaint.textAlign = Paint.Align.CENTER
                hudTextPaint.isFakeBoldText = false
                hudTextPaint.textSize = h * 0.04f
                val tw = hudTextPaint.measureText(text)
                val top = inset.top + pad
                val panelH = h * 0.04f + pad * 1.5f
                val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + panelH)
                overlay(canvas, OverlayElement.RALLY_PANEL, rect) {
                    bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                    canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubblePaint)
                    canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubbleStrokePaint)
                    hudTextPaint.textAlign = Paint.Align.CENTER
                    hudTextPaint.textSize = h * 0.04f
                    hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                    canvas.drawText(text, cx, top + panelH - pad * 0.85f, hudTextPaint)
                }
            }
            RallyState.Idle -> Unit
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = (abs(ms) / 60_000L).toInt()
        return if (totalMin >= 60) "%d:%02d h".format(Locale.ROOT, totalMin / 60, totalMin % 60)
        else "$totalMin min"
    }

    // ── Paints (allocated once; mutated per draw, single-threaded) ──

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = TRAIL_COLOR
    }
    private val trailCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 15f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = TRAIL_CASING
    }
    private val navRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = NAV_ROUTE_COLOR
    }
    private val navCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = NAV_ROUTE_CASING
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER_FILL }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerChevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = BUBBLE_STROKE
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val recDotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trafficPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trafficFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val altPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val limitFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val limitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** FILTER_BITMAP so the snapshot bitmap blits smoothly. */
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val darkScrimPaint = Paint().apply { color = TILE_DARK_SCRIM }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.0

        /** Speed-adaptive zoom: glide between these levels as ground
         *  speed goes 0 → [ZOOM_FAST_KMH]. MapLibre takes the
         *  fractional value directly. */
        const val ZOOM_STANDSTILL = 17.5
        const val ZOOM_FAST = 13.8
        const val ZOOM_FAST_KMH = 120.0
        const val ZOOM_STEP_PER_TICK = 0.18
        const val ZOOM_BIAS_RANGE = 3.0

        /** MapLibre camera pitch when tilt is on — the native 2.5D
         *  perspective (labels stay upright, road recedes). The host's
         *  driving-mode tilt cap is 60°; 57 leans into the road ahead for
         *  the nav look-ahead camera without hitting the cap. */
        const val CAR_PITCH_DEG = 57.0

        // ── Smooth-animation loop tunables (see ensureAnimating) ──
        /** Frame cadence of the dead-reckoning loop. ~15 fps: smooth
         *  enough, conservative on the MapLibre re-snapshot cost (the host
         *  kills slow apps). Lower toward ~33 ms only if a real head unit
         *  tolerates it. */
        const val FRAME_MS = 66L
        /** The live-GL backend renders on the GPU (cheap) rather than
         *  re-snapshotting, so it can afford a smoother ~30 fps cadence. */
        const val GL_FRAME_MS = 33L
        /** Per-frame easing factor for needles + heading (0..1). */
        const val EASE = 0.25f
        /** Per-frame easing for the map zoom (gentler than [EASE]). */
        const val ZOOM_EASE = 0.16f
        /** Below this ground speed the vehicle is treated as stopped (no
         *  dead-reckoning, loop idles). */
        const val MIN_MOVE_MPS = 0.8f
        /** Never extrapolate position more than this past the last fix —
         *  a dropped fix shouldn't fling the marker down the road. */
        const val MAX_EXTRAPOLATE_SEC = 2.0
        /** Camera look-ahead as a fraction of the surface height (in metres
         *  of ground), pushing the puck low on the screen. Tune to taste —
         *  higher = puck lower / more road ahead. */
        const val LOOK_AHEAD_FRACTION = 0.22
        /** Fixed on-screen anchor for the puck while following — fraction
         *  of surface height from the top (~78% down). Keep roughly in
         *  step with LOOK_AHEAD_FRACTION so puck and map agree. */
        const val PUCK_SCREEN_FRACTION = 0.78f
        /** Max fraction of the surface the map is shifted to keep the puck on
         *  the road between snapshots (bounds the edge gap when the snapshot
         *  lags a fast-moving camera). */
        const val MAX_MAP_SHIFT = 0.18f
        /** Consecutive route points drawn ahead of the vehicle, full
         *  fidelity (no decimation). ~90 ≈ the next several km at BRouter's
         *  ~100 m node spacing — more than the zoomed nav view shows. */
        const val ROUTE_DRAW_POINTS = 90
        /** Vertical squash applied to the vehicle marker when tilted, so
         *  it lies on the ground plane instead of facing the camera —
         *  cos of the camera pitch. */
        val PITCH_FORESHORTEN = cos(Math.toRadians(CAR_PITCH_DEG)).toFloat()
        /** Recent G-force samples kept for the corner G-meter trail. At
         *  the car sample rate (~5 Hz) this is a few seconds of history. */
        const val GFORCE_TRAIL = 24

        /** Power peak-hold window and instant-efficiency averaging window. */
        const val PEAK_WINDOW_MS = 30_000L
        const val EFF_WINDOW_MS = 2_000L
        /** Cockpit needs a wide surface; below this aspect ratio (w/h) the
         *  cluster falls back to the single integrated gauge. */
        const val COCKPIT_MIN_ASPECT = 1.1f

        /** Pan clamp (≈±55 km) — keeps a runaway drag from scrolling
         *  far off the route. */
        const val MAX_PAN_DEG = 0.5

        /** Above ~1.1 m/s (walking pace) course-over-ground is stable
         *  enough to rotate the map by. */
        const val MIN_HEADING_UP_SPEED_MPS = 1.1f
        const val SCALE_STEP = 1.15f
        const val BREADCRUMB_CAP = 4000
        const val MARKER_RADIUS = 21f
        /** Per-frame projection budget for route/breadcrumb polylines —
         *  each point is a native pixelForLatLng call; over-projecting
         *  janks the surface and the host kills slow apps. ~120 points
         *  is smooth enough at car-screen scale. */
        const val MAX_PROJECTED_POINTS = 120

        const val BG_DARK = 0xFF11151A.toInt()
        const val BG_LIGHT = 0xFFE8E8E3.toInt()
        const val TILE_DARK_SCRIM = 0x66000000
        const val TRAIL_COLOR = 0xFF00B0FF.toInt()
        const val TRAIL_CASING = 0xCC003C5C.toInt()
        // Navigation route: the RetroDial accent orange with a white
        // casing — unmistakable against both trail cyan and OSM roads.
        const val NAV_ROUTE_COLOR = 0xFFE67635.toInt()
        const val NAV_ROUTE_CASING = 0xCCFFFFFF.toInt()
        // Semi-transparent so the road under the marker stays visible.
        const val MARKER_FILL = 0x99_1A73E8.toInt()
        const val BUBBLE_DARK = 0xE61E242C.toInt()
        const val BUBBLE_LIGHT = 0xF2FFFFFF.toInt()
        const val BUBBLE_STROKE = 0x33808080
        const val HUD_MUTED_DARK = 0xFFB0BAC4.toInt()
        const val HUD_MUTED_LIGHT = 0xFF5F6B76.toInt()
        const val REC_ACTIVE = 0xFFE53935.toInt()
        const val REC_PAUSED = 0xFFFFB300.toInt()
        const val SIGN_RED = 0xFFD32F2F.toInt()
        // Live traffic incident colours.
        const val TRAFFIC_CONGESTION = 0xFFE53935.toInt() // red
        const val TRAFFIC_ACCIDENT = 0xFFB71C1C.toInt()   // dark red
        const val TRAFFIC_ROADWORKS = 0xFFF9A825.toInt()  // amber
        const val TRAFFIC_CLOSURE = 0xFFFB8C00.toInt()    // orange
        const val TRAFFIC_OTHER = 0xFF9E9E9E.toInt()      // grey
        // En-route alternatives.
        const val ALT_ROUTE_COLOR = 0xCC5C6BC0.toInt()    // dimmed indigo
        const val ALT_BANNER_BG = 0xE63949AB.toInt()      // indigo banner
        // Overlay drag/scale limits (Phase 4 edit mode).
        const val MIN_OVERLAY_SCALE = 0.4f
        const val MAX_OVERLAY_SCALE = 3.0f
        /** Max overlay offset as a fraction of surface size, so a runaway drag
         *  can't fling an element off-screen and out of reach. */
        const val MAX_OVERLAY_OFFSET = 0.9f
        const val EDIT_SELECT = 0xFFE67635.toInt()
        const val EDIT_HINT_BG = 0xCC000000.toInt()
        const val RALLY_ON_TIME = 0xFF43A047.toInt()
        const val RALLY_NEAR = 0xFFF9A825.toInt()
        const val RALLY_OFF = 0xFFE53935.toInt()
    }
}

/** Map presentation, cycled by the tilt button. */
enum class MapViewMode {
    /** Top-down, no pitch — buildings read as flat footprints. */
    FLAT,
    /** 2.5D perspective, flat building footprints (no extrusion). */
    TILTED_FLAT,
    /** 2.5D perspective with extruded 3D buildings. */
    TILTED_3D,
}
