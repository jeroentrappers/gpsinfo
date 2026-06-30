package be.appmire.gpsinfo.ui.livemap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ExploreOff
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.nav.MapLibreStyle
import be.appmire.gpsinfo.data.nav.OfflineMapRepository
import kotlinx.coroutines.launch
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.data.nav.TurnCommand
import be.appmire.gpsinfo.data.nav.TurnHint
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.ui.activity.DetailLevel
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel
import java.util.Locale

/**
 * Real-time map with the live GPS position pinned and the most
 * relevant dashboard metrics overlaid. Auto-follows the user by
 * default (the "Follow" toggle in the top-right controls whether new
 * fixes recenter the map). Optional heading-up rotation rotates the
 * map so the direction-of-travel is at the top — uses GPS
 * course-over-ground (not the magnetometer) so the rotation stays
 * correct when the phone is in a cradle / cupholder.
 *
 * If a trail recording is active, the captured polyline draws live —
 * each accepted point extends the line so the user can watch the
 * track grow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMapScreen(
    vm: DashboardViewModel,
    /** Back navigation. Null when shown as a top-level tab (no back arrow). */
    onBack: (() -> Unit)? = null,
    /** Open the destination picker (search / saved places). */
    onOpenDestination: (() -> Unit)? = null,
    /** Drive & Navigate detail level: Simple shows just speed + guidance,
     *  Pro adds heading/altitude and the GPS fix/accuracy/sats bar. */
    detailLevel: DetailLevel = DetailLevel.PRO,
    /** Flip the detail level (Simple ⇄ Pro) in place. */
    onToggleDetail: (() -> Unit)? = null,
    /** Reports the immersive chrome's visibility up to the shell so the
     *  bottom navigation bar can hide/show in lock-step with the map's
     *  own top bar. */
    onChromeVisibilityChanged: (Boolean) -> Unit = {},
) {
    val pro = detailLevel == DetailLevel.PRO
    val state by vm.state.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val navigationTarget by vm.navigationTarget.collectAsStateWithLifecycle()
    // Offline turn-by-turn route (NavigationController) — drawn on the
    // map as the active route line so "Drive route (offline)" shows
    // its path, not just the bearing-style waypoint target.
    val navState by be.appmire.gpsinfo.data.nav.NavigationController.state
        .collectAsStateWithLifecycle()
    val tbtRoute = (navState as? be.appmire.gpsinfo.data.nav.NavigationController.NavState.Navigating)
        ?.route?.points
    val loc = state.gnss.location
    val unit = state.unitSystem

    // Live traffic incidents (TrafficController), drawn on the map. The
    // viewport follows the route while navigating, else the current fix.
    val traffic by be.appmire.gpsinfo.data.nav.TrafficController.incidents
        .collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        be.appmire.gpsinfo.data.nav.TrafficController.start()
    }
    androidx.compose.runtime.LaunchedEffect(tbtRoute, loc?.latitude, loc?.longitude) {
        if (tbtRoute != null && tbtRoute.size >= 2) {
            be.appmire.gpsinfo.data.nav.TrafficController.setRoute(
                tbtRoute.map { doubleArrayOf(it.lat, it.lon) },
            )
        } else {
            loc?.let { be.appmire.gpsinfo.data.nav.TrafficController.setLocation(it.latitude, it.longitude) }
        }
    }

    // OBD live feed — outside temp etc. Only active if the user configured
    // an adapter in the OBD Lab (startIfConfigured no-ops otherwise).
    val obdLive by be.appmire.gpsinfo.obd.ObdLiveController.state.collectAsStateWithLifecycle()
    val liveMapCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        be.appmire.gpsinfo.obd.ObdLiveController.startIfConfigured(liveMapCtx)
    }
    val outsideTempC = obdLive.ambientTempC?.takeIf { obdLive.connected }

    // Map styling: dark base style in dark mode, and a decluttered map
    // while navigating so the route reads clearly.
    val darkMap = when (state.themeOverride) {
        ThemeOverride.Dark -> true
        ThemeOverride.Light -> false
        ThemeOverride.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val navigating = navState is NavigationController.NavState.Navigating

    // Combined view: the gauge cluster overlaid on the live map (opt-in via
    // the shared cluster setting). When on, it replaces the simple speed dial
    // and is editable in place, per orientation, independently of the other
    // overlay surfaces.
    val clusterOn by vm.carOverlayCluster.collectAsStateWithLifecycle()
    val compassOn by vm.carOverlayCompass.collectAsStateWithLifecycle()
    val phoneLayout by vm.phoneOverlayLayout.collectAsStateWithLifecycle()
    val overlayCtx = if (androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) {
        be.appmire.gpsinfo.ui.overlay.PhoneOverlayContext.LIVEMAP_LANDSCAPE
    } else {
        be.appmire.gpsinfo.ui.overlay.PhoneOverlayContext.LIVEMAP_PORTRAIT
    }

    var follow by remember { mutableStateOf(true) }
    // Map presentation, cycled by the view-mode button (mirrors the car):
    // flat north-up → 2.5D heading-up → 2.5D heading-up + 3D buildings.
    var viewMode by remember { mutableStateOf(be.appmire.gpsinfo.car.MapViewMode.FLAT) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Treat the chip's bearing as "course" only above the 3 km/h
    // threshold — below that the Doppler reading is unstable and
    // would jitter the heading-up rotation around the user.
    val headingMode by vm.headingMode.collectAsStateWithLifecycle()
    val gpsBearing: Float? = if (headingMode ==
        be.appmire.gpsinfo.util.HeadingMode.DualWithCourse
    ) loc?.takeIf { it.hasBearing() }?.bearing else null

    // Bumped to force a one-shot recenter on the user (the MapLibre
    // host follows continuously while `follow` is on; this nudges it
    // once when the user taps Recentre even if follow was already on).
    var recenterTrigger by remember { mutableStateOf(0) }

    // Offline map-region download state.
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadScope = androidx.compose.runtime.rememberCoroutineScope()
    val offlineRepo = remember { be.appmire.gpsinfo.data.nav.OfflineMapRepository(context) }
    var offlineBusy by remember { mutableStateOf(false) }
    var offlineProgress by remember { mutableStateOf(0) }

    // Immersive chrome: the map is full-screen by default; the top bar
    // (with the back arrow) and the shell's bottom nav reveal on any
    // touch of the map, then auto-hide a few seconds later. revealTick
    // is bumped on each touch to (re)start the hide countdown.
    var chromeVisible by remember { mutableStateOf(true) }
    var revealTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(chromeVisible) {
        onChromeVisibilityChanged(chromeVisible)
    }
    androidx.compose.runtime.LaunchedEffect(revealTick) {
        kotlinx.coroutines.delay(3500L)
        chromeVisible = false
    }
    val revealChrome = {
        chromeVisible = true
        revealTick++
    }

    Scaffold(
        topBar = {
            if (chromeVisible) {
            TopAppBar(
                title = { Text(stringResource(R.string.live_map_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (onOpenDestination != null) {
                        IconButton(onClick = onOpenDestination) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.drive_destination),
                            )
                        }
                    }
                    if (onToggleDetail != null) {
                        androidx.compose.material3.TextButton(onClick = onToggleDetail) {
                            Text(
                                stringResource(
                                    if (pro) R.string.detail_simple else R.string.detail_detailed
                                )
                            )
                        }
                    }
                    // Quick settings — theme + units — without leaving the map.
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.activity_hub_more),
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_toggle_theme)) },
                            onClick = {
                                menuExpanded = false
                                vm.setThemeOverride(nextTheme(state.themeOverride))
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_toggle_units)) },
                            onClick = {
                                menuExpanded = false
                                vm.setUnitSystem(nextUnit(state.unitSystem))
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Reveal the chrome on any touch of the map, without
                // consuming the gesture (Initial pass) so the map still
                // pans/zooms and the controls still receive their taps.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.type == PointerEventType.Press) revealChrome()
                        }
                    }
                },
        ) {
            // MapLibre Native vector map (OpenFreeMap style, no key).
            // The host owns the GL MapView + its annotation layers; we
            // just feed it the current fix, follow/heading toggles and
            // the active recording/route.
            MapLibreMapHost(
                modifier = Modifier.fillMaxSize(),
                loc = loc,
                follow = follow,
                viewMode = viewMode,
                gpsBearingDeg = gpsBearing,
                recording = recording,
                navigationTarget = navigationTarget,
                tbtRoute = tbtRoute,
                traffic = traffic,
                recenterTrigger = recenterTrigger,
                darkMap = darkMap,
                simplified = navigating,
            )

            // Top overlay — speed, heading, altitude. Translucent so
            // the map remains visible underneath. The speed cell
            // doubles as a sanity-cross-check: small subtext shows
            // the rolling distance-over-time average and flags
            // divergence from the Doppler chip reading.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Navigation status banner: a progress spinner while the
                // route computes (so it doesn't look stuck), the failure
                // reason if it fails, or the turn-by-turn maneuver once
                // navigating.
                when (val ns = navState) {
                    is NavigationController.NavState.Preparing -> PreparingBanner(ns.detail)
                    is NavigationController.NavState.Failed -> FailedBanner(ns.message)
                    is NavigationController.NavState.Navigating ->
                        ns.nextTurn?.let { turn ->
                            ManeuverBanner(turn = turn, distanceM = ns.distanceToTurnM)
                        }
                    else -> Unit
                }
                // Top strip (Pro only): precise speed + its margin of
                // error, heading and altitude. In Simple the bottom-left
                // dial is the sole speed readout, so the strip is hidden.
                if (pro) {
                    TopOverlay(
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        speedAccuracyKmh = loc
                            ?.takeIf { it.hasSpeedAccuracy() }
                            ?.speedAccuracyMetersPerSecond?.times(3.6f),
                        gpsBearingDeg = gpsBearing,
                        altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                        outsideTempC = outsideTempC,
                        unit = unit,
                    )
                }
            }

            // Speed gauge — current speed dial + posted-limit roundel —
            // pinned to the bottom-left. Hidden when the full gauge cluster
            // is shown (the cluster has its own speed dial).
            if (!clusterOn) {
                SpeedGauge(
                    speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                    limitKmh = (navState as? NavigationController.NavState.Navigating)
                        ?.speedLimitKmh,
                    unit = unit,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 12.dp),
                )
            }

            // Combined view: full gauge cluster overlaid on the map, editable
            // in place. Insets keep it clear of the maneuver banner (top) and
            // the status strip (bottom); empty areas don't grab map gestures.
            if (clusterOn) {
                be.appmire.gpsinfo.ui.overlay.OverlayEditBox(
                    persisted = phoneLayout,
                    onSave = { vm.savePhoneOverlayLayout(it) },
                    context = overlayCtx,
                    controlsAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(top = 76.dp, bottom = 76.dp),
                ) {
                    LiveMapClusterOverlay(vm, compassOn, Modifier.fillMaxSize())
                }
            }

            // Bottom overlay — fix status + accuracy + sats. When a
            // navigation target is active, a dedicated NavOverlay sits
            // above it with bearing arrow + distance + ETA.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Inset from the left so the bottom-left speed dial
                    // has its own corner; the info pills sit beside it.
                    .padding(start = 156.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                navigationTarget?.let { target ->
                    NavOverlay(
                        target = target,
                        currentLat = loc?.latitude,
                        currentLon = loc?.longitude,
                        gpsBearingDeg = gpsBearing,
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        unit = unit,
                        onStop = { vm.clearNavigation() },
                    )
                }
                // Detailed GPS readout — Pro only.
                if (pro) {
                    BottomOverlay(
                        fix = state.gnss.fix,
                        accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                        satsInUse = state.gnss.satellitesInUse,
                        unit = unit,
                    )
                }
            }

            // Right-side stacked controls, each captioned so the icons
            // aren't a guessing game. The two toggles mirror the user's
            // pick (follow / map-rotation); the bottom button recenters
            // once. Follow and Recentre used to share the MyLocation
            // glyph, which made them indistinguishable — Recentre now
            // uses a distinct centre-focus icon.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LabeledMapControl(label = stringResource(R.string.live_map_follow_label)) {
                    FilledIconToggleButton(
                        checked = follow,
                        onCheckedChange = { follow = it },
                    ) {
                        Icon(
                            if (follow) Icons.Outlined.MyLocation
                            else Icons.Outlined.LocationSearching,
                            contentDescription = stringResource(R.string.live_map_follow),
                        )
                    }
                }
                // View-mode cycle (same three modes as the car): flat
                // north-up → 2.5D heading-up → 2.5D + 3D buildings. The
                // icon + caption reflect the *current* mode; tapping
                // advances to the next.
                val viewModeLabel = when (viewMode) {
                    be.appmire.gpsinfo.car.MapViewMode.FLAT -> stringResource(R.string.trail_north_up)
                    be.appmire.gpsinfo.car.MapViewMode.TILTED_FLAT -> "2.5D"
                    be.appmire.gpsinfo.car.MapViewMode.TILTED_3D -> "3D"
                }
                LabeledMapControl(label = viewModeLabel) {
                    FilledIconToggleButton(
                        checked = viewMode != be.appmire.gpsinfo.car.MapViewMode.FLAT,
                        onCheckedChange = {
                            viewMode = when (viewMode) {
                                be.appmire.gpsinfo.car.MapViewMode.FLAT ->
                                    be.appmire.gpsinfo.car.MapViewMode.TILTED_FLAT
                                be.appmire.gpsinfo.car.MapViewMode.TILTED_FLAT ->
                                    be.appmire.gpsinfo.car.MapViewMode.TILTED_3D
                                be.appmire.gpsinfo.car.MapViewMode.TILTED_3D ->
                                    be.appmire.gpsinfo.car.MapViewMode.FLAT
                            }
                        },
                    ) {
                        Icon(
                            when (viewMode) {
                                be.appmire.gpsinfo.car.MapViewMode.FLAT -> Icons.Outlined.ExploreOff
                                be.appmire.gpsinfo.car.MapViewMode.TILTED_FLAT -> Icons.Outlined.Explore
                                be.appmire.gpsinfo.car.MapViewMode.TILTED_3D -> Icons.Outlined.Apartment
                            },
                            contentDescription = stringResource(R.string.live_map_heading_up),
                        )
                    }
                }
                LabeledMapControl(label = stringResource(R.string.live_map_recenter_label)) {
                    FilledIconButton(onClick = {
                        follow = true
                        recenterTrigger++
                    }) {
                        Icon(
                            Icons.Outlined.CenterFocusStrong,
                            contentDescription = stringResource(R.string.live_map_recenter),
                        )
                    }
                }
                // Download the current area's vector tiles for offline
                // use — the map-imagery counterpart to the rd5 road
                // network. Caches a box around the current position.
                LabeledMapControl(label = stringResource(R.string.live_map_download_label)) {
                    FilledIconButton(
                        onClick = {
                            val l = loc ?: return@FilledIconButton
                            if (!offlineBusy) {
                                offlineBusy = true
                                offlineProgress = 0
                                downloadScope.launch {
                                    val half = 0.18 // ≈ 20 km box half-extent
                                    val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
                                        .include(
                                            org.maplibre.android.geometry.LatLng(
                                                l.latitude + half, l.longitude + half,
                                            )
                                        )
                                        .include(
                                            org.maplibre.android.geometry.LatLng(
                                                l.latitude - half, l.longitude - half,
                                            )
                                        )
                                        .build()
                                    offlineRepo.downloadRegion(
                                        name = "area-${l.latitude.toInt()}-${l.longitude.toInt()}",
                                        bounds = bounds,
                                        styleUrl = MapLibreStyle.OFFLINE_DOWNLOAD,
                                        minZoom = 6.0,
                                        maxZoom = 15.0,
                                    ).collect { st ->
                                        when (st) {
                                            is OfflineMapRepository.DownloadState.Progress ->
                                                offlineProgress = if (st.required > 0)
                                                    (st.completed * 100 / st.required).toInt() else 0
                                            is OfflineMapRepository.DownloadState.Done -> {
                                                offlineProgress = 100; offlineBusy = false
                                            }
                                            is OfflineMapRepository.DownloadState.Failed ->
                                                offlineBusy = false
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        if (offlineBusy) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = stringResource(R.string.live_map_download),
                            )
                        }
                    }
                }
            }

            // Offline-download progress chip.
            if (offlineBusy || (offlineProgress in 1..99)) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        stringResource(R.string.live_map_download_progress, offlineProgress),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** A map control button with a small caption beneath it, drawn on a
 *  translucent chip so the label stays legible over map tiles. */
@Composable
private fun LabeledMapControl(label: String, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Spacer(Modifier.height(3.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** Route-computing banner: a spinner + the current phase (tile download
 *  percentage / "computing route") so the prepare step doesn't look
 *  frozen. */
@Composable
private fun PreparingBanner(detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    text = stringResource(R.string.nav_calculating),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Transient banner shown when route preparation fails (no fix, no route,
 *  tile download error) — the controller clears it after a few seconds. */
@Composable
private fun FailedBanner(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** The gauge cluster overlaid on the live map. Collects the ~30 Hz cluster
 *  data in its own scope so the map and chrome don't recompose per tick. */
@androidx.compose.runtime.Composable
private fun LiveMapClusterOverlay(
    vm: DashboardViewModel,
    showCompass: Boolean,
    modifier: Modifier,
) {
    val data by vm.clusterData.collectAsStateWithLifecycle()
    be.appmire.gpsinfo.ui.cluster.ClusterGauges(data, showCompass, modifier)
}

/** Turn-by-turn maneuver banner: distance to the next turn + the cue. */
@Composable
private fun ManeuverBanner(turn: TurnHint, distanceM: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (distanceM >= 1000) "%.1f km".format(Locale.ROOT, distanceM / 1000.0)
                else "${(distanceM / 10).toInt() * 10} m",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = turnCue(turn),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun turnCue(turn: TurnHint): String = stringResource(
    when (turn.command) {
        TurnCommand.TURN_LEFT -> R.string.car_nav_turn_left
        TurnCommand.TURN_SLIGHT_LEFT -> R.string.car_nav_slight_left
        TurnCommand.TURN_SHARP_LEFT -> R.string.car_nav_sharp_left
        TurnCommand.TURN_RIGHT -> R.string.car_nav_turn_right
        TurnCommand.TURN_SLIGHT_RIGHT -> R.string.car_nav_slight_right
        TurnCommand.TURN_SHARP_RIGHT -> R.string.car_nav_sharp_right
        TurnCommand.KEEP_LEFT -> R.string.car_nav_keep_left
        TurnCommand.KEEP_RIGHT -> R.string.car_nav_keep_right
        TurnCommand.U_TURN -> R.string.car_nav_u_turn
        TurnCommand.ROUNDABOUT -> R.string.car_nav_roundabout
        else -> R.string.car_nav_continue
    }
)

private fun nextTheme(t: ThemeOverride): ThemeOverride = when (t) {
    ThemeOverride.System -> ThemeOverride.Light
    ThemeOverride.Light -> ThemeOverride.Dark
    ThemeOverride.Dark -> ThemeOverride.System
}

private fun nextUnit(u: UnitSystem): UnitSystem = when (u) {
    UnitSystem.Metric -> UnitSystem.Imperial
    UnitSystem.Imperial -> UnitSystem.Nautical
    UnitSystem.Nautical -> UnitSystem.Metric
}

@Composable
private fun TopOverlay(
    speedKmh: Float?,
    speedAccuracyKmh: Float?,
    gpsBearingDeg: Float?,
    altMeters: Double?,
    /** OBD ambient temperature in °C, or null when no OBD feed. */
    outsideTempC: Double? = null,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedCell(speedKmh = speedKmh, accuracyKmh = speedAccuracyKmh, unit = unit)
            Stat(
                label = stringResource(R.string.metric_heading),
                value = gpsBearingDeg?.let { "%03d".format(Locale.ROOT, it.toInt()) } ?: "—",
                unit = "°T",
            )
            Stat(
                label = stringResource(R.string.metric_altitude),
                value = altMeters?.let {
                    "%d".format(Locale.ROOT, UnitConverter.lengthFromMeters(it, unit).toInt())
                } ?: "—",
                unit = lengthUnitLabel(unit),
            )
            // Outside temp — only when an OBD adapter is feeding it.
            if (outsideTempC != null) {
                val imperial = unit == UnitSystem.Imperial
                val shown = if (imperial) outsideTempC * 9.0 / 5.0 + 32.0 else outsideTempC
                Stat(
                    label = stringResource(R.string.metric_outside_temp),
                    value = "%.0f".format(Locale.ROOT, shown),
                    unit = if (imperial) "°F" else "°C",
                )
            }
        }
    }
}

/** Precise speed readout with its margin of error: the chip's
 *  Doppler speed plus the ± uncertainty it reports
 *  ([Location.speedAccuracyMetersPerSecond]), shown on its own line so
 *  the confidence in the number is always visible. */
@Composable
private fun SpeedCell(speedKmh: Float?, accuracyKmh: Float?, unit: UnitSystem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.metric_speed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = speedKmh?.let {
                    "%.0f".format(Locale.ROOT, UnitConverter.speedFromKmh(it, unit))
                } ?: "—",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = " ${speedUnitLabel(unit)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = accuracyKmh?.let {
                "± %.1f %s".format(
                    Locale.ROOT,
                    UnitConverter.speedFromKmh(it, unit),
                    speedUnitLabel(unit),
                )
            } ?: "± —",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Bearing arrow + destination name + distance + ETA, surfaced above
 * the regular fix-status overlay whenever a navigation target is
 * active. The arrow rotates by the **relative** bearing — target
 * bearing minus current direction of travel (GPS course) — so
 * "ahead" = up, "left/right" = literal left/right relative to motion.
 *
 * When the user is stationary (no GPS bearing yet), we fall back to
 * absolute bearing — at least the arrow then points in the right
 * compass direction once they start moving.
 */
@Composable
private fun NavOverlay(
    target: be.appmire.gpsinfo.data.model.NavigationTarget,
    currentLat: Double?,
    currentLon: Double?,
    gpsBearingDeg: Float?,
    speedKmh: Float?,
    unit: UnitSystem,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val absoluteBearing: Double? =
                if (currentLat != null && currentLon != null) {
                    be.appmire.gpsinfo.util.NavigationMath.bearingDegrees(
                        currentLat, currentLon, target.targetLatDeg, target.targetLonDeg,
                    )
                } else null
            val relativeBearing: Float =
                if (absoluteBearing != null && gpsBearingDeg != null) {
                    be.appmire.gpsinfo.util.NavigationMath.relativeBearingDegrees(
                        absoluteBearing, gpsBearingDeg,
                    ).toFloat()
                } else absoluteBearing?.toFloat() ?: 0f

            Icon(
                imageVector = Icons.Outlined.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(36.dp)
                    .rotate(relativeBearing),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                val distM: Double? =
                    if (currentLat != null && currentLon != null) {
                        be.appmire.gpsinfo.util.NavigationMath.distanceMetres(
                            currentLat, currentLon, target.targetLatDeg, target.targetLonDeg,
                        )
                    } else null
                val distStr = distM?.let {
                    if (it < 1000.0) "%d m".format(Locale.ROOT, it.toInt())
                    else "%.2f km".format(Locale.ROOT, it / 1000.0)
                } ?: "—"
                val etaStr = if (distM != null && speedKmh != null) {
                    val secs = be.appmire.gpsinfo.util.NavigationMath.etaSeconds(distM, speedKmh)
                    if (secs == null) "—" else {
                        val m = (secs / 60L).toInt()
                        val s = (secs % 60L).toInt()
                        "%d:%02d".format(Locale.ROOT, m, s)
                    }
                } else "—"
                Text(
                    text = "$distStr · ETA $etaStr",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            FilledIconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.nav_stop),
                )
            }
        }
    }
}

@Composable
private fun BottomOverlay(
    fix: FixStatus,
    accuracyMeters: Float?,
    satsInUse: Int,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat(
                label = stringResource(R.string.metric_fix),
                value = stringResource(fix.labelRes),
                unit = "",
            )
            Stat(
                label = stringResource(R.string.metric_h_accuracy),
                value = accuracyMeters?.let {
                    "±%d".format(Locale.ROOT, UnitConverter.lengthFromMeters(it.toDouble(), unit).toInt())
                } ?: "—",
                unit = lengthUnitLabel(unit),
            )
            Stat(
                label = stringResource(R.string.live_map_sats),
                value = satsInUse.takeIf { it > 0 }?.toString() ?: "—",
                unit = "",
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, big: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = if (big) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
