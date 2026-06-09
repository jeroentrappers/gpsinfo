package be.appmire.gpsinfo.ui.livemap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onBack: () -> Unit,
    /** Open the destination picker (search / saved places). */
    onOpenDestination: (() -> Unit)? = null,
    /** Drive & Navigate detail level: Simple shows just speed + guidance,
     *  Pro adds heading/altitude and the GPS fix/accuracy/sats bar. */
    detailLevel: DetailLevel = DetailLevel.PRO,
    /** Flip the detail level (Simple ⇄ Pro) in place. */
    onToggleDetail: (() -> Unit)? = null,
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

    var follow by remember { mutableStateOf(true) }
    var headingUp by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Treat the chip's bearing as "course" only above the 3 km/h
    // threshold — below that the Doppler reading is unstable and
    // would jitter the heading-up rotation around the user.
    val headingMode by vm.headingMode.collectAsStateWithLifecycle()
    val gpsBearing: Float? = if (headingMode ==
        be.appmire.gpsinfo.util.HeadingMode.DualWithCourse
    ) loc?.takeIf { it.hasBearing() }?.bearing else null

    // Rolling distance-over-time speed estimator — independent
    // cross-check against Location.speed (Doppler) so the user can
    // see when the chip is being noisy.
    val rollingSpeed = remember { be.appmire.gpsinfo.util.RollingSpeed() }
    if (loc != null) {
        val ts = if (loc.time > 0) loc.time else System.currentTimeMillis()
        rollingSpeed.push(loc.latitude, loc.longitude, ts)
    }
    val rollingAvgKmh: Float? = rollingSpeed.averageKmh()
    val rollingWindowSec: Long = rollingSpeed.windowSeconds()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.live_map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // MapLibre Native vector map (OpenFreeMap style, no key).
            // The host owns the GL MapView + its annotation layers; we
            // just feed it the current fix, follow/heading toggles and
            // the active recording/route.
            MapLibreMapHost(
                modifier = Modifier.fillMaxSize(),
                loc = loc,
                follow = follow,
                headingUp = headingUp,
                gpsBearingDeg = gpsBearing,
                recording = recording,
                navigationTarget = navigationTarget,
                tbtRoute = tbtRoute,
                recenterTrigger = recenterTrigger,
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
                // Turn-by-turn maneuver banner while navigating an
                // offline route.
                (navState as? NavigationController.NavState.Navigating)?.let { navg ->
                    navg.nextTurn?.let { turn ->
                        ManeuverBanner(turn = turn, distanceM = navg.distanceToTurnM)
                    }
                }
                TopOverlay(
                    speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                    rollingAvgKmh = rollingAvgKmh,
                    rollingWindowSec = rollingWindowSec,
                    gpsBearingDeg = gpsBearing,
                    altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                    unit = unit,
                    // Simple: just the speed. Pro: + heading, altitude and
                    // the rolling-average cross-check.
                    compact = !pro,
                )
            }

            // Bottom overlay — fix status + accuracy + sats. When a
            // navigation target is active, a dedicated NavOverlay sits
            // above it with bearing arrow + distance + ETA.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
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
                LabeledMapControl(
                    label = if (headingUp) stringResource(R.string.trail_heading_up)
                    else stringResource(R.string.trail_north_up),
                ) {
                    FilledIconToggleButton(
                        checked = headingUp,
                        onCheckedChange = { headingUp = it },
                    ) {
                        Icon(
                            if (headingUp) Icons.Outlined.Explore else Icons.Outlined.ExploreOff,
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
                                        styleUrl = MapLibreStyle.LIBERTY,
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
    rollingAvgKmh: Float?,
    rollingWindowSec: Long,
    gpsBearingDeg: Float?,
    altMeters: Double?,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
    /** Simple mode — show only the speed, hide heading/altitude. */
    compact: Boolean = false,
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
            SpeedStat(
                liveKmh = speedKmh,
                avgKmh = if (compact) null else rollingAvgKmh,
                windowSec = rollingWindowSec,
                unit = unit,
            )
            if (!compact) {
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
            }
        }
    }
}

/**
 * Speed cell with a built-in rolling-window cross-check. The big
 * number is the chip's Doppler-derived `Location.speed`. Underneath
 * sits a small subtext showing the rolling distance-over-time
 * average and the delta — colour-coded:
 *
 *  - green / no flag : agreement within ±2 km/h (good fix)
 *  - amber           : ±2-6 km/h disagreement (transitional or
 *                      mildly noisy)
 *  - red             : > 6 km/h disagreement (multipath, signal
 *                      bounce, the chip is lying)
 *
 * The window seconds shown is the actual observed span (1-second
 * GPS cadence + occasional drops mean a "10 s window" may contain
 * 8-12 s of data).
 */
@Composable
private fun SpeedStat(
    liveKmh: Float?,
    avgKmh: Float?,
    windowSec: Long,
    unit: UnitSystem,
) {
    val liveDisp = liveKmh?.let { "%.0f".format(Locale.ROOT, UnitConverter.speedFromKmh(it, unit)) } ?: "—"
    val avgDisp = avgKmh?.let { "%.0f".format(Locale.ROOT, UnitConverter.speedFromKmh(it, unit)) }
    val deltaAbs: Float? = if (liveKmh != null && avgKmh != null) kotlin.math.abs(liveKmh - avgKmh) else null
    val deltaTint = when {
        deltaAbs == null -> MaterialTheme.colorScheme.onSurfaceVariant
        deltaAbs <= 2f -> be.appmire.gpsinfo.ui.theme.SignalGreen
        deltaAbs <= 6f -> be.appmire.gpsinfo.ui.theme.SignalYellow
        else -> be.appmire.gpsinfo.ui.theme.SignalRed
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.metric_speed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            be.appmire.gpsinfo.ui.components.AutoSizingText(
                text = liveDisp,
                maxFontSize = 28.sp,
                minFontSize = 16.sp,
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
        if (avgDisp != null) {
            Text(
                text = stringResource(
                    R.string.live_map_avg_subtext,
                    avgDisp,
                    speedUnitLabel(unit),
                    windowSec,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = deltaTint,
                fontFamily = FontFamily.Monospace,
            )
        }
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
