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
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.ui.trails.MapnikBulkOk
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
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
 * magnetometer — see TripDashboardScreen.formatHeading for why) and
 * engages above a walking pace, holding the last rotation while
 * stationary so the map doesn't snap back to north at every red light.
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
    private var lastDrawnLocation: Location? = null

    /** Breadcrumb of the active recording as lat/lon pairs. Appended
     *  on a >3 m move, decimated 2:1 above [BREADCRUMB_CAP] so a long
     *  drive can't grow the per-frame path cost without bound. */
    private val breadcrumb = ArrayList<DoubleArray>()
    private var breadcrumbMinStepM = 3f
    private var wasRecording = false

    private var zoom = DEFAULT_ZOOM
    /** Smoothed course-over-ground driving the heading-up rotation. */
    private var smoothedBearingDeg = 0f
    private var hasBearing = false
    /** Accumulated pinch factor from [onScale]; crossing ±[SCALE_STEP]
     *  commits a zoom level. */
    private var pinchAccumulator = 1f

    private var renderPending = false

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
    }

    // ── Data in ────────────────────────────────────────────────────

    /** Feed the latest GNSS + recording state; repaints the surface. */
    fun update(gnss: GnssSnapshot, rec: RecordingState) {
        snapshot = gnss
        recording = rec

        val isRecording = rec is RecordingState.Recording
        if (isRecording && !wasRecording) {
            breadcrumb.clear()
            breadcrumbMinStepM = 3f
        }
        wasRecording = isRecording

        val loc = gnss.location
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
        scheduleRender()
    }

    fun zoomIn() = setZoom(zoom + 1)

    fun zoomOut() = setZoom(zoom - 1)

    /** Repaint with current state — e.g. after a day/night flip. */
    fun repaint() = scheduleRender()

    private fun setZoom(z: Int) {
        val clamped = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (clamped != zoom) {
            zoom = clamped
            scheduleRender()
        }
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
            if (pinchAccumulator > SCALE_STEP) {
                pinchAccumulator = 1f
                zoomIn()
            } else if (pinchAccumulator < 1f / SCALE_STEP) {
                pinchAccumulator = 1f
                zoomOut()
            }
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
        canvas.drawColor(if (dark) BG_DARK else BG_LIGHT)

        val loc = snapshot.location ?: lastDrawnLocation
        // Anchor: centre for north-up; pushed to the lower third when
        // heading-up so most of the map is the road ahead.
        val headingUp = hasBearing
        val ax = w / 2f
        val ay = if (headingUp) h * 0.66f else h / 2f

        if (loc != null) {
            val cx = lonToWorldX(loc.longitude, zoom)
            val cy = latToWorldY(loc.latitude, zoom)

            canvas.save()
            if (headingUp) canvas.rotate(-smoothedBearingDeg, ax, ay)
            drawTiles(canvas, ax, ay, cx, cy, w, h)
            if (dark) canvas.drawColor(TILE_DARK_SCRIM)
            drawBreadcrumb(canvas, ax, ay, cx, cy)
            canvas.restore()

            drawPositionMarker(canvas, ax, ay, headingUp)
        } else {
            drawWaitingForFix(canvas, w, h, dark)
        }

        drawHud(canvas, w, h, loc, dark)
    }

    private fun drawTiles(canvas: Canvas, ax: Float, ay: Float, cx: Double, cy: Double, w: Int, h: Int) {
        // Cover the rotated viewport: half the diagonal in every
        // direction from the anchor, padded by one tile.
        val half = hypot(w.toDouble(), h.toDouble()) / 2.0 + TILE_SIZE
        val n = 1 shl zoom
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
                    .getMapTile(MapTileIndex.getTileIndex(zoom, wrappedTx, ty))
                    ?: continue
                val left = (ax + (tx.toDouble() * TILE_SIZE - cx)).roundToInt()
                val top = (ay + (ty.toDouble() * TILE_SIZE - cy)).roundToInt()
                drawable.setBounds(left, top, left + TILE_SIZE, top + TILE_SIZE)
                drawable.draw(canvas)
            }
        }
    }

    private fun drawBreadcrumb(canvas: Canvas, ax: Float, ay: Float, cx: Double, cy: Double) {
        if (breadcrumb.size < 2) return
        val path = Path()
        breadcrumb.forEachIndexed { i, p ->
            val x = (ax + (lonToWorldX(p[1], zoom) - cx)).toFloat()
            val y = (ay + (latToWorldY(p[0], zoom) - cy)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Connect the decimated tail to the live position so the line
        // never visibly detaches from the marker.
        path.lineTo(ax, ay)
        canvas.drawPath(path, trailCasingPaint)
        canvas.drawPath(path, trailPaint)
    }

    private fun drawPositionMarker(canvas: Canvas, ax: Float, ay: Float, headingUp: Boolean) {
        canvas.drawCircle(ax, ay, MARKER_RADIUS + 4f, markerRingPaint)
        canvas.drawCircle(ax, ay, MARKER_RADIUS, markerFillPaint)
        // Chevron: up in heading-up mode (the map rotates instead);
        // rotated to the course otherwise.
        canvas.save()
        if (!headingUp && hasBearing) canvas.rotate(smoothedBearingDeg, ax, ay)
        val chevron = Path().apply {
            moveTo(ax, ay - MARKER_RADIUS * 0.62f)
            lineTo(ax - MARKER_RADIUS * 0.45f, ay + MARKER_RADIUS * 0.40f)
            lineTo(ax, ay + MARKER_RADIUS * 0.12f)
            lineTo(ax + MARKER_RADIUS * 0.45f, ay + MARKER_RADIUS * 0.40f)
            close()
        }
        canvas.drawPath(chevron, markerChevronPaint)
        canvas.restore()
    }

    private fun drawWaitingForFix(canvas: Canvas, w: Int, h: Int, dark: Boolean) {
        hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
        hudTextPaint.textSize = h * 0.05f
        hudTextPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            carContext.getString(be.appmire.gpsinfo.R.string.car_waiting_fix),
            w / 2f,
            h / 2f,
            hudTextPaint,
        )
    }

    private fun drawHud(canvas: Canvas, w: Int, h: Int, loc: Location?, dark: Boolean) {
        val inset = stableArea ?: visibleArea ?: Rect(0, 0, w, h)
        val pad = h * 0.03f

        // ── Speed bubble, bottom-left ──
        val r = min(w, h) * 0.13f
        val bx = inset.left + pad + r
        val by = inset.bottom - pad - r
        bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
        canvas.drawCircle(bx, by, r, bubblePaint)
        canvas.drawCircle(bx, by, r, bubbleStrokePaint)

        val speedText = if (loc != null && loc.hasSpeed()) {
            (loc.speed * 3.6f).roundToInt().toString()
        } else "—"
        hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
        hudTextPaint.textAlign = Paint.Align.CENTER
        hudTextPaint.textSize = r * 0.78f
        hudTextPaint.isFakeBoldText = true
        canvas.drawText(speedText, bx, by + r * 0.12f, hudTextPaint)
        hudTextPaint.isFakeBoldText = false
        hudTextPaint.textSize = r * 0.30f
        hudTextPaint.color = if (dark) HUD_MUTED_DARK else HUD_MUTED_LIGHT
        canvas.drawText("km/h", bx, by + r * 0.52f, hudTextPaint)

        // ── Bottom info strip, right of the bubble ──
        val rec = recording as? RecordingState.Recording
        val parts = mutableListOf<String>()
        if (loc != null && hasBearing) {
            val b = smoothedBearingDeg.roundToInt() % 360
            parts += "${cardinal(b)} $b°"
        }
        if (loc != null && loc.hasAltitude()) {
            parts += "▲ ${loc.altitude.roundToInt()} m"
        }
        if (rec != null) {
            parts += "%.1f km".format(Locale.ROOT, rec.distanceMetres / 1000.0)
            parts += formatDuration(System.currentTimeMillis() - rec.startedAtMillis)
        }
        if (parts.isNotEmpty()) {
            val stripText = parts.joinToString("   ")
            hudTextPaint.textAlign = Paint.Align.LEFT
            hudTextPaint.textSize = r * 0.34f
            val sx = bx + r + pad
            val sy = by + r * 0.12f
            val tw = hudTextPaint.measureText(stripText)
            val recDotSpace = if (rec != null) r * 0.55f else 0f
            bubblePaint.color = if (dark) BUBBLE_DARK else BUBBLE_LIGHT
            val strip = RectF(
                sx - pad / 2,
                sy - r * 0.42f,
                sx + tw + recDotSpace + pad / 2,
                sy + r * 0.22f,
            )
            canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubblePaint)
            canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, bubbleStrokePaint)
            hudTextPaint.color = if (dark) Color.WHITE else Color.BLACK
            canvas.drawText(stripText, sx, sy, hudTextPaint)
            if (rec != null) {
                // Pulse-free REC dot — a steady red dot reads
                // "recording" without needing an animation loop.
                recDotPaint.color = if (rec.paused) REC_PAUSED else REC_ACTIVE
                canvas.drawCircle(sx + tw + recDotSpace / 2, sy - r * 0.10f, r * 0.13f, recDotPaint)
            }
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

    // ── Web-Mercator helpers (slippy tiles, world pixels at [zoom]) ──

    private fun lonToWorldX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * TILE_SIZE * (1 shl zoom)

    private fun latToWorldY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * TILE_SIZE * (1 shl zoom)
    }

    private fun cardinal(deg: Int): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((deg + 22.5) / 45.0).toInt()) % 8]
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

    private companion object {
        const val TILE_SIZE = 256
        const val DEFAULT_ZOOM = 16
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 19
        const val MAX_MERCATOR_LAT = 85.05112878
        /** Above ~1.1 m/s (walking pace) course-over-ground is stable
         *  enough to rotate the map by. */
        const val MIN_HEADING_UP_SPEED_MPS = 1.1f
        const val SCALE_STEP = 1.30f
        const val BREADCRUMB_CAP = 4000
        const val MARKER_RADIUS = 26f

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
    }
}
