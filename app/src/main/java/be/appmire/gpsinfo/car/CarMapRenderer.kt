package be.appmire.gpsinfo.car

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.rally.RallyState
import be.appmire.gpsinfo.ui.trails.MapnikBulkOk
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.MapTileIndex

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
    /** 2.5D perspective on/off — flipped from the map action strip. */
    private var tilted = true

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

    /** Offscreen layer the flat map is drawn into before the
     *  perspective pass. Oversized: the tilt narrows the far field, so
     *  the viewport's top corners sample outside the screen rect. */
    private var mapLayer: Bitmap? = null

    private val camera = Camera()
    private val tiltMatrix = Matrix()
    private val instruments = CarInstruments()

    /** Instantaneous drive power in kW for the energy meter — null
     *  until an OBD2 source feeds [updatePower]; the dial parks at 0
     *  with a dimmed readout meanwhile. */
    private var powerKw: Double? = null

    fun updatePower(kw: Double?) {
        powerKw = kw
        scheduleRender()
    }

    /** Repaint when an async tile download lands. */
    private val tileArrivedHandler = Handler(Looper.getMainLooper()) {
        scheduleRender()
        true
    }

    private val tileProvider: MapTileProviderBasic by lazy {
        // The car session can be the first thing that runs in this
        // process (phone app never opened), so MainActivity's osmdroid
        // config may not have happened. Same app-private paths as
        // MainActivity → shared tile cache. Idempotent by construction.
        val osmConfig = Configuration.getInstance()
        val appContext = carContext.applicationContext
        osmConfig.userAgentValue = appContext.packageName
        osmConfig.osmdroidBasePath = File(appContext.filesDir, "osmdroid").apply { mkdirs() }
        osmConfig.osmdroidTileCache = File(appContext.cacheDir, "osmdroid/tiles").apply { mkdirs() }
        MapTileProviderBasic(appContext, MapnikBulkOk).also {
            it.tileRequestCompleteHandlers.add(tileArrivedHandler)
        }
    }

    init {
        sessionLifecycle.addObserver(this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        tileProvider.tileRequestCompleteHandlers.remove(tileArrivedHandler)
        tileProvider.detach()
        mapLayer?.recycle()
        mapLayer = null
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

        val loc = gnss.location?.let { withDerivedMotion(it, lastDrawnLocation) }
        if (loc != null) {
            lastDrawnLocation = loc
            // Course-over-ground low-pass: 35% of the shortest angular
            // delta per fix — smooths jitter without lagging real turns.
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > MIN_HEADING_UP_SPEED_MPS) {
                var delta = loc.bearing - smoothedBearingDeg
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                smoothedBearingDeg = (smoothedBearingDeg + delta * 0.35f + 360f) % 360f
                hasBearing = true
            }
            if (isRecording) appendBreadcrumb(loc)
        }
        stepAutoZoom(loc)
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

    fun toggleTilt() {
        tilted = !tilted
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

    /** Drag in pan mode. The host hands us screen-space deltas
     *  (previous − current); map them through the fractional-zoom
     *  scale and the heading-up rotation into a lat/lon offset. The
     *  tilt foreshortening is deliberately ignored — near-field pan
     *  speed is right and the far field just pans a bit faster. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        mainHandler.post {
            if (!panMode) return@post
            val loc = snapshot.location ?: lastDrawnLocation ?: return@post
            val tileZoom = floor(currentZoom).toInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
            val scale = 2.0.pow(currentZoom - tileZoom)
            // Screen → world: undo the canvas rotation (heading-up).
            val b = if (hasBearing) Math.toRadians(smoothedBearingDeg.toDouble()) else 0.0
            val wx = (distanceX * cos(b) - distanceY * kotlin.math.sin(b)) / scale
            val wy = (distanceX * kotlin.math.sin(b) + distanceY * cos(b)) / scale
            val worldSize = TILE_SIZE * (1 shl tileZoom)
            val latRad = Math.toRadians(
                (loc.latitude + panLatOffset).coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
            )
            panLonOffset += wx * 360.0 / worldSize
            panLatOffset -= wy * 360.0 * cos(latRad) / worldSize
            panLatOffset = panLatOffset.coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
            panLonOffset = panLonOffset.coerceIn(-MAX_PAN_DEG, MAX_PAN_DEG)
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

        // lastDrawnLocation carries the latest fix with derived motion
        // (see withDerivedMotion) — prefer it over the raw snapshot.
        val loc = lastDrawnLocation ?: snapshot.location

        // Split layout: instrument column on the left third, live map
        // on the right two thirds.
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val columnW = w * INSTRUMENT_COLUMN_FRACTION
        drawInstrumentColumn(canvas, columnW, h, inset, loc)

        val mapLeft = columnW
        val mapW = (w - columnW).toInt()
        // Anchor: centre only in flat north-up mode. Heading-up or
        // tilted, the marker sits near the bottom edge so nearly the
        // whole map is the road ahead.
        val headingUp = hasBearing
        val ax = mapLeft + mapW / 2f
        val ay = if (headingUp || tilted) h * ANCHOR_FRACTION else h / 2f

        canvas.save()
        canvas.clipRect(mapLeft, 0f, w.toFloat(), h.toFloat())
        if (loc != null) {
            // Free camera while panning: the map centres on the
            // dragged-to point; the marker always draws in-layer at
            // the vehicle's true position.
            val camLat = loc.latitude + panLatOffset
            val camLon = loc.longitude + panLonOffset
            if (tilted) {
                drawTiltedMap(canvas, mapW, h, ax, ay, camLat, camLon, loc, headingUp, dark, bg)
            } else {
                canvas.save()
                drawMapLayer(canvas, ax, ay, w, h, camLat, camLon, loc, headingUp, dark)
                canvas.restore()
            }
        } else {
            drawWaitingForFix(canvas, mapLeft, w, h, dark)
        }
        drawHud(canvas, mapLeft, w, h, dark)
        drawRallyPanel(canvas, mapLeft, w, h, dark)
        canvas.restore()
    }

    /** Left third: speed dial / gimballed compass / energy meter,
     *  stacked inside the host's stable area (the bottom system rail
     *  would otherwise eat half the energy dial). The energy needle
     *  parks at 0 until an OBD2 power source exists — the dial itself
     *  is the id.dash design. */
    private fun drawInstrumentColumn(
        canvas: Canvas,
        columnW: Float,
        h: Int,
        inset: Rect,
        loc: Location?,
    ) {
        instruments.drawColumnBackground(canvas, columnW, h.toFloat())
        // The stable area is conservative (sized around the action
        // strips on the map side); the visible area is what actually
        // matters for the left column — maximize the dials within it.
        val column = visibleArea ?: inset
        val top = column.top.toFloat()
        val bottom = column.bottom.toFloat().coerceAtMost(h.toFloat())
        val cellH = (bottom - top) / 3f
        instruments.drawSpeedDial(
            canvas, RectF(0f, top, columnW, top + cellH), loc,
        )
        instruments.drawCompass(
            canvas,
            RectF(0f, top + cellH, columnW, top + 2 * cellH),
            smoothedBearingDeg,
            hasBearing,
            loc,
        )
        instruments.drawEnergyDial(
            canvas, RectF(0f, top + 2 * cellH, columnW, bottom), powerKw,
        )
    }

    /** Flat map → oversized offscreen layer → perspective draw. The
     *  margins exist because rotateX narrows the far field: the
     *  viewport's top corners sample map content from outside the
     *  screen rect. A haze gradient hides the layer's far edge. */
    private fun drawTiltedMap(
        canvas: Canvas,
        w: Int,
        h: Int,
        ax: Float,
        ay: Float,
        camLat: Double,
        camLon: Double,
        loc: Location,
        headingUp: Boolean,
        dark: Boolean,
        bg: Int,
    ) {
        // [w] is the MAP AREA width; [ax] is absolute on the surface
        // (offset by the instrument column). The offscreen layer is
        // map-local — translate between the two when blitting.
        val mapLeft = ax - w / 2f
        val mx = (w * TILT_MARGIN_X).toInt()
        val mt = (h * TILT_MARGIN_TOP).toInt()
        val layer = obtainMapLayer(w + 2 * mx, h + mt)
        layer.eraseColor(bg)
        val layerCanvas = Canvas(layer)
        drawMapLayer(
            layerCanvas,
            ax = mx + (ax - mapLeft),
            ay = mt + ay,
            w = layer.width,
            h = layer.height,
            camLat = camLat,
            camLon = camLon,
            loc = loc,
            headingUp = headingUp,
            dark = dark,
        )

        // Perspective: rotate the map plane about the horizontal axis
        // through the anchor. Camera distance scales with the surface
        // so the foreshortening looks the same on every head unit.
        camera.save()
        camera.setLocation(0f, 0f, -(h * TILT_CAMERA_DISTANCE) / 72f)
        camera.rotateX(TILT_DEG)
        camera.getMatrix(tiltMatrix)
        camera.restore()
        tiltMatrix.preTranslate(-ax, -ay)
        tiltMatrix.postTranslate(ax, ay)

        canvas.save()
        canvas.concat(tiltMatrix)
        canvas.drawBitmap(layer, mapLeft - mx, -mt.toFloat(), layerPaint)
        canvas.restore()

        // Haze the horizon so the layer's far edge never shows as a
        // hard line. Shader is cached per (height, palette).
        ensureHazePaint(h, bg)
        canvas.drawRect(mapLeft, 0f, mapLeft + w, h * HAZE_DEPTH, hazePaint)
    }

    private fun obtainMapLayer(lw: Int, lh: Int): Bitmap {
        val existing = mapLayer
        if (existing != null && existing.width == lw && existing.height == lh) return existing
        existing?.recycle()
        return Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { mapLayer = it }
    }

    private var hazeKey = 0L
    private fun ensureHazePaint(h: Int, bg: Int) {
        val key = h.toLong() shl 32 or (bg.toLong() and 0xFFFFFFFFL)
        if (key == hazeKey) return
        hazeKey = key
        hazePaint.shader = LinearGradient(
            0f, 0f, 0f, h * HAZE_DEPTH,
            bg, bg and 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
    }

    /** The flat (untilted) map: tiles + dark scrim + breadcrumb, with
     *  heading-up rotation and fractional-zoom scaling applied around
     *  the anchor. Draws onto whatever canvas it's given — the surface
     *  directly (top-down mode) or the offscreen layer (tilt mode). */
    private fun drawMapLayer(
        canvas: Canvas,
        ax: Float,
        ay: Float,
        w: Int,
        h: Int,
        camLat: Double,
        camLon: Double,
        loc: Location,
        headingUp: Boolean,
        dark: Boolean,
    ) {
        val tileZoom = floor(currentZoom).toInt().coerceIn(MIN_ZOOM.toInt(), MAX_ZOOM.toInt())
        val scale = 2.0.pow(currentZoom - tileZoom).toFloat()
        val cx = lonToWorldX(camLon, tileZoom)
        val cy = latToWorldY(camLat, tileZoom)
        // The vehicle's own layer position — equals the anchor while
        // following, drifts off-anchor while panned.
        val vehicleX = (ax + (lonToWorldX(loc.longitude, tileZoom) - cx)).toFloat()
        val vehicleY = (ay + (latToWorldY(loc.latitude, tileZoom) - cy)).toFloat()

        canvas.save()
        if (headingUp) canvas.rotate(-smoothedBearingDeg, ax, ay)
        canvas.scale(scale, scale, ax, ay)
        drawTiles(canvas, ax, ay, cx, cy, w, h, tileZoom, scale)
        if (dark) canvas.drawColor(TILE_DARK_SCRIM)
        drawBreadcrumb(canvas, vehicleX, vehicleY, cx, cy, ax, ay, tileZoom, scale)
        drawInLayerMarker(canvas, vehicleX, vehicleY, scale)
        canvas.restore()
    }

    /** Vehicle marker drawn inside the (rotated/scaled/tilted) map
     *  layer, so it lies down with the perspective like everything
     *  else on the road. The chevron rotates to the course in world
     *  space: combined with the heading-up canvas rotation it still
     *  points up. While panned the marker simply sits wherever the
     *  vehicle's true position projects. */
    private fun drawInLayerMarker(canvas: Canvas, x: Float, y: Float, scale: Float) {
        val r = MARKER_RADIUS / scale
        canvas.drawCircle(x, y, r + 4f / scale, markerRingPaint)
        canvas.drawCircle(x, y, r, markerFillPaint)
        canvas.save()
        if (hasBearing) canvas.rotate(smoothedBearingDeg, x, y)
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

    private fun drawTiles(
        canvas: Canvas,
        ax: Float,
        ay: Float,
        cx: Double,
        cy: Double,
        w: Int,
        h: Int,
        tileZoom: Int,
        scale: Float,
    ) {
        // Cover the rotated+scaled viewport: half the diagonal in every
        // direction from the anchor (in pre-scale units), padded by one
        // tile.
        val half = hypot(w.toDouble(), h.toDouble()) / (2.0 * scale) + TILE_SIZE
        val n = 1 shl tileZoom
        val minTx = floor((cx - half) / TILE_SIZE).toInt()
        val maxTx = floor((cx + half) / TILE_SIZE).toInt()
        val minTy = floor((cy - half) / TILE_SIZE).toInt().coerceAtLeast(0)
        val maxTy = floor((cy + half) / TILE_SIZE).toInt().coerceAtMost(n - 1)
        for (tx in minTx..maxTx) {
            // Wrap longitude so the map keeps tiling across the
            // antimeridian.
            val wrappedTx = ((tx % n) + n) % n
            for (ty in minTy..maxTy) {
                val drawable = tileProvider
                    .getMapTile(MapTileIndex.getTileIndex(tileZoom, wrappedTx, ty))
                    ?: continue
                val left = (ax + (tx.toDouble() * TILE_SIZE - cx)).roundToInt()
                val top = (ay + (ty.toDouble() * TILE_SIZE - cy)).roundToInt()
                drawable.setBounds(left, top, left + TILE_SIZE, top + TILE_SIZE)
                drawable.draw(canvas)
            }
        }
    }

    private fun drawBreadcrumb(
        canvas: Canvas,
        vehicleX: Float,
        vehicleY: Float,
        cx: Double,
        cy: Double,
        ax: Float,
        ay: Float,
        tileZoom: Int,
        scale: Float,
    ) {
        if (breadcrumb.size < 2) return
        val path = Path()
        breadcrumb.forEachIndexed { i, p ->
            val x = (ax + (lonToWorldX(p[1], tileZoom) - cx)).toFloat()
            val y = (ay + (latToWorldY(p[0], tileZoom) - cy)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Connect the decimated tail to the live vehicle position so
        // the line never visibly detaches from the marker.
        path.lineTo(vehicleX, vehicleY)
        // Counter the fractional-zoom canvas scale so stroke widths
        // stay constant on screen.
        trailCasingPaint.strokeWidth = 15f / scale
        trailPaint.strokeWidth = 9f / scale
        canvas.drawPath(path, trailCasingPaint)
        canvas.drawPath(path, trailPaint)
    }

    private fun drawWaitingForFix(canvas: Canvas, mapLeft: Float, w: Int, h: Int, dark: Boolean) {
        hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
        hudTextPaint.textSize = h * 0.05f
        hudTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            carContext.getString(be.appmire.gpsinfo.R.string.car_waiting_fix),
            (mapLeft + w) / 2f,
            h / 2f,
            hudTextPaint,
        )
    }

    /** Map-area HUD. Speed/heading/altitude moved to the instrument
     *  column — what remains here is the trip strip (distance,
     *  duration, REC dot) and the OSM attribution. */
    private fun drawHud(canvas: Canvas, mapLeft: Float, w: Int, h: Int, dark: Boolean) {
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val pad = h * 0.03f
        val unit = min(w - mapLeft.toInt(), h) * 0.13f

        // ── Trip strip, bottom-left of the map ──
        val rec = recording as? RecordingState.Recording
        if (rec != null) {
            val stripText = "%.1f km   %s".format(
                Locale.ROOT,
                rec.distanceMetres / 1000.0,
                formatDuration(System.currentTimeMillis() - rec.startedAtMillis),
            )
            hudTextPaint.textAlign = Paint.Align.LEFT
            hudTextPaint.textSize = unit * 0.34f
            val sx = mapLeft + pad * 1.5f
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

        // ── OSM attribution, bottom-right (tile-policy requirement) ──
        hudTextPaint.textAlign = Paint.Align.RIGHT
        hudTextPaint.textSize = h * 0.022f
        hudTextPaint.color = if (dark) HUD_MUTED_DARK else HUD_MUTED_LIGHT
        canvas.drawText(
            "© OpenStreetMap",
            inset.right - pad,
            inset.bottom - pad,
            hudTextPaint,
        )
    }

    /** Regularity-test panel, top-centre: the early/late delta is THE
     *  number during an RT, so it gets the biggest type on the screen.
     *  Armed shows a one-line "waiting for the marshal" banner. */
    private fun drawRallyPanel(canvas: Canvas, mapLeft: Float, w: Int, h: Int, dark: Boolean) {
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val pad = h * 0.03f
        val mapCx = (mapLeft + w) / 2f
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

    // ── Web-Mercator helpers (slippy tiles, world pixels at zoom) ──

    private fun lonToWorldX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * TILE_SIZE * (1 shl zoom)

    private fun latToWorldY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * TILE_SIZE * (1 shl zoom)
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
    /** FILTER_BITMAP so the perspective resample stays smooth. */
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hazePaint = Paint()

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.0
        const val MAX_MERCATOR_LAT = 85.05112878

        /** Speed-adaptive zoom: glide between these levels as ground
         *  speed goes 0 → [ZOOM_FAST_KMH]. */
        const val ZOOM_STANDSTILL = 17.5
        const val ZOOM_FAST = 13.8
        const val ZOOM_FAST_KMH = 120.0
        const val ZOOM_STEP_PER_TICK = 0.18
        const val ZOOM_BIAS_RANGE = 3.0

        /** 2.5D tilt geometry. The far field samples the layer
         *  hyperbolically — at distance D (units of h) and tilt θ, a
         *  screen point y above the anchor reads the layer at
         *  y·D/(D·cosθ − y·sinθ), which blows up toward the horizon
         *  (D·cotθ). These values are solved so the layer's top margin
         *  covers the *entire* screen: with θ=50°, D=2.4 the screen
         *  top (0.82·h above the anchor) needs ≈2.15·h of layer →
         *  margin 1.35·h. Cranking θ up or D down reintroduces the
         *  empty band at the top — recompute before touching. */
        const val TILT_DEG = 50f
        const val TILT_MARGIN_X = 0.50f
        const val TILT_MARGIN_TOP = 1.35f
        const val TILT_CAMERA_DISTANCE = 2.4f
        const val HAZE_DEPTH = 0.22f
        /** Marker height when heading-up or tilted: near the bottom,
         *  the screen above is the road ahead. */
        const val ANCHOR_FRACTION = 0.82f
        /** Left instrument column (speed/compass/energy) width. */
        const val INSTRUMENT_COLUMN_FRACTION = 1f / 3f
        /** Pan clamp (≈±55 km) — keeps a runaway drag from scrolling
         *  to Nullarbor and requesting tiles all the way there. */
        const val MAX_PAN_DEG = 0.5

        /** Above ~1.1 m/s (walking pace) course-over-ground is stable
         *  enough to rotate the map by. */
        const val MIN_HEADING_UP_SPEED_MPS = 1.1f
        const val SCALE_STEP = 1.15f
        const val BREADCRUMB_CAP = 4000
        // Slightly smaller than the old anchored overlay — the marker
        // now lives inside the tilted layer, where the perspective
        // already gives it presence in the near field.
        const val MARKER_RADIUS = 21f

        const val BG_DARK = 0xFF11151A.toInt()
        const val BG_LIGHT = 0xFFE8E8E3.toInt()
        const val TILE_DARK_SCRIM = 0x66000000
        const val TRAIL_COLOR = 0xFF00B0FF.toInt()
        const val TRAIL_CASING = 0xCC003C5C.toInt()
        const val MARKER_FILL = 0xFF1A73E8.toInt()
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
