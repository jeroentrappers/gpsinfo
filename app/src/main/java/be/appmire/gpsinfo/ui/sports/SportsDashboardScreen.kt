package be.appmire.gpsinfo.ui.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.HeartRateState
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.formatPace
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.paceSecondsPerUnit
import be.appmire.gpsinfo.util.paceUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Full-screen "guided run" view. Surfaces the recording session as a
 * runner-focused instrument panel:
 *
 *   1. Header strip — elapsed time + total distance covered.
 *   2. Three live-vs-avg metric rows (pace, speed, gait).
 *   3. Heart-rate panel with zone colouring.
 *
 * The screen is purely a *view* of an active recording — it reads
 * everything from [DashboardViewModel.recordingState] and friends. If
 * the user opens it without a recording in progress, a "Start recording
 * first" prompt explains the prerequisite.
 *
 * Intensity-profile-ahead and ETA-to-next-climb (the route-projection
 * pieces of #30) are deferred to a follow-up so this PR can ship the
 * static instrument-panel half cleanly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportsDashboardScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val hrState by vm.hrState.collectAsStateWithLifecycle()
    val hrZoneConfig by vm.hrZoneConfig.collectAsStateWithLifecycle()
    val navigationTarget by vm.navigationTarget.collectAsStateWithLifecycle()
    val tutorialSeen by vm.sportsTutorialSeen.collectAsStateWithLifecycle()
    val ghostTrail by vm.ghostTrail.collectAsStateWithLifecycle()

    // Re-arm cue transition memory when the user enters this screen so
    // the first matching cue actually fires (otherwise re-entry would
    // be a no-op because "last severity" still matches).
    androidx.compose.runtime.DisposableEffect(Unit) {
        vm.audibleCues.resetTransitions()
        vm.vibrationCues.resetTransitions()
        onDispose {
            vm.audibleCues.resetTransitions()
            vm.vibrationCues.resetTransitions()
        }
    }

    // Pace-deviation cue. Computes the live delta vs the active target
    // pace, classifies into a severity band, and tells the manager. The
    // manager dedups + throttles internally.
    val currentSpeedKmh = state.gnss.location?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
    val targetPace = navigationTarget?.effectiveTargetPaceSecondsPerUnit(state.unitSystem)
    if (targetPace != null) {
        val currentPace = be.appmire.gpsinfo.util.paceSecondsPerUnit(currentSpeedKmh, state.unitSystem)
        val severity = paceSeverity(currentPace, targetPace)
        androidx.compose.runtime.LaunchedEffect(severity) {
            if (severity != null) {
                vm.audibleCues.reportPace(severity)
                vm.vibrationCues.reportPace(severity)
            }
        }
    }
    // HR zone cue.
    val hrConnected = hrState as? be.appmire.gpsinfo.data.model.HeartRateState.Connected
    val hrZone = hrConnected?.lastBpm?.let(hrZoneConfig::zoneFor)
    if (hrZone != null) {
        androidx.compose.runtime.LaunchedEffect(hrZone) {
            vm.audibleCues.reportHrZone(hrZone)
            vm.vibrationCues.reportHrZone(hrZone)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sports_dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
        when (val rec = recording) {
            is RecordingState.Recording -> RecordingContent(
                padding = padding,
                rec = rec,
                currentSpeedKmh = state.gnss.location?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                unitSystem = state.unitSystem,
                hrState = hrState,
                hrZoneConfig = hrZoneConfig,
                targetPaceSecondsPerUnit = navigationTarget?.effectiveTargetPaceSecondsPerUnit(state.unitSystem),
                route = navigationTarget as? be.appmire.gpsinfo.data.model.NavigationTarget.Route,
                ghostTrail = ghostTrail,
                onClearGhost = { vm.setGhostTrail(null) },
            )
            RecordingState.Idle -> IdlePlaceholder(padding)
        }
    }

    if (!tutorialSeen) {
        SportsTutorialOverlay(onDismiss = { vm.markSportsTutorialSeen() })
    }
}

@Composable
private fun IdlePlaceholder(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.sports_dashboard_idle_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingContent(
    padding: PaddingValues,
    rec: RecordingState.Recording,
    currentSpeedKmh: Float?,
    unitSystem: UnitSystem,
    hrState: HeartRateState,
    hrZoneConfig: HrZoneConfig,
    targetPaceSecondsPerUnit: Float?,
    route: be.appmire.gpsinfo.data.model.NavigationTarget.Route?,
    ghostTrail: be.appmire.gpsinfo.data.model.Trail?,
    onClearGhost: () -> Unit,
) {
    // 1 Hz tick so the elapsed-time readout keeps advancing even when
    // GPS is briefly silent. Same pattern as the FAB ticker.
    var nowMillis by androidx.compose.runtime.remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedSec = ((nowMillis - rec.startedAtMillis) / 1_000L).coerceAtLeast(0L)

    // Width breakpoint: anything ≥ 720 dp (landscape phone, foldable
    // inner, tablet) renders the two gauges side-by-side so both stay
    // in the first viewport. Portrait phones (< 720 dp) keep the
    // stacked layout — gauges are tall, side-by-side cramps the dial.
    val containerWidthDp = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
    }
    val wide = containerWidthDp.value >= 720f

    // Tabletop posture: phone half-folded with a horizontal hinge.
    // Bottom half is on the table and out of glance-view. Push the
    // scroll content into the top half by padding the bottom with
    // the height of the lower screen segment. Density conversion via
    // LocalDensity keeps the dp / px math correct.
    val fold = be.appmire.gpsinfo.ui.util.rememberFoldingFeature()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tabletopBottomPad: androidx.compose.ui.unit.Dp =
        if (be.appmire.gpsinfo.ui.util.isTabletop(fold)) {
            val windowHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current
                .containerSize.height
            val hingeTopPx = fold!!.bounds.top
            with(density) { (windowHeightPx - hingeTopPx).coerceAtLeast(0).toDp() }
        } else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = (12.dp + tabletopBottomPad),
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderStrip(elapsedSec = elapsedSec, distanceMetres = rec.distanceMetres, unitSystem = unitSystem)

        // Ghost-pacer panel — shown only when the user picked a trail
        // to race against. Sits high in the stack because it's the
        // primary point of comparison for a "race past self" run.
        if (ghostTrail != null) {
            GhostPacerCard(
                ghostTrail = ghostTrail,
                elapsedMillis = elapsedSec * 1000L,
                liveDistanceMetres = rec.distanceMetres,
                onClearGhost = onClearGhost,
            )
        }

        // Glanceable gauges go right under the header so a runner who
        // looks down for half a second sees their pace-vs-goal and zone
        // immediately, without scrolling.
        val connectedHr = hrState as? HeartRateState.Connected
        val showPace = targetPaceSecondsPerUnit != null
        val showHr = connectedHr != null
        if (showPace || showHr) {
            if (wide && showPace && showHr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PaceDeviationGauge(
                        currentPaceSecondsPerUnit = be.appmire.gpsinfo.util.paceSecondsPerUnit(
                            currentSpeedKmh, unitSystem,
                        ),
                        targetPaceSecondsPerUnit = targetPaceSecondsPerUnit!!,
                        paceUnitLabel = be.appmire.gpsinfo.util.paceUnitLabel(unitSystem),
                        modifier = Modifier.weight(1f),
                    )
                    HrZoneGauge(
                        bpm = connectedHr!!.lastBpm,
                        zoneConfig = hrZoneConfig,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                if (showPace) {
                    PaceDeviationGauge(
                        currentPaceSecondsPerUnit = be.appmire.gpsinfo.util.paceSecondsPerUnit(
                            currentSpeedKmh, unitSystem,
                        ),
                        targetPaceSecondsPerUnit = targetPaceSecondsPerUnit!!,
                        paceUnitLabel = be.appmire.gpsinfo.util.paceUnitLabel(unitSystem),
                    )
                }
                if (showHr) {
                    HrZoneGauge(
                        bpm = connectedHr!!.lastBpm,
                        zoneConfig = hrZoneConfig,
                    )
                }
            }
        }

        // Upcoming-trail intensity profile + ETA to next climb. Only
        // meaningful when a track-back route is active; otherwise we
        // don't know what's ahead of the user.
        if (route != null) {
            val segments = be.appmire.gpsinfo.util.RouteProjection.upcomingSegments(route)
            if (segments.isNotEmpty()) {
                val nextClimb = be.appmire.gpsinfo.util.RouteProjection.nextClimb(segments)
                IntensityProfileCard(
                    segments = segments,
                    nextClimb = nextClimb,
                    currentSpeedKmh = currentSpeedKmh,
                    unitSystem = unitSystem,
                )
            }
        }

        // Stat rows: on wide screens, pace + speed sit side-by-side
        // (related "how fast" metrics), gait stretches across full width
        // because the cadence + stride cells already fan out.
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    PaceRow(
                        currentSpeedKmh = currentSpeedKmh,
                        avgSpeedKmh = avgSpeedFromTotals(rec.distanceMetres, elapsedSec),
                        unitSystem = unitSystem,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SpeedRow(
                        currentSpeedKmh = currentSpeedKmh,
                        avgSpeedKmh = avgSpeedFromTotals(rec.distanceMetres, elapsedSec),
                        unitSystem = unitSystem,
                    )
                }
            }
        } else {
            PaceRow(
                currentSpeedKmh = currentSpeedKmh,
                avgSpeedKmh = avgSpeedFromTotals(rec.distanceMetres, elapsedSec),
                unitSystem = unitSystem,
            )
            SpeedRow(
                currentSpeedKmh = currentSpeedKmh,
                avgSpeedKmh = avgSpeedFromTotals(rec.distanceMetres, elapsedSec),
                unitSystem = unitSystem,
            )
        }

        GaitRow(
            cadenceSpm = rec.cadenceSpm,
            avgCadenceSpm = rec.avgCadenceSpm,
            strideMetres = rec.strideMetres,
            avgStrideMetres = rec.avgStrideMetres,
            unitSystem = unitSystem,
        )

        // HR rendering moved into the HrZoneGauge above. The fallback
        // states (paired-but-no-samples, disconnected) still show here
        // so the user knows the strap is in transitional state.
        if (hrState !is HeartRateState.Connected || hrState.lastBpm == null) {
            HrPanel(hrState = hrState, zoneConfig = hrZoneConfig)
        }
    }
}

@Composable
private fun HeaderStrip(elapsedSec: Long, distanceMetres: Double, unitSystem: UnitSystem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigStat(
                label = stringResource(R.string.sports_metric_elapsed),
                value = formatElapsedHms(elapsedSec),
            )
            BigStat(
                label = stringResource(R.string.sports_metric_distance),
                value = formatDistance(distanceMetres, unitSystem),
            )
        }
    }
}

@Composable
private fun PaceRow(
    currentSpeedKmh: Float?,
    avgSpeedKmh: Float?,
    unitSystem: UnitSystem,
) {
    val curPace = paceSecondsPerUnit(currentSpeedKmh, unitSystem)
    val avgPace = paceSecondsPerUnit(avgSpeedKmh, unitSystem)
    val unitLabel = paceUnitLabel(unitSystem)
    LiveVsAvgRow(
        label = stringResource(R.string.metric_pace),
        currentText = curPace?.let { "${formatPace(it)} $unitLabel" } ?: dash(),
        avgText = avgPace?.let { "${formatPace(it)} $unitLabel" } ?: dash(),
        accent = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SpeedRow(
    currentSpeedKmh: Float?,
    avgSpeedKmh: Float?,
    unitSystem: UnitSystem,
) {
    val unitLabel = speedUnitLabel(unitSystem)
    val cur = currentSpeedKmh?.let { UnitConverter.speedFromKmh(it, unitSystem) }
    val avg = avgSpeedKmh?.let { UnitConverter.speedFromKmh(it, unitSystem) }
    LiveVsAvgRow(
        label = stringResource(R.string.metric_speed),
        currentText = cur?.let { "%.1f %s".format(Locale.ROOT, it, unitLabel) } ?: dash(),
        avgText = avg?.let { "%.1f %s".format(Locale.ROOT, it, unitLabel) } ?: dash(),
        accent = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun GaitRow(
    cadenceSpm: Float?,
    avgCadenceSpm: Float?,
    strideMetres: Float?,
    avgStrideMetres: Float?,
    unitSystem: UnitSystem,
) {
    val lengthLabel = lengthUnitLabel(unitSystem)
    val curStride = strideMetres?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
    val avgStride = avgStrideMetres?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveVsAvgRow(
            label = stringResource(R.string.sports_metric_cadence),
            currentText = cadenceSpm?.let { "%.0f spm".format(Locale.ROOT, it) } ?: dash(),
            avgText = avgCadenceSpm?.let { "%.0f spm".format(Locale.ROOT, it) } ?: dash(),
            accent = MaterialTheme.colorScheme.tertiary,
        )
        LiveVsAvgRow(
            label = stringResource(R.string.sports_metric_stride),
            currentText = curStride?.let { "%.2f %s".format(Locale.ROOT, it, lengthLabel) } ?: dash(),
            avgText = avgStride?.let { "%.2f %s".format(Locale.ROOT, it, lengthLabel) } ?: dash(),
            accent = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun HrPanel(hrState: HeartRateState, zoneConfig: HrZoneConfig) {
    val connected = hrState as? HeartRateState.Connected
    val bpm = connected?.lastBpm
    val zone = bpm?.let(zoneConfig::zoneFor)
    val accent = zone?.let(::zoneColor) ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.hr_card_title).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = bpm?.toString() ?: dash(),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = accent,
                )
                Text(
                    text = if (bpm != null) "BPM" else stringResource(R.string.hr_card_waiting),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (zone != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.hr_card_zone).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Z$zone",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveVsAvgRow(label: String, currentText: String, avgText: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.sports_metric_now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentText,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = accent,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.sports_metric_avg),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = avgText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun dash(): String = stringResource(R.string.placeholder_dash)

private fun avgSpeedFromTotals(distanceMetres: Double, elapsedSec: Long): Float? {
    if (elapsedSec < 5L || distanceMetres < 1.0) return null
    val kmh = (distanceMetres / 1000.0) / (elapsedSec.toDouble() / 3600.0)
    return kmh.toFloat()
}

private fun formatElapsedHms(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    else "%d:%02d".format(Locale.ROOT, m, s)
}

private fun formatDistance(metres: Double, unit: UnitSystem): String = when (unit) {
    UnitSystem.Metric ->
        if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
        else "%.2f km".format(Locale.ROOT, metres / 1_000.0)
    UnitSystem.Imperial -> {
        val ft = UnitConverter.lengthFromMeters(metres, unit)
        if (ft < 5_280) "%d ft".format(Locale.ROOT, ft.toInt())
        else "%.2f mi".format(Locale.ROOT, ft / 5_280.0)
    }
    UnitSystem.Nautical -> "%.2f NM".format(Locale.ROOT, metres / 1_852.0)
}

/** Classify the user's current pace deviation into a severity band that
 *  matches the gauge's colour bands and the audible-cue manager's
 *  PaceSeverity enum. Null when we don't have a current pace yet. */
private fun paceSeverity(
    currentPaceSecondsPerUnit: Float?,
    targetPaceSecondsPerUnit: Float,
): be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity? {
    val cur = currentPaceSecondsPerUnit ?: return null
    val delta = cur - targetPaceSecondsPerUnit
    val abs = kotlin.math.abs(delta)
    return when {
        abs <= 5f -> be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity.OnTarget
        abs <= 15f && delta > 0 -> be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity.SlightlySlow
        abs <= 15f -> be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity.SlightlyFast
        delta > 0 -> be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity.TooSlow
        else -> be.appmire.gpsinfo.data.AudibleCueManager.PaceSeverity.TooFast
    }
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> SignalGreen.copy(alpha = 0.55f)
    2 -> SignalGreen
    3 -> SignalYellow
    4 -> SignalOrange
    else -> SignalRed
}
