package be.appmire.gpsinfo.ui.livemap

import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.model.NavigationTarget
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Circle
import org.maplibre.android.plugins.annotation.CircleManager
import org.maplibre.android.plugins.annotation.CircleOptions
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions

/**
 * MapLibre Native vector-map host. Renders OpenStreetMap *vector*
 * tiles (no API key, OpenFreeMap by default) through MapLibre's GL
 * engine — smooth fractional zoom + rotation, crisp at any scale, and
 * a fraction of the data of raster tiles.
 *
 * Replaces the osmdroid raster MapView on the live-map screen while
 * keeping the same inputs, so the surrounding overlays/controls are
 * untouched. The trail, route and markers are drawn as MapLibre
 * annotation-plugin layers (Circle/Line managers) — no bundled icon
 * assets needed.
 *
 * The heavy [MapView] lifecycle (onCreate/onStart/.../onDestroy) is
 * driven from the host Compose lifecycle; [MapLibre.getInstance] must
 * run before the view is constructed.
 */
@Composable
fun MapLibreMapHost(
    loc: Location?,
    follow: Boolean,
    headingUp: Boolean,
    gpsBearingDeg: Float?,
    recording: RecordingState,
    navigationTarget: NavigationTarget?,
    /** Active offline turn-by-turn route (NavigationController), drawn
     *  as the primary route line; null when not navigating. */
    tbtRoute: List<be.appmire.gpsinfo.data.nav.RoutePoint>? = null,
    /** Bumped by the caller to force a one-shot recenter on the user. */
    recenterTrigger: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { MapHolder() }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { view ->
            view.onCreate(null)
            view.getMapAsync { map -> holder.onMapReady(view, map) }
        }
    }

    // Bridge the Android MapView lifecycle to Compose's.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
            holder.release()
        }
    }

    var lastRecenter = remember { intArrayOf(recenterTrigger) }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = {
            val forceRecenter = recenterTrigger != lastRecenter[0]
            lastRecenter[0] = recenterTrigger
            holder.update(
                loc, follow || forceRecenter, headingUp, gpsBearingDeg,
                recording, navigationTarget, tbtRoute,
            )
        },
    )
}

/**
 * Owns the MapLibreMap + its annotation managers and the long-lived
 * annotation objects (user dot, destination dot, trail line, route
 * line), mutating them per GNSS fix. Everything is null until the
 * style finishes loading; [update] is a no-op until then.
 */
private class MapHolder {
    private var map: MapLibreMap? = null
    private var circles: CircleManager? = null
    private var lines: LineManager? = null

    private var userDot: Circle? = null
    private var destDot: Circle? = null
    private var trailLine: Line? = null
    private var routeLine: Line? = null
    private var tbtLine: Line? = null

    private var seeded = false
    private val trailPoints = ArrayList<LatLng>()
    private var lastRoutePointCount = -1
    private var lastTbtPointCount = -1

    fun onMapReady(view: MapView, map: MapLibreMap) {
        this.map = map
        map.setStyle(Style.Builder().fromUri(STYLE_URI)) { style ->
            // Managers must outlive the style; LineManager added first
            // so the trail/route draw beneath the position dots.
            lines = LineManager(view, map, style)
            circles = CircleManager(view, map, style)
        }
    }

    fun update(
        loc: Location?,
        follow: Boolean,
        headingUp: Boolean,
        gpsBearingDeg: Float?,
        recording: RecordingState,
        navigationTarget: NavigationTarget?,
        tbtRoute: List<be.appmire.gpsinfo.data.nav.RoutePoint>?,
    ) {
        val map = map ?: return
        val circles = circles ?: return
        val lines = lines ?: return
        val l = loc ?: return
        val here = LatLng(l.latitude, l.longitude)

        // User position dot.
        if (userDot == null) {
            userDot = circles.create(
                CircleOptions().withLatLng(here).withCircleRadius(7f)
                    .withCircleColor("#1A73E8").withCircleStrokeColor("#FFFFFF")
                    .withCircleStrokeWidth(2.5f),
            )
        } else {
            userDot!!.latLng = here
            circles.update(userDot)
        }

        // Camera: follow + heading-up rotation.
        if (follow) {
            val bearing = if (headingUp && gpsBearingDeg != null) gpsBearingDeg.toDouble() else 0.0
            val target = if (!seeded) {
                seeded = true
                CameraPosition.Builder().target(here).zoom(15.5).bearing(bearing).build()
            } else {
                CameraPosition.Builder().target(here).bearing(bearing).build()
            }
            map.cameraPosition = target
        }

        // Live recording trail.
        val rec = recording as? RecordingState.Recording
        if (rec != null) {
            if (trailPoints.isEmpty() || trailPoints.last() != here) trailPoints.add(here)
            if (trailLine == null) {
                trailLine = lines.create(
                    LineOptions().withLatLngs(trailPoints).withLineColor("#E67635")
                        .withLineWidth(4f),
                )
            } else {
                trailLine!!.latLngs = ArrayList(trailPoints)
                lines.update(trailLine)
            }
        } else if (trailPoints.isNotEmpty()) {
            trailPoints.clear()
            trailLine?.let { lines.delete(it) }
            trailLine = null
        }

        // Destination marker + route line.
        if (navigationTarget != null) {
            val dest = LatLng(navigationTarget.targetLatDeg, navigationTarget.targetLonDeg)
            if (destDot == null) {
                destDot = circles.create(
                    CircleOptions().withLatLng(dest).withCircleRadius(8f)
                        .withCircleColor("#E53935").withCircleStrokeColor("#FFFFFF")
                        .withCircleStrokeWidth(2.5f),
                )
            } else {
                destDot!!.latLng = dest
                circles.update(destDot)
            }
            if (navigationTarget is NavigationTarget.Route) {
                if (lastRoutePointCount != navigationTarget.points.size) {
                    lastRoutePointCount = navigationTarget.points.size
                    val pts = navigationTarget.points.map { LatLng(it.latDeg, it.lonDeg) }
                    routeLine?.let { lines.delete(it) }
                    routeLine = lines.create(
                        LineOptions().withLatLngs(pts).withLineColor("#79C2FF")
                            .withLineWidth(5f),
                    )
                }
            } else {
                routeLine?.let { lines.delete(it); routeLine = null; lastRoutePointCount = -1 }
            }
        } else {
            destDot?.let { circles.delete(it); destDot = null }
            routeLine?.let { lines.delete(it); routeLine = null; lastRoutePointCount = -1 }
        }

        // Offline turn-by-turn route (NavigationController) — the
        // primary route line, orange + thick so it reads as "the way".
        if (tbtRoute != null && tbtRoute.size >= 2) {
            if (lastTbtPointCount != tbtRoute.size) {
                lastTbtPointCount = tbtRoute.size
                val pts = tbtRoute.map { LatLng(it.lat, it.lon) }
                tbtLine?.let { lines.delete(it) }
                tbtLine = lines.create(
                    LineOptions().withLatLngs(pts).withLineColor("#E67635").withLineWidth(6f),
                )
            }
        } else if (tbtLine != null) {
            lines.delete(tbtLine!!)
            tbtLine = null
            lastTbtPointCount = -1
        }
    }

    fun release() {
        circles?.onDestroy()
        lines?.onDestroy()
        circles = null
        lines = null
        map = null
    }

    private companion object {
        val STYLE_URI = MapLibreStyle.LIBERTY
    }
}

/** Shared MapLibre style URLs — used by the live map, the car
 *  snapshotter and the offline-region downloader so all three cache
 *  against the same style. OpenFreeMap: free OSM vector, no API key,
 *  no usage limits, self-hostable. */
object MapLibreStyle {
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
}
