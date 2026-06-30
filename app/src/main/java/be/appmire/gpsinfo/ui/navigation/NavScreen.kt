package be.appmire.gpsinfo.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.car.MapViewMode
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.data.nav.TrafficController
import be.appmire.gpsinfo.data.nav.TurnCommand
import be.appmire.gpsinfo.ui.cluster.ClusterMode
import be.appmire.gpsinfo.ui.cluster.GaugeCluster
import be.appmire.gpsinfo.ui.livemap.MapLibreMapHost
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.speedUnitLabel
import kotlin.math.abs

/**
 * Full-screen phone turn-by-turn navigation, the phone analogue of the
 * Android Auto surface: a heading-up MapLibre map (route line from the
 * puck, Waze-style palette) with a maneuver card, lane guidance, a live
 * speed + speed-limit badge and an ETA strip layered on top. The layout
 * reflows between landscape (maneuver + ETA in a left rail) and portrait
 * (maneuver banner on top, ETA bar at the bottom).
 *
 * All state comes from [NavigationController]; the screen owns no routing
 * logic. It auto-closes when navigation ends (arrival / cancel).
 */
@Composable
fun NavScreen(
    vm: DashboardViewModel,
    onExit: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val navState by NavigationController.state.collectAsStateWithLifecycle()

    val loc = state.gnss.location
    val unit = state.unitSystem
    val darkMap = when (state.themeOverride) {
        ThemeOverride.Dark -> true
        ThemeOverride.Light -> false
        ThemeOverride.System -> isSystemInDarkTheme()
    }
    // Heading-up course once we're actually moving (Doppler bearing is
    // unstable at a crawl, which would spin the map around the puck).
    val gpsBearing = loc?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.0f }?.bearing

    // Close the screen once a started navigation ends (arrival handled with
    // its own card first). `sawActive` avoids closing on the initial Idle
    // tick before navigateTo's coroutine flips the state to Preparing.
    var sawActive by remember { mutableStateOf(false) }
    LaunchedEffect(navState) {
        if (navState !is NavigationController.NavState.Idle) sawActive = true
    }

    val navg = navState as? NavigationController.NavState.Navigating
    // Draw the road ahead — drop points already driven so the line starts at
    // the puck, like the car surface.
    val tbtRoute = navg?.let { n ->
        val from = n.segmentIndex.coerceIn(0, (n.route.points.size - 1).coerceAtLeast(0))
        n.route.points.subList(from, n.route.points.size)
    }
    var recenter by remember { mutableIntStateOf(0) }
    var dismissedAltKey by remember { mutableStateOf<String?>(null) }
    val voiceOn by vm.voiceGuidanceEnabled.collectAsStateWithLifecycle()

    // Same instrument cluster as the Android Auto surface, opt-in via the
    // shared overlay settings. When on, it replaces the plain speed badge
    // (the cluster shows speed + limit itself); the compass/G-meter centre
    // follows its own toggle. The ~30 Hz cluster data is collected inside
    // NavClusterOverlay so only that subtree — not the whole map screen —
    // recomposes with it.
    val clusterOn by vm.carOverlayCluster.collectAsStateWithLifecycle()
    val compassOn by vm.carOverlayCompass.collectAsStateWithLifecycle()

    // Live traffic: subscribe and keep the viewport on the active route.
    val traffic by TrafficController.incidents.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TrafficController.start() }
    LaunchedEffect(navg?.route) {
        val n = navg
        if (n != null) TrafficController.setRoute(n.route.points.map { doubleArrayOf(it.lat, it.lon) })
        else loc?.let { TrafficController.setLocation(it.latitude, it.longitude) }
    }
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE

    Box(Modifier.fillMaxSize()) {
        MapLibreMapHost(
            loc = loc,
            follow = true,
            viewMode = MapViewMode.TILTED_FLAT,
            gpsBearingDeg = gpsBearing,
            recording = recording,
            navigationTarget = null,
            tbtRoute = tbtRoute,
            traffic = traffic,
            alternativeRoutes = navg?.alternatives?.map { it.route.points } ?: emptyList(),
            recenterTrigger = recenter,
            darkMap = darkMap,
            simplified = true,
            modifier = Modifier.fillMaxSize(),
        )

        val content = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)
        when (val ns = navState) {
            is NavigationController.NavState.Navigating -> {
                val onToggleVoice = { vm.setVoiceGuidanceEnabled(!voiceOn) }
                val clusterUi: (@Composable (Modifier) -> Unit)? = if (clusterOn) {
                    { m -> NavClusterOverlay(vm, compassOn, m) }
                } else null
                if (isLandscape) {
                    NavLandscape(ns, unit, loc, voiceOn, content, { recenter++ }, onToggleVoice, clusterUi) { exit(onExit) }
                } else {
                    NavPortrait(ns, unit, loc, voiceOn, content, { recenter++ }, onToggleVoice, clusterUi) { exit(onExit) }
                }
                val topAlt = ns.alternatives.firstOrNull()
                if (topAlt != null && altKey(topAlt) != dismissedAltKey) {
                    AlternativeCard(
                        alt = topAlt, unit = unit, modifier = content,
                        onTake = { NavigationController.acceptAlternative(topAlt) },
                        onDismiss = { dismissedAltKey = altKey(topAlt) },
                    )
                }
            }
            is NavigationController.NavState.Preparing ->
                CenterCard(ns.detail, R.string.action_cancel, content) { exit(onExit) }
            is NavigationController.NavState.Failed ->
                CenterCard(ns.message, R.string.action_back, content) { exit(onExit) }
            is NavigationController.NavState.Arrived ->
                CenterCard(
                    androidx.compose.ui.res.stringResource(R.string.car_nav_arrived_title),
                    R.string.action_back, content,
                ) { exit(onExit) }
            NavigationController.NavState.Idle ->
                if (sawActive) LaunchedEffect(Unit) { onExit() }
        }
    }
}

private fun exit(onExit: () -> Unit) {
    NavigationController.stop()
    onExit()
}

// ── Layouts ──────────────────────────────────────────────────────────

@Composable
private fun NavLandscape(
    n: NavigationController.NavState.Navigating,
    unit: UnitSystem,
    loc: android.location.Location?,
    voiceOn: Boolean,
    modifier: Modifier,
    onRecenter: () -> Unit,
    onToggleVoice: () -> Unit,
    cluster: (@Composable (Modifier) -> Unit)?,
    onExit: () -> Unit,
) {
    Box(modifier) {
        // Left rail: maneuver + lanes on top, ETA at the bottom.
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 380.dp)
                .fillMaxWidth(0.42f)
                .align(Alignment.TopStart),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ManeuverCard(n, unit, Modifier.fillMaxWidth())
            LaneGuidance(n.nextTurn?.lanes, Modifier.padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            EtaCard(n, unit, Modifier.fillMaxWidth())
        }
        // Right edge controls + live readouts.
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MuteButton(voiceOn, onToggleVoice)
            ExitButton(onExit = onExit)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecenterButton(onRecenter)
            if (cluster != null) cluster(Modifier.size(200.dp))
            else SpeedAndLimit(loc, unit, n.speedLimitKmh)
        }
    }
}

@Composable
private fun NavPortrait(
    n: NavigationController.NavState.Navigating,
    unit: UnitSystem,
    loc: android.location.Location?,
    voiceOn: Boolean,
    modifier: Modifier,
    onRecenter: () -> Unit,
    onToggleVoice: () -> Unit,
    cluster: (@Composable (Modifier) -> Unit)?,
    onExit: () -> Unit,
) {
    Box(modifier) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ManeuverCard(n, unit, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ExitButton(onExit = onExit)
            }
            LaneGuidance(n.nextTurn?.lanes, Modifier.padding(start = 4.dp))
        }
        if (cluster != null) {
            cluster(Modifier.align(Alignment.BottomStart).padding(bottom = 84.dp).size(168.dp))
        } else {
            SpeedAndLimit(
                loc, unit, n.speedLimitKmh,
                Modifier.align(Alignment.BottomStart).padding(bottom = 84.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 84.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MuteButton(voiceOn, onToggleVoice)
            RecenterButton(onRecenter)
        }
        EtaCard(n, unit, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

// ── Components ───────────────────────────────────────────────────────

@Composable
private fun ManeuverCard(
    n: NavigationController.NavState.Navigating,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManeuverIcon(
                command = n.nextTurn?.command ?: TurnCommand.STRAIGHT,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(60.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = formatNavDistance(n.distanceToTurnM, unit),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = turnCue(n.nextTurn?.command),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EtaCard(
    n: NavigationController.NavState.Navigating,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val arrival = remember(n.etaSeconds) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        fmt.format(java.util.Date(System.currentTimeMillis() + n.etaSeconds * 1000L))
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            n.destName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.size(4.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EtaStat(arrival, R.string.nav_eta_arrival)
                EtaStat(formatNavDuration(n.etaSeconds), R.string.nav_eta_remaining)
                EtaStat(formatNavDistance(n.distanceRemainingM, unit), R.string.nav_eta_distance)
            }
        }
    }
}

@Composable
private fun EtaStat(value: String, labelRes: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            androidx.compose.ui.res.stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpeedAndLimit(
    loc: android.location.Location?,
    unit: UnitSystem,
    limitKmh: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (limitKmh != null) SpeedLimitSign(limitKmh, unit)
        val kmh = loc?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f }
        val shown = kmh?.let { UnitConverter.speedFromKmh(it, unit) }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(percent = 50),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = shown?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "––",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = speedUnitLabel(unit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/** The Android-Auto instrument cluster (integrated layout) overlaid on the
 *  nav map. Collects the ~30 Hz cluster data here, in its own recomposition
 *  scope, so the map and chrome don't re-lay-out with every sensor tick. */
@Composable
private fun NavClusterOverlay(
    vm: DashboardViewModel,
    showCompass: Boolean,
    modifier: Modifier,
) {
    val data by vm.clusterData.collectAsStateWithLifecycle()
    GaugeCluster(data, modifier, showCompass = showCompass, mode = ClusterMode.INTEGRATED)
}

/** EU posted-limit roundel: white disc, red ring, black number. The limit
 *  is always posted in km/h on the route; converted for display. */
@Composable
private fun SpeedLimitSign(limitKmh: Int, unit: UnitSystem) {
    val shown = UnitConverter.speedFromKmh(limitKmh.toFloat(), unit).toInt()
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(5.dp, Color(0xFFD32F2F), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = shown.toString(),
            color = Color.Black,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterCard(text: String, actionRes: Int, modifier: Modifier, onAction: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                androidx.compose.material3.TextButton(onClick = onAction) {
                    Text(androidx.compose.ui.res.stringResource(actionRes))
                }
            }
        }
    }
}

@Composable
private fun ExitButton(modifier: Modifier = Modifier, onExit: () -> Unit) {
    FloatingActionButton(
        onClick = onExit,
        modifier = modifier.size(52.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Icon(Icons.Outlined.Close, contentDescription = androidx.compose.ui.res.stringResource(R.string.action_back))
    }
}

@Composable
private fun MuteButton(voiceOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onToggle,
        modifier = modifier.size(52.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = if (voiceOn) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(
            if (voiceOn) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
            contentDescription = androidx.compose.ui.res.stringResource(
                if (voiceOn) R.string.nav_mute else R.string.nav_unmute,
            ),
        )
    }
}

@Composable
private fun RecenterButton(onRecenter: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onRecenter,
        modifier = modifier.size(52.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Icon(Icons.Outlined.MyLocation, contentDescription = androidx.compose.ui.res.stringResource(R.string.nav_recenter))
    }
}

/** Mid-drive "fork in the road" suggestion: the trade-off + take/dismiss. */
@Composable
private fun AlternativeCard(
    alt: NavigationController.RouteAlternative,
    unit: UnitSystem,
    modifier: Modifier,
    onTake: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.nav_alt_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    altTradeoff(alt, unit),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    textAlign = TextAlign.Center,
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.nav_alt_dismiss)) }
                    Button(onClick = onTake) { Text(stringResource(R.string.nav_alt_take)) }
                }
            }
        }
    }
}

private fun altKey(a: NavigationController.RouteAlternative): String =
    "${a.deltaSeconds}_${a.deltaMeters}_${a.route.points.size}"

/** "2 min longer but 10 km shorter" — only the significant parts, joined
 *  with "but" when it's a genuine trade-off (one saves, the other costs). */
@Composable
private fun altTradeoff(a: NavigationController.RouteAlternative, unit: UnitSystem): String {
    val parts = ArrayList<String>(2)
    val mins = abs(a.deltaSeconds) / 60
    val timeSaves = a.deltaSeconds <= 0
    if (mins >= 1) {
        parts += stringResource(if (timeSaves) R.string.nav_alt_faster else R.string.nav_alt_slower, mins)
    }
    val distSaves = a.deltaMeters <= 0
    if (abs(a.deltaMeters) >= 500) {
        val dist = formatNavDistance(abs(a.deltaMeters).toDouble(), unit)
        parts += stringResource(if (distSaves) R.string.nav_alt_shorter else R.string.nav_alt_longer, dist)
    }
    if (parts.isEmpty()) return stringResource(R.string.nav_alt_title)
    val sep = if (parts.size == 2 && timeSaves != distSaves) {
        " ${stringResource(R.string.nav_alt_but)} "
    } else {
        " · "
    }
    return parts.joinToString(sep)
}

@Composable
private fun turnCue(c: TurnCommand?): String = androidx.compose.ui.res.stringResource(
    when (c) {
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
