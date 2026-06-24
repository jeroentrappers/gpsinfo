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

    /** Instantaneous drive power in kW for the energy meter — null
     *  until an OBD2 source feeds [updatePower]; the dial parks at 0
     *  with a dimmed readout meanwhile. */
    private var powerKw: Double? = null

    fun updatePower(kw: Double?) {
        powerKw = kw
        scheduleRender()
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

    init {
        sessionLifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        mainHandler.removeCallbacks(frameRunnable)
        snapshotter.destroy()
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

    private fun ensureAnimating() {
        if (animating) return
        animating = true
        mainHandler.postDelayed(frameRunnable, FRAME_MS)
    }

    private fun onAnimationFrame() {
        val more = stepAnimation()
        renderFrame()
        if (more) mainHandler.postDelayed(frameRunnable, FRAME_MS) else animating = false
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
        val moving = targetMps > MIN_MOVE_MPS
        val settling = abs(targetMps - dispSpeedMps) > 0.05f || abs(targetKw - dispPowerKw) > 0.5
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
            scheduleRender()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mainHandler.post { this.surfaceContainer = null }
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
            pinchAccumulator *= scaleFactor
            if (pinchAccumulator > SCALE_STEP || pinchAccumulator < 1f / SCALE_STEP) {
                nudgeZoom(log2(pinchAccumulator.toDouble()))
                pinchAccumulator = 1f
            }
        }
    }

    /** Drag in pan mode. Convert the screen-space delta into a lat/lon
     *  shift using the latest snapshot's own projection — two unprojects
     *  (centre, and centre+delta) give the geographic offset for this
     *  drag, accounting for MapLibre's zoom, bearing and pitch exactly. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            if (!panMode) return@post
            val snap = snapshotter.latest ?: return@post
            val w = snap.bitmap.width
            val h = snap.bitmap.height
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
            drawFrame(canvas, container.width, container.height)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
                // Same race on the way out; nothing to recover.
            }
        }
    }

    private fun drawFrame(canvas: Canvas, w: Int, h: Int) {
        val dark = carContext.isDarkMode
        val bg = if (dark) BG_DARK else BG_LIGHT
        canvas.drawColor(bg)

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

        val layout = gaugeLayout(w, h)
        drawGaugePanel(canvas, layout, loc)
        drawHud(canvas, layout, h, dark)
        // Centre the banners on the safe area, not the raw screen, so they
        // track the host's chrome layout instead of hiding under it.
        val bannerLeft = layout.safe.left.toFloat().coerceIn(0f, w.toFloat())
        val bannerRight = layout.safe.right.toFloat().coerceIn(bannerLeft, w.toFloat())
        drawRallyPanel(canvas, bannerLeft, bannerRight, h, dark)
        drawNavStatus(canvas, bannerLeft, bannerRight, h, dark)
    }

    /** Gauge geometry within the host safe area (so it tracks the host's
     *  chrome positions): the speed/odometer dial centre-left, the optional
     *  power dial top-left, the merged compass/G-meter (smaller) bottom-right. */
    private class GaugeLayout(
        val speedCell: RectF,
        val powerCell: RectF?,
        val compassCell: RectF,
        val safe: Rect,
        val pad: Float,
    )

    private fun gaugeLayout(w: Int, h: Int): GaugeLayout {
        val safe = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val margin = h * 0.02f
        // Dials hug the PHYSICAL screen edges. The safe area is reserved
        // for the host's own cards (turn card, ETA, strips) and during
        // navigation it collapses to the screen CENTRE — anchoring the
        // dials to it pushed them into the middle. The host cards are
        // translucent overlays the map shows through, and the puck lives
        // low-centre (look-ahead camera), so the corners are ours.
        val left = margin
        val right = w - margin
        val top = margin
        val bottom = h - margin

        // Headline dials (speed, power): square, capped so two never crowd.
        val mainSide = min((bottom - top) * 0.38f, (right - left) * 0.30f)
        // The dynamics dial is the supporting instrument — a touch smaller.
        val miniSide = mainSide * 0.82f

        // Top-left corner: power/energy (OBD only).
        val powerCell = if (obdConnected) RectF(
            left, top, left + mainSide, top + mainSide,
        ) else null
        // Left edge, vertically centred below the power dial: speed + odo.
        val speedTop = (powerCell?.bottom?.plus(margin)) ?: top
        val speedCy = (speedTop + bottom) / 2f
        val speedCell = RectF(
            left, speedCy - mainSide / 2f,
            left + mainSide, speedCy + mainSide / 2f,
        )
        // Bottom-right corner: compass / G-meter.
        val compassCell = RectF(
            right - miniSide, bottom - miniSide,
            right, bottom,
        )
        return GaugeLayout(speedCell, powerCell, compassCell, safe, margin)
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
        bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
        val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + panelH)
        canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubblePaint)
        canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubbleStrokePaint)
        hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
        canvas.drawText(text, cx, top + panelH - pad * 0.85f, hudTextPaint)
    }

    /** Corner instrument dials over the full-bleed map: speed + trip
     *  odometer bottom-left, the EV power/energy dial top-left (only with
     *  a live OBD2 feed), and the merged compass/G-meter dynamics dial
     *  bottom-right. Each is a self-contained RetroDial housing, so they
     *  sit on the map without a backing panel. Compass rotates by GPS
     *  course (never the magnetometer in a car); the G-plot reads the
     *  phone's fused accelerometer. */
    private fun drawGaugePanel(canvas: Canvas, layout: GaugeLayout, loc: Location?) {
        // Centre-left: speed + trip odometer dial.
        instruments.drawSpeedDial(canvas, layout.speedCell, loc, speedLimitKmh, odometerM / 1000.0)

        // Top-left: power / energy — only with a live OBD2 feed behind it.
        layout.powerCell?.let { cell ->
            // On-arrival estimates: range left after the remaining drive,
            // SOC scaled by the same ratio. Only when navigating AND a
            // range is known.
            val range = rangeRemainingKm
            val drive = navRemainingKm
            val arrivalRangeKm: Float?
            val arrivalSocPct: Float?
            if (range != null && range > 0f && drive != null) {
                val leftKm = range - drive.toFloat()
                arrivalRangeKm = leftKm
                arrivalSocPct = batterySocPct?.let { (it * (leftKm / range)).coerceIn(0f, it) }
            } else {
                arrivalRangeKm = null
                arrivalSocPct = null
            }
            instruments.drawEnergyDial(
                canvas, cell, dispPowerKw,
                batterySocPct, rangeRemainingKm, arrivalRangeKm, arrivalSocPct,
            )
        }

        // Bottom-right: merged compass / G-meter (supporting dial).
        instruments.drawCompassGMeter(
            canvas, layout.compassCell,
            smoothedBearingDeg, hasBearing, loc, gForceTrail.toList(),
        )
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
                Math.pow(2.0, currentZoom)
            LOOK_AHEAD_FRACTION * mpp * h
        } else 0.0
        val brRad = Math.toRadians(bearing)
        val camLat = loc.latitude + panLatOffset +
            lookAheadM * cos(brRad) / 111_320.0
        val camLon = loc.longitude + panLonOffset +
            lookAheadM * Math.sin(brRad) / (111_320.0 * cos(Math.toRadians(loc.latitude)))
        val pitch = if (tilted) CAR_PITCH_DEG else 0.0
        // 3D building extrusions only in the dedicated 3D mode; the other
        // two show flat footprints (the snapshotter hides the
        // `building-3d` layer).
        snapshotter.setBuildings3d(viewMode == MapViewMode.TILTED_3D)
        val cam = org.maplibre.android.camera.CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(camLat, camLon))
            .zoom(currentZoom)
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
        canvas.drawBitmap(bmp, mapLeft, 0f, layerPaint)
        if (dark) canvas.drawRect(mapLeft, 0f, mapLeft + mapW, h.toFloat(), darkScrimPaint)

        // Overlays projected through the snapshot. While following, the
        // puck is pinned to a fixed on-screen anchor (the map scrolls
        // under it) so it never jitters against a snapshot that lags the
        // moving camera.
        val following = hasBearing && !panMode
        drawProjectedRoute(canvas, snap, mapLeft, loc)
        drawProjectedBreadcrumb(canvas, snap, mapLeft)
        drawProjectedMarker(canvas, snap, mapLeft, loc, following)
    }

    private fun drawProjectedRoute(
        canvas: Canvas,
        snap: org.maplibre.android.snapshotter.MapSnapshot,
        mapLeft: Float,
        loc: Location,
    ) {
        val path = routeAheadPath(navRoute, snap, mapLeft, loc) ?: return
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
        snap: org.maplibre.android.snapshotter.MapSnapshot,
        mapLeft: Float,
        loc: Location,
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
        val end = (startIdx + ROUTE_DRAW_POINTS).coerceAtMost(pts.size)
        if (end - startIdx < 2) return null
        val maxJump = snap.bitmap.height * 4f
        val path = Path()
        var started = false
        var lastX = 0f
        var lastY = 0f
        for (i in startIdx until end) {
            val p = pts[i]
            val pf = snap.pixelForLatLng(org.maplibre.android.geometry.LatLng(p[0], p[1]))
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
        return path
    }

    private fun drawProjectedBreadcrumb(
        canvas: Canvas,
        snap: org.maplibre.android.snapshotter.MapSnapshot,
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
        snap: org.maplibre.android.snapshotter.MapSnapshot,
        mapLeft: Float,
    ): Path? {
        if (pts == null || pts.size < 2) return null
        val step = (pts.size + MAX_PROJECTED_POINTS - 1) / MAX_PROJECTED_POINTS
        val path = Path()
        // A point far outside the view (or a tilted-horizon point) can
        // project to a wild pixel; a lineTo to it streaks a nonsensical
        // line across the surface. Break the path on any jump larger than
        // this many surface-heights and resume with a moveTo.
        val maxJump = snap.bitmap.height * 4f
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
        snap: org.maplibre.android.snapshotter.MapSnapshot,
        mapLeft: Float,
        loc: Location,
        following: Boolean,
    ) {
        // While following: pin the puck to a fixed screen anchor (centre-X,
        // low — matching the look-ahead camera) so it stays rock-steady as
        // the map scrolls beneath it, even when the snapshot lags the
        // camera. When panning (free camera): project the real position.
        val x: Float
        val y: Float
        if (following) {
            x = mapLeft + snap.bitmap.width / 2f
            y = snap.bitmap.height * PUCK_SCREEN_FRACTION
        } else {
            val pf = snap.pixelForLatLng(
                org.maplibre.android.geometry.LatLng(loc.latitude, loc.longitude),
            )
            x = mapLeft + pf.x
            y = pf.y
        }
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
    private fun drawHud(canvas: Canvas, layout: GaugeLayout, h: Int, dark: Boolean) {
        val inset = layout.safe
        val pad = h * 0.03f
        val unit = h * 0.13f

        // ── Outside-temp badge (OBD ambient), top-left — beside the power
        // dial when it's shown, otherwise at the safe edge ──
        ambientTempC?.let { c ->
            val txt = "%.0f°C".format(Locale.ROOT, c)
            hudTextPaint.textAlign = Paint.Align.LEFT
            hudTextPaint.textSize = unit * 0.36f
            val tx = layout.powerCell?.let { it.right + pad } ?: (inset.left + pad * 1.5f)
            val ty = inset.top + pad * 1.5f + hudTextPaint.textSize
            val tw = hudTextPaint.measureText(txt)
            bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
            val rect = RectF(tx - pad / 2, ty - hudTextPaint.textSize, tx + tw + pad / 2, ty + pad / 2)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bubblePaint)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bubbleStrokePaint)
            hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
            canvas.drawText(txt, tx, ty - hudTextPaint.textSize * 0.18f, hudTextPaint)
        }

        // ── Trip strip (recording distance · duration + REC dot), bottom,
        // to the right of the speed dial so the two never overlap ──
        val rec = recording as? RecordingState.Recording
        if (rec != null) {
            val stripText = "%.1f km   %s".format(
                Locale.ROOT,
                rec.distanceMetres / 1000.0,
                formatDuration(System.currentTimeMillis() - rec.startedAtMillis),
            )
            hudTextPaint.textAlign = Paint.Align.LEFT
            hudTextPaint.textSize = unit * 0.34f
            val sx = layout.speedCell.right + pad
            val sy = inset.bottom - pad * 1.5f
            val tw = hudTextPaint.measureText(stripText)
            val recDotSpace = unit * 0.55f
            bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
            val strip = RectF(
                sx - pad / 2,
                sy - unit * 0.42f,
                sx + tw + recDotSpace + pad / 2,
                sy + unit * 0.22f,
            )
            canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubblePaint)
            canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubbleStrokePaint)
            hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
            canvas.drawText(stripText, sx, sy, hudTextPaint)
            // Pulse-free REC dot — a steady red dot reads "recording"
            // without needing an animation loop.
            recDotPaint.color = if (rec.paused) REC_PAUSED else REC_ACTIVE
            canvas.drawCircle(sx + tw + recDotSpace / 2, sy - unit * 0.10f, unit * 0.13f, recDotPaint)
        }

        // ── OSM attribution (tile-policy requirement). Tiny, pinned just
        // above the bottom-right dynamics dial so the dial doesn't hide
        // it, and off the visible right edge. ──
        hudTextPaint.textAlign = Paint.Align.RIGHT
        hudTextPaint.textSize = h * 0.013f
        hudTextPaint.color = if (dark) HUD_MUTED_DARK else HUD_MUTED_LIGHT
        canvas.drawText(
            "© OpenStreetMap",
            inset.right.toFloat() - 2f,
            layout.compassCell.top - 4f,
            hudTextPaint,
        )
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
                bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                val rect = RectF(cx - panelW / 2, top, cx + panelW / 2, top + panelH)
                canvas.drawRoundRect(rect, pad, pad, bubblePaint)
                canvas.drawRoundRect(rect, pad, pad, bubbleStrokePaint)
                hudTextPaint.textSize = h * 0.14f
                hudTextPaint.color = deltaColor
                canvas.drawText(deltaText, cx, top + pad * 0.6f + h * 0.12f, hudTextPaint)
                hudTextPaint.isFakeBoldText = false
                hudTextPaint.textSize = h * 0.045f
                hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                canvas.drawText(subText, cx, top + panelH - pad, hudTextPaint)
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
                bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
                val rect = RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + panelH)
                canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubblePaint)
                canvas.drawRoundRect(rect, panelH / 2, panelH / 2, bubbleStrokePaint)
                hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
                canvas.drawText(text, cx, top + panelH - pad * 0.85f, hudTextPaint)
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
        /** Per-frame easing factor for needles + heading (0..1). */
        const val EASE = 0.25f
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
