package be.appmire.gpsinfo.ui.trails

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ExploreOff
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.IntentHelpers
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailMapScreen(
    vm: DashboardViewModel,
    trailId: String,
    onBack: () -> Unit,
    onTrackBackStarted: () -> Unit = onBack,
    onOpenPaceTargets: () -> Unit = {},
) {
    var trail by remember { mutableStateOf<Trail?>(null) }
    var selectedPoint by remember { mutableStateOf<TrailPoint?>(null) }
    var cursorPoint by remember { mutableStateOf<TrailPoint?>(null) }
    var showSimplify by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOfflineCache by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Live compass — only collected while heading-up is on, so a
    // north-up viewer doesn't pay the magnetometer subscription cost.
    val compass by vm.compass.collectAsStateWithLifecycle()
    val hrZoneConfig by vm.hrZoneConfig.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveFailedMsg = stringResource(R.string.trail_save_as_file_failed)

    // Reloading on `trailId` AND on a "version" counter lets us refresh
    // the in-memory trail after the user replaces it with a simplified
    // or renamed version. Otherwise the map would keep showing stale data.
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(trailId, version) { trail = vm.loadTrail(trailId) }

    // SAF launcher for "Save GPX file" — the user picks the location
    // and we stream the on-disk GPX into the chosen URI.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        val loaded = trail
        if (uri == null || loaded == null) return@rememberLauncherForActivityResult
        scope.launch {
            val src = vm.trailFile(loaded.id)
            val ok = src != null && runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
            }.isSuccess
            if (!ok) Toast.makeText(ctx, saveFailedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trail?.name ?: stringResource(R.string.screen_trails)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    val loaded = trail
                    if (loaded != null) {
                        IconButton(onClick = { headingUp = !headingUp }) {
                            Icon(
                                if (headingUp) Icons.Outlined.Explore else Icons.Outlined.ExploreOff,
                                contentDescription = stringResource(
                                    if (headingUp) R.string.trail_north_up else R.string.trail_heading_up
                                ),
                            )
                        }
                        IconButton(
                            onClick = {
                                vm.trailFile(loaded.id)?.let {
                                    IntentHelpers.shareGpx(ctx, it, loaded.name)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Share,
                                contentDescription = stringResource(R.string.trail_share),
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_rename)) },
                                    onClick = {
                                        menuExpanded = false
                                        showRename = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_simplify)) },
                                    onClick = {
                                        menuExpanded = false
                                        showSimplify = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_save_as_file)) },
                                    onClick = {
                                        menuExpanded = false
                                        saveLauncher.launch("${loaded.name}.gpx")
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_share_fit)) },
                                    onClick = {
                                        menuExpanded = false
                                        scope.launch {
                                            vm.trailFitFile(loaded.id)?.let {
                                                IntentHelpers.shareFit(ctx, it, loaded.name)
                                            } ?: Toast.makeText(
                                                ctx,
                                                ctx.getString(R.string.trail_share_fit_failed),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.IosShare, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tile_cache)) },
                                    onClick = {
                                        menuExpanded = false
                                        showOfflineCache = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_track_back)) },
                                    onClick = {
                                        menuExpanded = false
                                        vm.startTrackBack(loaded)
                                        onTrackBackStarted()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.NearMe, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trail_pace_targets)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenPaceTargets()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val containerWidthDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
        }
        // Narrow phones can't host the sun-compass overlay AND a usable
        // map at the same time — the overlay eats the top-left corner
        // and squeezes the visible map into a thin slab. Hide it under
        // 400 dp; tablets keep the overlay.
        val showSunCompassOverlay = containerWidthDp.value >= 400f
        // Wide-screen pivot: at ≥ 720 dp width, the scrubber + stats +
        // laps move out of an overlay on top of the map into a side
        // panel on the right. Frees the entire map for visual
        // inspection instead of obscuring its bottom half.
        val wide = containerWidthDp.value >= 720f
        val loaded = trail
        if (loaded == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else if (wide && loaded.points.size > 1) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxSize(),
                ) {
                    TrailMap(
                        trail = loaded,
                        cursorPoint = cursorPoint,
                        mapOrientationDeg = if (headingUp) -compass.magneticHeadingDeg else 0f,
                        onPointSelected = { selectedPoint = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (showSunCompassOverlay) {
                        TrailSunCompass(
                            trail = loaded,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp),
                        )
                    }
                }
                val sideScroll = rememberScrollState()
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxSize()
                        .verticalScroll(sideScroll)
                        .padding(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    TrailScrubberPanel(
                        trail = loaded,
                        unitSystem = state.unitSystem,
                        speedColor = MaterialTheme.colorScheme.primary,
                        elevationColor = MaterialTheme.colorScheme.tertiary,
                        onPointSelected = { cursorPoint = it },
                    )
                    TrailStatsCard(
                        trail = loaded,
                        unitSystem = state.unitSystem,
                        hrZoneConfig = hrZoneConfig,
                    )
                    LapsCard(laps = loaded.laps)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                TrailMap(
                    trail = loaded,
                    cursorPoint = cursorPoint,
                    mapOrientationDeg = if (headingUp) -compass.magneticHeadingDeg else 0f,
                    onPointSelected = { selectedPoint = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loaded.points.size > 1 && showSunCompassOverlay) {
                    TrailSunCompass(
                        trail = loaded,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                    )
                }
                if (loaded.points.size > 1) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                    ) {
                        TrailScrubberPanel(
                            trail = loaded,
                            unitSystem = state.unitSystem,
                            speedColor = MaterialTheme.colorScheme.primary,
                            elevationColor = MaterialTheme.colorScheme.tertiary,
                            onPointSelected = { cursorPoint = it },
                        )
                        TrailStatsCard(
                            trail = loaded,
                            unitSystem = state.unitSystem,
                            hrZoneConfig = hrZoneConfig,
                        )
                        LapsCard(laps = loaded.laps)
                    }
                }
            }
        }
    }

    val point = selectedPoint
    if (point != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedPoint = null },
            sheetState = sheetState,
        ) {
            TrailPointDetailsSheet(point = point, unitSystem = state.unitSystem)
        }
    }

    val toSimplify = trail
    if (showSimplify && toSimplify != null) {
        SimplifyTrailDialog(
            originalPoints = toSimplify.points,
            onDismiss = { showSimplify = false },
            onConfirm = { eps, replace ->
                showSimplify = false
                scope.launch {
                    val newId = vm.simplifyTrail(toSimplify.id, eps, replace)
                    if (replace && newId == toSimplify.id) {
                        // Same id, new contents — bump the version so
                        // the LaunchedEffect above reloads the trail.
                        version++
                    }
                    // For "save as copy" the user stays on the original.
                    // The new sibling shows up in the trails list automatically.
                }
            },
        )
    }

    val toCache = trail
    if (showOfflineCache && toCache != null) {
        val b = toCache.bounds
        if (b != null) {
            OfflineCacheDialog(
                bounds = b,
                onDismiss = { showOfflineCache = false },
            )
        } else {
            showOfflineCache = false
        }
    }

    val toRename = trail
    if (showRename && toRename != null) {
        RenameTrailDialog(
            initialName = toRename.name,
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                scope.launch {
                    if (vm.renameTrail(toRename.id, newName)) version++
                }
            },
        )
    }
}

@Composable
private fun RenameTrailDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trail_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.trail_save_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != initialName,
            ) {
                Text(stringResource(R.string.trail_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun TrailMap(
    trail: Trail,
    cursorPoint: TrailPoint?,
    mapOrientationDeg: Float,
    onPointSelected: (TrailPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Keep the latest callback reachable from the Marker click listeners
    // that we attach inside `factory` — without this they'd hold the
    // first reference forever.
    val currentOnSelected by rememberUpdatedState(onPointSelected)
    val currentCursor by rememberUpdatedState(cursorPoint)
    val currentOrientation by rememberUpdatedState(mapOrientationDeg)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(MapnikBulkOk)
                setMultiTouchControls(true)
                // Mapnik tiles only exist up to zoom 19. osmdroid lets the
                // user zoom much further by default, which produces blurry
                // upscaled tiles or blank space. Clamp to the tile source's
                // real range so pinch-zoom stops at meaningful detail.
                minZoomLevel = MapnikBulkOk.minimumZoomLevel.toDouble()
                maxZoomLevel = MapnikBulkOk.maximumZoomLevel.toDouble()
                // OSM Mapnik tiles are bright; invert them when the app
                // theme is dark so the map doesn't glow against the UI.
                if (isDarkTheme) {
                    overlayManager.tilesOverlay.setColorFilter(
                        ColorMatrixColorFilter(NIGHT_MATRIX)
                    )
                }
                renderTrail(trail) { currentOnSelected(it) }
            }
        },
        update = { mapView ->
            // Rotate the whole map view to match the compass heading.
            // mapOrientation is in degrees CCW, so we pass the
            // negative-compass-heading. 0f means "north up".
            if (mapView.mapOrientation != currentOrientation) {
                mapView.mapOrientation = currentOrientation
            }
            // Move the scrubber cursor marker without re-laying out the
            // polyline + markers. We tag it so we can find + remove it
            // on each update; failing to remove the old one stacks dots.
            val cursor = currentCursor
            mapView.overlays.removeAll { it is CursorMarker }
            if (cursor != null) {
                mapView.overlays.add(
                    CursorMarker(mapView).apply {
                        position = GeoPoint(cursor.latDeg, cursor.lonDeg)
                        icon = mapView.makeDotIcon(color = CURSOR_COLOR, sizeDp = 18)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setInfoWindow(null)
                        setOnMarkerClickListener { _, _ ->
                            currentOnSelected(cursor)
                            true
                        }
                    }
                )
                mapView.invalidate()
            } else {
                mapView.invalidate()
            }
        },
        onRelease = { mapView ->
            // osmdroid keeps background threads for tile downloading and
            // a SQLite cache handle; this releases both.
            mapView.onDetach()
        },
    )
}

/** Marker subclass used as a sentinel for the scrubber cursor — lets us
 *  find + replace just the cursor marker on slider drag without touching
 *  the rest of the overlays. */
private class CursorMarker(mapView: MapView) : Marker(mapView)

/**
 * Lays out the polyline, fits the camera to the trail bounds, and adds
 * one tappable marker for every Nth point. The polyline still uses every
 * point for visual fidelity — only the markers are thinned out so a
 * 10k-point trail doesn't flood the map.
 */
private fun MapView.renderTrail(
    trail: Trail,
    onSelected: (TrailPoint) -> Unit,
) {
    val pts = trail.points
    if (pts.isEmpty()) return

    val line = Polyline(this).apply {
        outlinePaint.color = TRAIL_COLOR
        outlinePaint.strokeWidth = 8f
        outlinePaint.isAntiAlias = true
        setPoints(pts.map { GeoPoint(it.latDeg, it.lonDeg) })
    }
    overlays.add(line)

    val step = (pts.size / 100).coerceAtLeast(1)
    val midIcon = makeDotIcon(color = DOT_COLOR, sizeDp = 8)
    val startIcon = makeDotIcon(color = START_COLOR, sizeDp = 14)
    val endIcon = makeDotIcon(color = END_COLOR, sizeDp = 14)
    for ((i, p) in pts.withIndex()) {
        val keep = i == 0 || i == pts.size - 1 || (i % step == 0)
        if (!keep) continue
        val marker = Marker(this).apply {
            position = GeoPoint(p.latDeg, p.lonDeg)
            icon = when (i) {
                0 -> startIcon
                pts.size - 1 -> endIcon
                else -> midIcon
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                onSelected(p)
                true
            }
            // We drive the details popup from Compose; suppress the
            // built-in InfoWindow that would otherwise overlay a bubble.
            setInfoWindow(null)
        }
        overlays.add(marker)
    }

    // zoomToBoundingBox requires the MapView to know its size, which
    // happens after layout — post() defers until then.
    trail.bounds?.let { b ->
        post {
            zoomToBoundingBox(
                BoundingBox(b.maxLat, b.maxLon, b.minLat, b.minLon),
                false,
                64,
            )
        }
    }
}

private fun MapView.makeDotIcon(color: Int, sizeDp: Int): Drawable {
    val density = resources.displayMetrics.density
    val px = (sizeDp * density).toInt().coerceAtLeast(2)
    return ShapeDrawable(OvalShape()).apply {
        intrinsicWidth = px
        intrinsicHeight = px
        paint.color = color
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
    }
}

/**
 * Mapnik tile source with the bulk-download policy flag removed.
 *
 * Stock [org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK] is
 * defined with `TileSourcePolicy.FLAG_NO_BULK`, which makes osmdroid throw
 * `TileSourcePolicyException` both in the `CacheManager(MapView)`
 * constructor and inside the download AsyncTask's pre-check. That's
 * faithful to the OSM tile usage policy, which discourages bulk
 * downloading — but the app offers a small, bounded "download this trail's
 * area" feature that's well within reasonable personal use, and the stock
 * source makes it crash on activation.
 *
 * We keep the same `getName()` ("Mapnik") so the on-disk cache
 * (`cache.db`) is shared with anything that originally cached against the
 * stock source. We retain the user-agent flags (we set a meaningful UA
 * via [be.appmire.gpsinfo.MainActivity]).
 */
internal val MapnikBulkOk: XYTileSource = XYTileSource(
    "Mapnik",
    0,
    19,
    256,
    ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/",
    ),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
            TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
    ),
)

// Trail colour palette. The rest of the app uses MaterialTheme colours,
// but osmdroid Paints take raw ints and we want the trail identity stable
// across themes.
private const val TRAIL_COLOR = 0xFFE67635.toInt()
private const val DOT_COLOR = 0xFFE67635.toInt()
private const val START_COLOR = 0xFF4CAF50.toInt()
private const val END_COLOR = 0xFFE53935.toInt()
internal const val CURSOR_COLOR = 0xFF7FCCFF.toInt()

// Standard "night-mode" matrix: invert RGB, leave alpha. Flips bright
// Mapnik tiles into a dark-friendly palette without a custom tile source.
private val NIGHT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)
