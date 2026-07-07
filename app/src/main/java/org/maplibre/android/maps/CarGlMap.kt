@file:Suppress("PackageDirectoryMismatch")

/*
 * Lives in `org.maplibre.android.maps` on purpose.
 *
 * MapLibre's `NativeMapView` is public, but its `StateCallback` /
 * `StyleCallback` listener interfaces are package-private — the only clean
 * way to construct a `NativeMapView` (and thus drive a live GL map without a
 * `MapView` in a view hierarchy) is to sit in the same package. No
 * reflection, no forked AAR; just a shim compiled against the stock
 * 11.13.0 classes. The MapLibre version is pinned, so the (non-public but
 * stable) signatures this leans on don't drift underneath us.
 *
 * What this gives us that the `MapSnapshotter` path can't: MapLibre renders
 * the vector map *continuously, on the GPU, straight onto the Android Auto
 * projection Surface* — no off-screen bitmap, no per-frame blit, no
 * snapshot latency. The screen-space overlays (route, puck, traffic,
 * instrument cluster) are drawn by the existing Canvas code into a Bitmap
 * and composited on top as a single textured quad between the map's draw
 * and the buffer swap.
 */
package org.maplibre.android.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import android.view.View
import be.appmire.gpsinfo.car.CarMapPalette
import be.appmire.gpsinfo.car.MapProjector
import org.maplibre.android.MapLibre
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.renderer.MapRenderer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.maps.renderer.egl.EGLConfigChooser
import org.maplibre.android.maps.renderer.egl.EGLContextFactory
import org.maplibre.android.maps.renderer.egl.EGLWindowSurfaceFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.tile.TileOperation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

private const val TAG = "CarGlMap"

/**
 * Live MapLibre map rendered onto an arbitrary [Surface] (the Android Auto
 * projection surface), plus a hook to composite the app's own overlay
 * bitmap over each frame.
 *
 * Threading contract:
 *  - Construct + all camera / projection / style calls happen on the MAIN
 *    thread (mbgl's `NativeMapView` is single-threaded and owned there).
 *  - The renderer runs on its own EGL thread; only the [MapRenderer]
 *    surface callbacks and the overlay composite touch GL.
 *
 * @param onStyleReady invoked (main thread) once the style has loaded and
 *        the map is drawable.
 * @param onNeedRepaint invoked when mbgl itself wants a new frame (tiles
 *        arrived, a transition is running) so the host can re-tick even if
 *        the vehicle hasn't moved.
 */
class CarGlMap(
    context: Context,
    private val lightStyleUri: String,
    private val darkStyleUri: String,
    private val onStyleReady: () -> Unit,
    private val onNeedRepaint: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val renderer: CarGlMapRenderer
    private val nativeMap: NativeMapView

    /** Current surface size (px); drives projection + camera focal point. */
    private var width = 0
    private var height = 0

    var styleLoaded = false
        private set

    private var want3d = true
    private var applied3d: Boolean? = null
    private var paletteApplied = false
    /** Night mode: uses the dark style + night palette. */
    private var dark = false

    /** Switch between the day (light + warm palette) and night (dark style +
     *  night palette) map. Reloads the style, which self-heals the nav layers
     *  and re-applies the right palette. */
    fun setDark(darkMode: Boolean) {
        if (darkMode == dark) return
        dark = darkMode
        styleLoaded = false
        navLayersReady = false
        paletteApplied = false
        applied3d = null
        runCatching { nativeMap.setStyleUri(if (darkMode) darkStyleUri else lightStyleUri) }
    }

    // ── Surface lifecycle (main thread) ─────────────────────────────

    fun onSurfaceAvailable(surface: Surface, w: Int, h: Int) {
        width = w
        height = h
        nativeMap.resizeView(w, h)
        renderer.surfaceAvailable(surface, w, h)
    }

    fun onSurfaceResized(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        nativeMap.resizeView(w, h)
        renderer.surfaceResized(w, h)
    }

    fun onSurfaceDestroyed() {
        renderer.surfaceDestroyed()
    }

    fun destroy() {
        renderer.shutdown()
        runCatching { nativeMap.destroy() }
    }

    fun onLowMemory() = runCatching { nativeMap.onLowMemory() }.let {}

    // ── Per-frame drive (main thread) ───────────────────────────────

    /** Point the camera. Applied synchronously to the map transform, so a
     *  [currentProjector] taken right after reflects this exact camera. */
    fun setCamera(lat: Double, lon: Double, zoom: Double, bearingDeg: Double, pitchDeg: Double) {
        // The style's layers may only become available a few frames after the
        // URI is set, so keep trying to apply the day palette / 3D visibility
        // until it takes (cheap once done).
        if (styleLoaded && (!paletteApplied || applied3d != want3d)) applyStyleTweaks()
        val center = LatLng(lat, lon)
        nativeMap.setLatLng(center, 0)
        nativeMap.setZoom(zoom, PointF(width / 2f, height / 2f), 0)
        nativeMap.setBearing(bearingDeg, 0)
        nativeMap.setPitch(pitchDeg, 0)
    }

    /** Projection over the *current* camera. Only valid on the main thread
     *  (it calls into `NativeMapView`). Null until the surface has a size. */
    fun currentProjector(): MapProjector? {
        if (width <= 0 || height <= 0) return null
        return object : MapProjector {
            override val width: Int get() = this@CarGlMap.width
            override val height: Int get() = this@CarGlMap.height
            override fun pixelForLatLng(latLng: LatLng): PointF = nativeMap.pixelForLatLng(latLng)
            override fun latLngForPixel(point: PointF): LatLng = nativeMap.latLngForPixel(point)
        }
    }

    /** Hand the freshly-drawn overlay bitmap to the render thread and ask
     *  for a frame. The bitmap must not be mutated until the next present
     *  (the caller double-buffers). */
    fun present(overlay: Bitmap?) {
        renderer.present(overlay)
    }

    private var lastPadding: DoubleArray? = null

    /** Content padding [left, top, right, bottom] px — centres the camera
     *  target within the visible region (so the puck lands correctly on a
     *  ⅔/⅓ split). Guarded so it's a no-op when unchanged. */
    fun setContentPadding(left: Double, top: Double, right: Double, bottom: Double) {
        val p = doubleArrayOf(left, top, right, bottom)
        if (lastPadding?.contentEquals(p) == true) return
        lastPadding = p
        runCatching { nativeMap.setContentPadding(p) }
    }

    fun setBuildings3d(enabled: Boolean) {
        if (enabled == want3d) return
        want3d = enabled
        applied3d = null
        applyStyleTweaks()
    }

    // ── Style theming ───────────────────────────────────────────────

    private fun applyStyleTweaks() {
        if (!styleLoaded) return
        if (applied3d != want3d) {
            runCatching { nativeMap.getLayer(BUILDING_3D) }.getOrNull()?.setProperties(
                PropertyFactory.visibility(if (want3d) Property.VISIBLE else Property.NONE),
            )
            applied3d = want3d
        }
        if (!paletteApplied) {
            // May report not-ready for a few frames after the style URI is set;
            // retried from setCamera until it takes. Day vs night palette to
            // match the active style.
            val lookup = { id: String -> runCatching { nativeMap.getLayer(id) }.getOrNull() }
            paletteApplied = if (dark) CarMapPalette.applyNight(lookup) else CarMapPalette.applyDay(lookup)
        }
        ensureNavLayers()
    }

    // ── Native navigation layers (route + puck, rendered inside mbgl) ────
    // The route line and vehicle puck are real MapLibre layers so they render
    // in the map's own GPU pass — always visible (independent of the overlay
    // compositor) and cheap (no per-frame polyline projection into a bitmap).

    private var navLayersReady = false
    /** Last GeoJSON per nav source, re-applied after a style reload (which
     *  drops the sources) so the overlays survive without waiting for a
     *  change. Keyed by source id. */
    private val lastNavJson = HashMap<String, String>()
    private var lastPuckBearing = 0f
    private var lastPuckLat = Double.NaN
    private var lastPuckLon = Double.NaN

    private fun ensureNavLayers() {
        if (navLayersReady || !styleLoaded) return
        runCatching {
            addPuckImage()
            for (src in listOf(SRC_ALTS, SRC_TRAIL, SRC_TRAFFIC, SRC_ROUTE, SRC_PUCK)) {
                nativeMap.addSource(GeoJsonSource(src, lastNavJson[src] ?: EMPTY_FC))
            }
            // Bottom → top: alternatives, breadcrumb, traffic, route, puck.
            nativeMap.addLayer(
                LineLayer(LYR_ALTS, SRC_ALTS).withProperties(
                    lineColor(ALT), lineWidth(6f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            nativeMap.addLayer(
                LineLayer(LYR_TRAIL, SRC_TRAIL).withProperties(
                    lineColor(TRAIL), lineWidth(6f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            nativeMap.addLayer(
                LineLayer(LYR_TRAFFIC, SRC_TRAFFIC).withProperties(
                    lineColor(Expression.get("color")), lineWidth(7f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            // Point incidents (single-coordinate) render as coloured dots.
            nativeMap.addLayer(
                CircleLayer(LYR_TRAFFIC_PT, SRC_TRAFFIC).withProperties(
                    circleColor(Expression.get("color")), circleRadius(6f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(1.5f),
                ),
            )
            nativeMap.addLayer(
                LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
                    lineColor(ROUTE_CASING), lineWidth(13f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            nativeMap.addLayer(
                LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
                    lineColor(ROUTE), lineWidth(8f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            nativeMap.addLayer(
                SymbolLayer(LYR_PUCK, SRC_PUCK).withProperties(
                    iconImage(PUCK_IMG),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconAnchor(Property.ICON_ANCHOR_CENTER),
                    iconSize(1f),
                    iconRotate(lastPuckBearing),
                ),
            )
            navLayersReady = true
        }.onFailure { Log.w(TAG, "ensureNavLayers", it) }
    }

    private fun setNavGeoJson(sourceId: String, json: String) {
        ensureNavLayers()
        lastNavJson[sourceId] = json
        (runCatching { nativeMap.getSource(sourceId) as? GeoJsonSource }.getOrNull())?.setGeoJson(json)
    }

    /** Draw a chevron-in-disc puck bitmap and register it as a style image. */
    private fun addPuckImage() {
        val s = 72
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.34f
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        c.drawCircle(cx, cy, r + s * 0.06f, disc)
        disc.color = PUCK_FILL
        c.drawCircle(cx, cy, r, disc)
        val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        // Upward chevron (points to the direction of travel; iconRotate spins it).
        c.drawPath(
            Path().apply {
                moveTo(cx, cy - r * 0.55f)
                lineTo(cx - r * 0.5f, cy + r * 0.45f)
                lineTo(cx, cy + r * 0.18f)
                lineTo(cx + r * 0.5f, cy + r * 0.45f)
                close()
            },
            chevron,
        )
        val buf = ByteBuffer.allocate(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)
        nativeMap.addImages(arrayOf(Image(buf.array(), 1f, PUCK_IMG, s, s, false)))
    }

    /** Set the route polyline ([lat, lon] points); null/short clears it. */
    fun setRoute(points: List<DoubleArray>?) {
        setNavGeoJson(SRC_ROUTE, if (points == null || points.size < 2) EMPTY_FC else lineJson(points))
    }

    /** Place the vehicle puck (heading in degrees); [visible] false hides it.
     *  Called every frame, so skip native work when nothing moved. */
    fun setPuck(lat: Double, lon: Double, bearingDeg: Double, visible: Boolean) {
        ensureNavLayers()
        if (!visible) {
            if (!lastPuckLat.isNaN()) { setNavGeoJson(SRC_PUCK, EMPTY_FC); lastPuckLat = Double.NaN }
            return
        }
        val posChanged = lastPuckLat.isNaN() ||
            kotlin.math.abs(lat - lastPuckLat) > 1e-6 || kotlin.math.abs(lon - lastPuckLon) > 1e-6
        if (posChanged) {
            setNavGeoJson(SRC_PUCK, pointJson(lat, lon))
            lastPuckLat = lat
            lastPuckLon = lon
        }
        val b = bearingDeg.toFloat()
        if (kotlin.math.abs(b - lastPuckBearing) > 0.5f) {
            lastPuckBearing = b
            runCatching { (nativeMap.getLayer(LYR_PUCK) as? SymbolLayer)?.setProperties(iconRotate(b)) }
        }
    }

    /** Recorded breadcrumb trail ([lat, lon] points). */
    fun setTrail(points: List<DoubleArray>?) {
        setNavGeoJson(SRC_TRAIL, if (points == null || points.size < 2) EMPTY_FC else lineJson(points))
    }

    /** En-route alternatives (each a [lat, lon] polyline). */
    fun setAlternatives(routes: List<List<DoubleArray>>) {
        val feats = JSONArray()
        for (r in routes) {
            if (r.size < 2) continue
            val coords = JSONArray()
            for (p in r) coords.put(JSONArray().put(p[1]).put(p[0]))
            feats.put(feature(JSONObject().put("type", "LineString").put("coordinates", coords), null))
        }
        setNavGeoJson(SRC_ALTS, if (feats.length() == 0) EMPTY_FC else collection(feats))
    }

    /** Live traffic segments: each a [lon, lat] coordinate list + colour hex.
     *  Single-coordinate segments render as points via the same coloured line
     *  layer degenerating; callers pass ≥2 coords for visible incidents. */
    fun setTraffic(segments: List<Pair<List<DoubleArray>, String>>) {
        val feats = JSONArray()
        for ((coordsLonLat, colorHex) in segments) {
            if (coordsLonLat.isEmpty()) continue
            val geom = if (coordsLonLat.size == 1) {
                // Single-coordinate incident → a Point (drawn by the circle layer).
                JSONObject().put("type", "Point")
                    .put("coordinates", JSONArray().put(coordsLonLat[0][0]).put(coordsLonLat[0][1]))
            } else {
                val coords = JSONArray()
                for (p in coordsLonLat) coords.put(JSONArray().put(p[0]).put(p[1])) // already [lon, lat]
                JSONObject().put("type", "LineString").put("coordinates", coords)
            }
            feats.put(feature(geom, colorHex))
        }
        setNavGeoJson(SRC_TRAFFIC, if (feats.length() == 0) EMPTY_FC else collection(feats))
    }

    private fun lineJson(points: List<DoubleArray>): String {
        val coords = JSONArray()
        for (p in points) coords.put(JSONArray().put(p[1]).put(p[0])) // [lon, lat]
        return feature(JSONObject().put("type", "LineString").put("coordinates", coords), null).toString()
    }

    private fun pointJson(lat: Double, lon: Double): String =
        feature(JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)), null).toString()

    private fun feature(geometry: JSONObject, colorHex: String?): JSONObject {
        val props = JSONObject()
        if (colorHex != null) props.put("color", colorHex)
        return JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)
    }

    private fun collection(features: JSONArray): String =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()

    // ── NativeMapView callbacks ─────────────────────────────────────

    private val viewCallback = NativeMapView.ViewCallback { null }

    private val stateCallback = object : NativeMapView.StateCallback {
        // StyleCallback
        override fun onWillStartLoadingMap() {}
        override fun onDidFinishLoadingStyle() {
            styleLoaded = true
            // A (re)loaded style drops our sources/layers + palette edits, so
            // mark them for re-application.
            navLayersReady = false
            paletteApplied = false
            applied3d = null
            applyStyleTweaks()
            onStyleReady()
        }

        // StateCallback
        override fun onCameraWillChange(animated: Boolean) {}
        override fun onCameraIsChanging() {}
        override fun onCameraDidChange(animated: Boolean) {}
        override fun onDidFinishLoadingMap() {}
        override fun onDidFailLoadingMap(error: String?) {
            Log.w(TAG, "map load failed: $error")
        }
        override fun onWillStartRenderingFrame() {}
        override fun onDidFinishRenderingFrame(fully: Boolean, stats: RenderingStats?) {}
        override fun onWillStartRenderingMap() {}
        override fun onDidFinishRenderingMap(fully: Boolean) {}
        override fun onDidBecomeIdle() {}
        override fun onSourceChanged(sourceId: String?) {}
        override fun onStyleImageMissing(imageId: String?) {}
        override fun onCanRemoveUnusedStyleImage(imageId: String?): Boolean = true
        override fun onPreCompileShader(id: Int, type: Int, defines: String?) {}
        override fun onPostCompileShader(id: Int, type: Int, defines: String?) {}
        override fun onShaderCompileFailed(id: Int, type: Int, defines: String?) {}
        override fun onGlyphsLoaded(stack: Array<out String>?, a: Int, b: Int) {}
        override fun onGlyphsError(stack: Array<out String>?, a: Int, b: Int) {}
        override fun onGlyphsRequested(stack: Array<out String>?, a: Int, b: Int) {}
        override fun onTileAction(op: TileOperation?, x: Int, y: Int, z: Int, w: Int, o: Int, sourceId: String?) {}
        override fun onSpriteLoaded(a: String?, b: String?) {}
        override fun onSpriteError(a: String?, b: String?) {}
        override fun onSpriteRequested(a: String?, b: String?) {}
    }

    init {
        MapLibre.getInstance(appContext)
        renderer = CarGlMapRenderer(appContext, onNeedRepaint)
        // pixelRatio 1.0 matches the snapshotter path (car surface is 1:1);
        // crossSourceCollisions on = default label behaviour.
        val opts = NativeMapOptions(1f, true)
        nativeMap = NativeMapView(appContext, opts, viewCallback, stateCallback, renderer)
        nativeMap.setStyleUri(if (dark) darkStyleUri else lightStyleUri)
    }

    private companion object {
        const val BUILDING_3D = "building-3d"
        const val SRC_ROUTE = "gpsinfo-route"
        const val SRC_PUCK = "gpsinfo-puck"
        const val LYR_ROUTE_CASING = "gpsinfo-route-casing"
        const val LYR_ROUTE = "gpsinfo-route-line"
        const val LYR_PUCK = "gpsinfo-puck"
        const val PUCK_IMG = "gpsinfo-puck-img"
        const val SRC_TRAIL = "gpsinfo-trail"
        const val SRC_ALTS = "gpsinfo-alts"
        const val SRC_TRAFFIC = "gpsinfo-traffic"
        const val LYR_TRAIL = "gpsinfo-trail-line"
        const val LYR_ALTS = "gpsinfo-alts-line"
        const val LYR_TRAFFIC = "gpsinfo-traffic-line"
        const val LYR_TRAFFIC_PT = "gpsinfo-traffic-pt"
        const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
        const val ROUTE = "#3B82F6"
        const val ROUTE_CASING = "#1E3A8A"
        const val TRAIL = "#9CA3AF"
        const val ALT = "#6366F1"
        const val PUCK_FILL = 0xFF2563EB.toInt()
    }
}

/**
 * A [MapRenderer] with no `MapView` behind it: it owns a private EGL thread
 * that binds to whatever [Surface] we hand it and drives the native render +
 * overlay composite. `MapRenderer`'s surface hooks are `protected`, reached
 * here via the subclass.
 */
class CarGlMapRenderer(
    context: Context,
    onNeedRepaint: () -> Unit,
) : MapRenderer(context, context.cacheDir?.absolutePath) {

    private val dummyView by lazy { View(context) }
    private var refreshMode = RenderingRefreshMode.WHEN_DIRTY
    private var thread: CarGlRenderThread? = null

    init {
        thread = CarGlRenderThread(this, onNeedRepaint).also { it.start() }
    }

    // MapRenderer abstract surface — never attached to a hierarchy.
    override fun getView(): View = dummyView
    override fun setRenderingRefreshMode(mode: RenderingRefreshMode) { refreshMode = mode }
    override fun getRenderingRefreshMode(): RenderingRefreshMode = refreshMode

    // MapRendererScheduler — mbgl asks for frames / posts work through these.
    override fun requestRender() { thread?.requestRender() }
    override fun queueEvent(runnable: Runnable) { thread?.queueEvent(runnable) }
    override fun waitForEmpty() { thread?.waitForEmpty() }

    // Facade the CarGlMap calls (main thread) → forwarded to the GL thread.
    fun surfaceAvailable(surface: Surface, w: Int, h: Int) = thread?.surfaceAvailable(surface, w, h)
    fun surfaceResized(w: Int, h: Int) = thread?.surfaceResized(w, h)
    fun surfaceDestroyed() = thread?.surfaceDestroyed()
    fun present(overlay: Bitmap?) = thread?.present(overlay)
    fun shutdown() { thread?.shutdown(); thread = null }

    // Reach the protected native hooks from the GL thread.
    internal fun nativeSurfaceCreated(surface: Surface) = onSurfaceCreated(surface)
    internal fun nativeSurfaceChanged(w: Int, h: Int) = onSurfaceChanged(w, h)
    internal fun nativeSurfaceDestroyed() = onSurfaceDestroyed()
    internal fun nativeDrawFrame() = onDrawFrame()
}

/**
 * The EGL render thread. Structure mirrors MapLibre's own
 * `TextureViewRenderThread` / `MapLibreGLSurfaceView$GLThread`: a guarded
 * loop that (re)creates the EGL window surface when the target surface
 * changes, drains queued events, and renders on demand. EGL is set up with
 * MapLibre's *own* config/context/window-surface factories so the config we
 * pick matches exactly what the native renderer expects.
 */
internal class CarGlRenderThread(
    private val renderer: CarGlMapRenderer,
    private val onNeedRepaint: () -> Unit,
) : Thread("CarGlRenderThread") {

    private val lock = Object()
    private val eventQueue = ArrayDeque<Runnable>()

    // Guarded state (set by main thread).
    private var targetSurface: Surface? = null
    private var width = 0
    private var height = 0
    private var sizeDirty = false
    private var renderRequested = false
    private var overlay: Bitmap? = null
    private var shouldExit = false

    // Thread-local EGL state.
    private var egl: EGL10? = null
    private var display: EGLDisplay? = null
    private var config: EGLConfig? = null
    private var context: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var boundSurface: Surface? = null
    private val compositor = CarGlOverlay()

    private val configChooser = EGLConfigChooser()
    private val contextFactory = EGLContextFactory()
    private val windowSurfaceFactory = EGLWindowSurfaceFactory()

    // ── Main-thread API ─────────────────────────────────────────────

    fun surfaceAvailable(surface: Surface, w: Int, h: Int) = synchronized(lock) {
        targetSurface = surface
        width = w
        height = h
        sizeDirty = true
        renderRequested = true
        lock.notifyAll()
    }

    fun surfaceResized(w: Int, h: Int) = synchronized(lock) {
        width = w
        height = h
        sizeDirty = true
        renderRequested = true
        lock.notifyAll()
    }

    fun surfaceDestroyed() = synchronized(lock) {
        targetSurface = null
        lock.notifyAll()
    }

    fun present(bmp: Bitmap?) = synchronized(lock) {
        overlay = bmp
        renderRequested = true
        lock.notifyAll()
    }

    fun requestRender() = synchronized(lock) {
        renderRequested = true
        lock.notifyAll()
    }

    fun queueEvent(r: Runnable) = synchronized(lock) {
        eventQueue.addLast(r)
        lock.notifyAll()
    }

    fun waitForEmpty() = synchronized(lock) {
        while (eventQueue.isNotEmpty() && !shouldExit) lock.wait()
    }

    fun shutdown() {
        synchronized(lock) {
            shouldExit = true
            lock.notifyAll()
        }
        runCatching { join(1500) }
    }

    // ── The loop ────────────────────────────────────────────────────

    override fun run() {
        if (!initEgl()) {
            Log.e(TAG, "EGL init failed; live GL map unavailable")
            return
        }
        try {
            while (true) {
                var task: Runnable? = null
                var doRender = false
                var frameOverlay: Bitmap? = null
                var fw = 0
                var fh = 0
                var rebind = false
                var resize = false

                synchronized(lock) {
                    while (!shouldExit) {
                        if (eventQueue.isNotEmpty()) { task = eventQueue.removeFirst(); break }
                        if (targetSurface !== boundSurface) { rebind = true; break }
                        if (boundSurface != null && sizeDirty) { resize = true; sizeDirty = false; fw = width; fh = height; break }
                        if (boundSurface != null && renderRequested) {
                            renderRequested = false
                            doRender = true
                            frameOverlay = overlay
                            fw = width; fh = height
                            break
                        }
                        lock.wait()
                    }
                    if (shouldExit) return@synchronized
                }
                if (shouldExit) break

                when {
                    task != null -> task!!.run()
                    rebind -> rebindSurface()
                    resize -> applyResize(fw, fh)
                    doRender -> drawFrame(frameOverlay, fw, fh)
                }
            }
        } finally {
            teardownEgl()
        }
    }

    // ── EGL setup / teardown ────────────────────────────────────────

    private fun initEgl(): Boolean = runCatching {
        val e = (EGLContext.getEGL() as EGL10).also { egl = it }
        val d = e.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (d === EGL10.EGL_NO_DISPLAY) return false
        display = d
        val ver = IntArray(2)
        if (!e.eglInitialize(d, ver)) return false
        config = configChooser.chooseConfig(e, d)
        context = contextFactory.createContext(e, d, config)
        context != null && context !== EGL10.EGL_NO_CONTEXT
    }.getOrElse {
        Log.e(TAG, "initEgl", it); false
    }

    /** Bind (or unbind) the EGL window surface to match [targetSurface]. */
    private fun rebindSurface() {
        val e = egl ?: return
        val d = display ?: return
        // Tear down the old window surface + tell native.
        if (boundSurface != null) {
            runCatching { renderer.nativeSurfaceDestroyed() }
            destroyEglSurface()
            boundSurface = null
        }
        val target = synchronized(lock) { targetSurface }
        if (target == null) return
        val created = runCatching {
            val s = windowSurfaceFactory.createWindowSurface(e, d, config, target)
            if (s == null || s === EGL10.EGL_NO_SURFACE) {
                Log.e(TAG, "createWindowSurface returned no surface"); return
            }
            eglSurface = s
            if (!e.eglMakeCurrent(d, s, s, context)) {
                Log.e(TAG, "eglMakeCurrent failed: ${e.eglGetError()}"); return
            }
            true
        }.getOrElse { Log.e(TAG, "rebindSurface", it); false }
        if (!created) return
        boundSurface = target
        compositor.onContextCreated()
        val w = synchronized(lock) { width }
        val h = synchronized(lock) { height }
        runCatching { renderer.nativeSurfaceCreated(target) }
        runCatching { renderer.nativeSurfaceChanged(w, h) }
        // Native now wants to draw the first frame.
        requestRender()
    }

    private fun applyResize(w: Int, h: Int) {
        if (!makeCurrent()) return
        runCatching { renderer.nativeSurfaceChanged(w, h) }
        requestRender()
    }

    private fun drawFrame(overlayBmp: Bitmap?, w: Int, h: Int) {
        if (!makeCurrent()) return
        // 1. Native map into the current EGL surface (no swap — that's ours).
        runCatching { renderer.nativeDrawFrame() }
            .onFailure { Log.e(TAG, "nativeDrawFrame", it) }
        // 2. Composite the app overlay on top.
        if (overlayBmp != null && !overlayBmp.isRecycled) {
            runCatching { compositor.draw(overlayBmp, w, h) }
                .onFailure { Log.e(TAG, "overlay composite", it) }
        }
        // 3. Present.
        val e = egl ?: return
        val d = display ?: return
        val s = eglSurface ?: return
        if (!e.eglSwapBuffers(d, s)) {
            val err = e.eglGetError()
            if (err == EGL11_CONTEXT_LOST || err == EGL10.EGL_BAD_NATIVE_WINDOW) {
                Log.w(TAG, "swap failed ($err) — dropping surface")
                synchronized(lock) { targetSurface = null }
            }
        }
    }

    private fun makeCurrent(): Boolean {
        val e = egl ?: return false
        val d = display ?: return false
        val s = eglSurface ?: return false
        if (e.eglGetCurrentContext() === context && e.eglGetCurrentSurface(EGL10.EGL_DRAW) === s) return true
        return e.eglMakeCurrent(d, s, s, context)
    }

    private fun destroyEglSurface() {
        val e = egl ?: return
        val d = display ?: return
        e.eglMakeCurrent(d, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        eglSurface?.let { windowSurfaceFactory.destroySurface(e, d, it) }
        eglSurface = null
    }

    private fun teardownEgl() {
        runCatching {
            if (boundSurface != null) {
                runCatching { renderer.nativeSurfaceDestroyed() }
                destroyEglSurface()
                boundSurface = null
            }
            val e = egl
            val d = display
            if (e != null && d != null) {
                context?.let { contextFactory.destroyContext(e, d, it) }
                e.eglTerminate(d)
            }
        }
        context = null
        display = null
        egl = null
    }

    private companion object {
        // EGL_CONTEXT_LOST is 0x300E; not surfaced as a constant on EGL10.
        const val EGL11_CONTEXT_LOST = 0x300E
    }
}

/**
 * Draws a straight-alpha [Bitmap] as a full-surface textured quad over the
 * already-rendered map, in the current EGL context. Sets all the GL state it
 * needs each frame — the native renderer leaves state arbitrary.
 */
internal class CarGlOverlay {
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var texId = 0
    private var texW = 0
    private var texH = 0

    private val verts: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD); position(0) }

    fun onContextCreated() {
        // Force a rebuild against the (new) context.
        program = 0
        texId = 0
        texW = 0
        texH = 0
    }

    private fun ensureProgram() {
        if (program != 0) return
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAG)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "overlay program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
    }

    private fun ensureTexture(bmp: Bitmap) {
        if (texId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texW = 0; texH = 0
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        }
        if (bmp.width != texW || bmp.height != texH) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            texW = bmp.width
            texH = bmp.height
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
    }

    fun draw(bmp: Bitmap, w: Int, h: Int) {
        ensureProgram()
        // Drain any GL error mbgl left from its own render pass so the post-draw
        // check below reflects only our compositing (glGetError returns any
        // error since the last check, not just ours).
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* clear */ }
        // mbgl leaves GL state configured for its own pipeline after rendering
        // the map. The overlays vanished because of these leaked bindings:
        //  - a non-default FRAMEBUFFER → our quad went to an offscreen target;
        //  - a bound VERTEX ARRAY (ES3) → client-side arrays are invalid;
        //  - a bound ARRAY_BUFFER → glVertexAttribPointer treats our client
        //    FloatBuffer as a byte OFFSET into mbgl's VBO (draws nothing).
        // Reset every piece of state the quad depends on so it lands on the
        // visible surface.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        runCatching { android.opengl.GLES30.glBindVertexArray(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        // Straight-alpha "source-over" — shows both opaque overlays and
        // antialiased edges over the map.
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        ensureTexture(bmp)
        GLES20.glUniform1i(uTex, 0)

        verts.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, STRIDE, verts)
        verts.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, STRIDE, verts)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        // Diagnostic: surface why overlays may not appear on a head unit.
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) Log.w(TAG, "overlay draw glGetError=0x${Integer.toHexString(err)} program=$program tex=$texId ${texW}x$texH")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "shader compile: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    private companion object {
        const val STRIDE = 4 * 4 // 4 floats per vertex
        // x, y (clip space), u, v (texture). Tex V flipped so the bitmap's
        // top-left origin maps to GL's bottom-left surface origin.
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )
        const val VERT = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        const val FRAG = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
    }
}
