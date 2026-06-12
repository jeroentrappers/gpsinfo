package be.appmire.gpsinfo.ui.livemap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import be.appmire.gpsinfo.car.MapViewMode
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.nav.MapLibreStyle
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
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

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
    /** Map presentation: flat north-up, 2.5D heading-up with flat
     *  building footprints, or 2.5D heading-up with 3D extrusions. */
    viewMode: MapViewMode,
    gpsBearingDeg: Float?,
    recording: RecordingState,
    navigationTarget: NavigationTarget?,
    /** Active offline turn-by-turn route (NavigationController), drawn
     *  as the primary route line; null when not navigating. */
    tbtRoute: List<be.appmire.gpsinfo.data.nav.RoutePoint>? = null,
    /** Bumped by the caller to force a one-shot recenter on the user. */
    recenterTrigger: Int,
    /** Use OpenFreeMap's dark style instead of Liberty. */
    darkMap: Boolean = false,
    /** Declutter the base map (hide POIs / minor place + name labels) —
     *  on while navigating so the road ahead reads clearly. */
    simplified: Boolean = false,
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
                loc, follow || forceRecenter, viewMode, gpsBearingDeg,
                recording, navigationTarget, tbtRoute, darkMap, simplified,
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
    private var view: MapView? = null
    private var style: Style? = null
    private var circles: CircleManager? = null
    private var lines: LineManager? = null
    private var symbols: SymbolManager? = null

    /** Which style URI is currently loaded, and whether a (re)load is in
     *  flight — the style is swapped at runtime when the theme changes. */
    private var loadedUri: String? = null
    private var styleLoading = false

    @Volatile
    private var wantDark = false

    /** Last applied declutter state, so we only walk the layers on change. */
    private var lastSimplified: Boolean? = null

    /** What we last pushed to the style's `building-3d` layer, so the
     *  visibility is only set when the mode actually flips (and re-set
     *  once the style finishes loading). */
    private var applied3d: Boolean? = null

    private var userPuck: Symbol? = null
    private var lastBearing = 0f
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
        this.view = view
        // Hide MapLibre's logo + attribution (i) icon — we surface the
        // OSM/OpenFreeMap credit elsewhere (About) instead of on the map.
        map.uiSettings.apply {
            isLogoEnabled = false
            isAttributionEnabled = false
        }
        loadStyle(MapLibreStyle.forDark(wantDark))
    }

    /** (Re)load a style and rebuild the annotation managers on top of it.
     *  Called initially and again whenever the theme flips the style —
     *  a style swap drops all annotations, so the long-lived objects are
     *  nulled and recreated by the next [update]. */
    private fun loadStyle(uri: String) {
        val map = map ?: return
        val view = view ?: return
        styleLoading = true
        // The old annotations belong to the outgoing style; invalidate them.
        circles = null
        lines = null
        symbols = null
        style = null
        userPuck = null
        destDot = null
        trailLine = null
        routeLine = null
        tbtLine = null
        lastRoutePointCount = -1
        lastTbtPointCount = -1
        applied3d = null
        lastSimplified = null
        map.setStyle(Style.Builder().fromUri(uri)) { style ->
            this.style = style
            // Managers must outlive the style; LineManager added first
            // so the trail/route draw beneath the position markers.
            lines = LineManager(view, map, style)
            circles = CircleManager(view, map, style)
            // Navigation puck: a chevron in a translucent blue disc that
            // rotates to the course (icon rotation aligned to the map, so
            // heading-up keeps it pointing up the screen).
            style.addImage(PUCK_IMAGE, navPuckBitmap())
            symbols = SymbolManager(view, map, style).apply {
                iconAllowOverlap = true
                iconIgnorePlacement = true
                iconRotationAlignment = Property.ICON_ROTATION_ALIGNMENT_MAP
            }
            // OpenFreeMap's dark style is near-black with low-contrast
            // roads; brighten the road network + water so features read.
            if (uri == MapLibreStyle.DARK) tuneDarkStyle(style)
            loadedUri = uri
            styleLoading = false
        }
    }

    /** Re-skin OpenFreeMap's near-black dark style into a Waze-like night
     *  map: a blue-slate background + land, muted parks, dark-blue water,
     *  slightly-lighter buildings, blue-grey roads with thin darker
     *  casings, and soft light labels. Applied per layer by type + id so
     *  it survives missing/renamed layers (best-effort, runCatching). */
    private fun tuneDarkStyle(style: Style) {
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            runCatching {
                when (layer) {
                    is BackgroundLayer ->
                        layer.setProperties(PropertyFactory.backgroundColor(NIGHT_BG))

                    is SymbolLayer ->
                        layer.setProperties(
                            PropertyFactory.textColor(NIGHT_TEXT),
                            PropertyFactory.textHaloColor(NIGHT_TEXT_HALO),
                            PropertyFactory.textHaloWidth(1.2f),
                        )

                    is LineLayer ->
                        if (isRoad(id)) {
                            val casing = id.contains("casing") || id.contains("outline")
                            layer.setProperties(
                                PropertyFactory.lineColor(if (casing) NIGHT_ROAD_CASING else NIGHT_ROAD),
                            )
                        }

                    is FillLayer -> {
                        val color = when {
                            id.contains("water") -> NIGHT_WATER
                            id.contains("park") || id.contains("wood") || id.contains("forest") ||
                                id.contains("grass") || id.contains("golf") || id.contains("cemetery") ||
                                id.contains("vegetation") || id.contains("scrub") -> NIGHT_GREEN
                            id.contains("building") -> NIGHT_BUILDING
                            else -> NIGHT_LAND // landcover / landuse / earth / etc.
                        }
                        layer.setProperties(PropertyFactory.fillColor(color))
                    }
                }
            }
        }
    }

    private fun isRoad(id: String): Boolean =
        id.startsWith("highway") || id.startsWith("road") || id.contains("transportation") ||
            id.startsWith("bridge") || id.startsWith("tunnel") || id.startsWith("street")

    /** Declutter the base map for navigation: hide POI icons/labels,
     *  minor place labels and aeroways so the route reads clearly. Keeps
     *  road/place/water names — those help while driving. Toggled by
     *  id-substring so it survives style differences and missing layers. */
    private fun applySimplify(on: Boolean) {
        val style = style ?: return
        val vis = if (on) Property.NONE else Property.VISIBLE
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            val clutter = id.startsWith("poi") ||
                id.startsWith("aeroway") ||
                id.contains("village") || id.contains("hamlet") ||
                id.contains("suburb") || id.contains("neighbo")
            if (clutter) runCatching { layer.setProperties(PropertyFactory.visibility(vis)) }
        }
    }

    fun update(
        loc: Location?,
        follow: Boolean,
        viewMode: MapViewMode,
        gpsBearingDeg: Float?,
        recording: RecordingState,
        navigationTarget: NavigationTarget?,
        tbtRoute: List<be.appmire.gpsinfo.data.nav.RoutePoint>?,
        darkMap: Boolean,
        simplified: Boolean,
    ) {
        wantDark = darkMap
        val map = map ?: return

        // Swap the base style when the theme flips. A style reload drops
        // all annotations, so they're rebuilt on the following frame.
        val desiredUri = MapLibreStyle.forDark(darkMap)
        if (styleLoading) return
        if (desiredUri != loadedUri) {
            loadStyle(desiredUri)
            return
        }

        val circles = circles ?: return
        val lines = lines ?: return

        // Declutter for navigation when requested (only walks layers on
        // change).
        if (lastSimplified != simplified) {
            applySimplify(simplified)
            lastSimplified = simplified
        }

        // Tilt is on for both 2.5D modes; 3D building extrusions only in
        // the dedicated 3D mode (the other two show flat footprints).
        val headingUp = viewMode != MapViewMode.FLAT
        applyBuildings3d(viewMode == MapViewMode.TILTED_3D)

        val l = loc ?: return
        val here = LatLng(l.latitude, l.longitude)

        // User position puck — chevron in a translucent blue disc,
        // rotated to the course. Map-aligned, so heading-up keeps it
        // pointing up the screen; north-up shows the travel direction.
        val puckRotate = gpsBearingDeg ?: lastBearing
        lastBearing = puckRotate
        symbols?.let { sym ->
            if (userPuck == null) {
                userPuck = sym.create(
                    SymbolOptions().withLatLng(here).withIconImage(PUCK_IMAGE)
                        .withIconRotate(puckRotate).withIconSize(1f),
                )
            } else {
                userPuck!!.latLng = here
                userPuck!!.iconRotate = puckRotate
                sym.update(userPuck)
            }
        }

        // Camera: follow + heading-up rotation. Heading-up also tilts
        // into a 2.5D driving perspective (north-up stays flat top-down)
        // and offsets the puck toward the bottom so the road ahead is
        // clear (top camera padding pushes the centred target down).
        if (follow) {
            val bearing = if (headingUp && gpsBearingDeg != null) gpsBearingDeg.toDouble() else 0.0
            val pitch = if (headingUp) HEADING_UP_PITCH_DEG else 0.0
            val topPad = if (headingUp) map.height * PUCK_BOTTOM_PADDING_FRAC else 0.0
            val builder = CameraPosition.Builder()
                .target(here).bearing(bearing).tilt(pitch)
                .padding(0.0, topPad, 0.0, 0.0)
            if (!seeded) {
                seeded = true
                builder.zoom(15.5)
            }
            map.cameraPosition = builder.build()
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
        style = null
        applied3d = null
    }

    /** Show/hide the style's `building-3d` extrusion layer to match the
     *  view mode — same lever the car snapshotter pulls. No-op until the
     *  style is loaded or when already in the requested state. */
    private fun applyBuildings3d(want: Boolean) {
        if (applied3d == want) return
        val style = style ?: return
        val layer = runCatching { style.getLayer(BUILDING_3D_LAYER) }.getOrNull() ?: return
        layer.setProperties(
            PropertyFactory.visibility(if (want) Property.VISIBLE else Property.NONE),
        )
        applied3d = want
    }

    /** Chevron-in-translucent-blue-disc navigation puck, drawn once and
     *  registered as a style image the SymbolManager references. */
    private fun navPuckBitmap(): Bitmap {
        val s = 96
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.40f
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99_1A73E8.toInt() })
        c.drawCircle(
            cx, cy, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.05f
                color = 0xFFFFFFFF.toInt()
            },
        )
        val chevron = Path().apply {
            moveTo(cx, cy - r * 0.55f)
            lineTo(cx - r * 0.45f, cy + r * 0.35f)
            lineTo(cx, cy + r * 0.12f)
            lineTo(cx + r * 0.45f, cy + r * 0.35f)
            close()
        }
        c.drawPath(chevron, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        return bmp
    }

    private companion object {
        /** OpenFreeMap style's extruded-buildings layer (Liberty + Dark). */
        const val BUILDING_3D_LAYER = "building-3d"
        /** Waze-like night palette layered over the dark style. */
        const val NIGHT_BG = "#28313D" // blue-slate background
        const val NIGHT_LAND = "#28313D" // land fills (match bg, no black patches)
        const val NIGHT_GREEN = "#2E3C34" // parks / woods
        const val NIGHT_WATER = "#1C2836" // dark blue water
        const val NIGHT_BUILDING = "#333D4B" // slightly lighter than land
        const val NIGHT_ROAD = "#5E6E84" // road fill, clearly lighter/blue
        const val NIGHT_ROAD_CASING = "#3C4858" // thin darker road outline
        const val NIGHT_TEXT = "#DCE3ED" // soft near-white labels
        const val NIGHT_TEXT_HALO = "#1A2230" // halo matched to bg
        /** Camera pitch in heading-up (driving) mode — 2.5D perspective. */
        const val HEADING_UP_PITCH_DEG = 50.0
        const val PUCK_IMAGE = "nav_puck"
        /** Top camera padding (fraction of map height) in heading-up mode
         *  — pushes the puck toward the bottom so the road ahead shows. */
        const val PUCK_BOTTOM_PADDING_FRAC = 0.45
    }
}
