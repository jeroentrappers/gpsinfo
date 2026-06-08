package be.appmire.gpsinfo.car

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import be.appmire.gpsinfo.data.nav.MapLibreStyle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * Renders MapLibre *vector* maps onto the Android Auto projection
 * surface — where a normal MapLibre `MapView` can't go (the car's
 * Surface isn't part of any Android view hierarchy).
 *
 * The trick: a reusable [MapSnapshotter] renders the map off-screen
 * to a Bitmap for a given camera (centre + fractional zoom + bearing
 * + native pitch), which [CarMapRenderer] then blits onto the car
 * canvas. MapLibre does the tilt/rotation/labelling natively, and the
 * resulting [MapSnapshot] exposes [MapSnapshot.pixelForLatLng] so the
 * trail / route / marker overlays project exactly onto the bitmap —
 * no hand-rolled mercator math, no perspective matrix.
 *
 * One snapshot in flight at a time; [request] coalesces (drops the
 * request if the camera hasn't moved meaningfully or one is pending).
 * Snapshots arrive on the main thread, so the held [latest] is only
 * ever touched there, same as the renderer's draw loop.
 */
class CarMapSnapshotter(
    context: Context,
    private val onReady: () -> Unit,
) {
    private val appContext = context.applicationContext
    private var snapshotter: MapSnapshotter? = null
    private var width = 0
    private var height = 0
    private var inFlight = false

    /** Latest completed snapshot — bitmap + projection. */
    var latest: MapSnapshot? = null
        private set

    /** Camera the [latest] snapshot was rendered for (so overlay
     *  projection and the redraw share one source of truth). */
    var latestCam: CameraPosition? = null
        private set

    private var pendingCam: CameraPosition? = null
    private var lastRequestedCam: CameraPosition? = null
    private val handler = Handler(Looper.getMainLooper())

    /** (Re)create the snapshotter when the map area size changes. */
    private fun ensureSized(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (snapshotter != null && w == width && h == height) return
        MapLibre.getInstance(appContext)
        width = w
        height = h
        snapshotter?.cancel()
        snapshotter = MapSnapshotter(
            appContext,
            MapSnapshotter.Options(w, h)
                .withPixelRatio(1f)
                .withStyle(MapLibreStyle.LIBERTY)
                .withLogo(false),
        )
        // Force the next request to render even if the camera matches.
        lastRequestedCam = null
    }

    /**
     * Ask for a fresh snapshot at [cam] for a [w]×[h] map area. No-op
     * if an identical-enough camera is already rendered or pending.
     */
    fun request(w: Int, h: Int, cam: CameraPosition) {
        ensureSized(w, h)
        val snap = snapshotter ?: return
        if (!cameraMovedEnough(lastRequestedCam, cam)) return
        if (inFlight) {
            // Coalesce: remember only the freshest camera; it'll be
            // drained when the in-flight snapshot completes.
            pendingCam = cam
            return
        }
        lastRequestedCam = cam
        inFlight = true
        snap.setCameraPosition(cam)
        // MapSnapshotter.start() throws if a previous snapshot hasn't
        // settled. Guard it: on failure, stash as pending and retry.
        val ok = runCatching {
            snap.start({ result ->
                latest = result
                latestCam = cam
                inFlight = false
                onReady()
                // Drain a coalesced camera on the NEXT loop turn — the
                // native snapshotter isn't ready to restart synchronously
                // inside its own ready callback.
                drainPending()
            }, {
                inFlight = false
                drainPending()
            })
        }.isSuccess
        if (!ok) {
            inFlight = false
            pendingCam = cam
            handler.post { drainPending() }
        }
    }

    private fun drainPending() {
        val p = pendingCam ?: return
        pendingCam = null
        handler.post { request(width, height, p) }
    }

    /** Project a geographic point to a pixel in the snapshot bitmap. */
    fun pixelFor(lat: Double, lon: Double): PointF? =
        latest?.pixelForLatLng(LatLng(lat, lon))

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        pendingCam = null
        inFlight = false
        snapshotter?.cancel()
        snapshotter = null
        latest = null
    }

    private companion object {
        /** Re-render thresholds — below these the on-screen change
         *  isn't worth a snapshot. ~5 m of movement, 1.5° of heading,
         *  a hair of zoom. */
        const val MOVE_DEG = 0.00005
        const val BEARING_DEG = 1.5
        const val ZOOM_EPS = 0.05
        const val TILT_EPS = 0.5

        fun cameraMovedEnough(a: CameraPosition?, b: CameraPosition): Boolean {
            a ?: return true
            val at = a.target ?: return true
            val bt = b.target ?: return true
            return kotlin.math.abs(at.latitude - bt.latitude) > MOVE_DEG ||
                kotlin.math.abs(at.longitude - bt.longitude) > MOVE_DEG ||
                kotlin.math.abs(a.bearing - b.bearing) > BEARING_DEG ||
                kotlin.math.abs(a.zoom - b.zoom) > ZOOM_EPS ||
                kotlin.math.abs(a.tilt - b.tilt) > TILT_EPS
        }
    }
}
