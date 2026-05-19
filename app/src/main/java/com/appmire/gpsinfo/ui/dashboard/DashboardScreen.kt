package com.appmire.gpsinfo.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.TrailRecordingService
import com.appmire.gpsinfo.data.model.MagneticAccuracy
import com.appmire.gpsinfo.ui.components.CompassCard
import com.appmire.gpsinfo.ui.components.PositionCard
import com.appmire.gpsinfo.ui.components.SkyViewCard
import com.appmire.gpsinfo.ui.components.SpeedCard
import com.appmire.gpsinfo.ui.components.StatusBar
import com.appmire.gpsinfo.ui.components.TimeSunCard
import com.appmire.gpsinfo.ui.components.WorldMapCard
import com.appmire.gpsinfo.ui.onboarding.OnboardingDialog
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.CoordinateFormat
import com.appmire.gpsinfo.util.TrailNaming
import kotlinx.coroutines.launch

/**
 * Named section keys for the dashboard's [LazyColumn]. Identifies each
 * card so a future reorder doesn't collapse the children's `remember`
 * state — index-based keys silently break this.
 */
private object SectionKeys {
    const val LocationDisabled = "section-location-disabled"
    const val CompassCalibration = "section-compass-calibration"
    const val Status = "section-status"
    const val Position = "section-position"
    const val Speed = "section-speed"
    const val Sky = "section-sky"
    const val Compass = "section-compass"
    const val World = "section-world"
    const val TimeSun = "section-time-sun"
}

private data class DashboardSection(
    val key: String,
    val content: @Composable () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun DashboardScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    vm: DashboardViewModel,
    onOpenSatellites: () -> Unit = {},
    onOpenCompass: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenTrails: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val compass by vm.compass.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val onboardingSeen by vm.onboardingSeen.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pendingSaveDialog by remember { mutableStateOf(false) }
    var coordFormat by remember { mutableStateOf(CoordinateFormat.DMS) }
    val waypointDefault = stringResource(R.string.trail_waypoint_default_name)
    val waypointNoFix = stringResource(R.string.trail_waypoint_no_fix)
    val waypointSaved = stringResource(R.string.trail_waypoint_saved)
    // LocalWindowInfo follows window resizes (foldables, multi-window)
    // more reliably than LocalConfiguration, which Compose 1.8+ lint
    // discourages for layout breakpoints.
    val containerWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val twoColumn = containerWidthDp.value >= 720f

    Scaffold(
        // testTagsAsResourceId surfaces every Modifier.testTag(...) as an
        // Android resource-id, which UiAutomator can then findObject(By.res …)
        // against. This is what makes our locale-independent screengrab
        // tests work without coordinate guesswork.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val id = vm.saveCurrentAsWaypoint(
                                    TrailNaming.timestamped(
                                        waypointDefault,
                                        System.currentTimeMillis(),
                                    ),
                                )
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (id != null) waypointSaved else waypointNoFix,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    ) {
                        Icon(
                            Icons.Outlined.AddLocationAlt,
                            contentDescription = stringResource(R.string.trail_waypoint_save),
                        )
                    }
                    IconButton(onClick = onOpenTrails) {
                        Icon(
                            Icons.Outlined.Map,
                            contentDescription = stringResource(R.string.trails_open),
                        )
                    }
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = stringResource(R.string.action_toggle_theme)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            // Android 13+ requires runtime permission to post the ongoing
            // recording notification. We register a launcher per
            // composable instance — it's a no-op fast path on older API.
            val notifPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* result ignored — recording works either way, the
                 * notification just won't show without permission */
            }
            RecordFab(
                recording = recording,
                onStart = {
                    TrailRecordingService.ensureNotificationChannel(ctx)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPermissionLauncher.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                    vm.startRecording(ctx)
                },
                onStop = { pendingSaveDialog = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
    ) { padding ->
        val loc = state.gnss.location
        val sections = buildList {
            // Banner when system Location toggle is off — without this the
            // user just sees perpetual NO_FIX with no actionable text.
            if (!state.locationEnabled) add(
                DashboardSection(SectionKeys.LocationDisabled) {
                    LocationDisabledBanner(onOpenSettings = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
            )
            // Compass-calibration banner — only when the magnetometer is
            // bad enough that the heading is meaningfully wrong. Shown
            // above the cards so users notice before reading the compass.
            if (compass.accuracy == MagneticAccuracy.LOW ||
                compass.accuracy == MagneticAccuracy.UNRELIABLE
            ) add(
                DashboardSection(SectionKeys.CompassCalibration) {
                    CompassCalibrationBanner()
                }
            )
            add(DashboardSection(SectionKeys.Status) {
                StatusBar(
                    fix = state.gnss.fix,
                    accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    satellitesInView = state.gnss.satellitesInView,
                    satellitesInUse = state.gnss.satellitesInUse,
                    averageSnr = state.gnss.averageSnr,
                    unitSystem = state.unitSystem,
                )
            })
            add(DashboardSection(SectionKeys.Position) {
                PositionCard(
                    latDeg = loc?.latitude,
                    lonDeg = loc?.longitude,
                    altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                    hAccuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    vAccuracyMeters = if (android.os.Build.VERSION.SDK_INT >= 26) {
                        loc?.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters
                    } else null,
                    format = coordFormat,
                    onToggleFormat = {
                        coordFormat = if (coordFormat == CoordinateFormat.DMS)
                            CoordinateFormat.DECIMAL else CoordinateFormat.DMS
                    },
                    unitSystem = state.unitSystem,
                )
            })
            add(DashboardSection(SectionKeys.Speed) {
                val speedDesc = stringResource(R.string.open_speed_gauge)
                Box(modifier = Modifier
                    .testTag("card_speed")
                    .clickable(onClick = onOpenSpeed, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = speedDesc }
                ) {
                    SpeedCard(
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        headingDegMagnetic = compass.magneticHeadingDeg,
                        altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                        unitSystem = state.unitSystem,
                    )
                }
            })
            add(DashboardSection(SectionKeys.Sky) {
                val satDesc = stringResource(R.string.open_satellites)
                Box(modifier = Modifier
                    .testTag("card_satellites")
                    .clickable(onClick = onOpenSatellites, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = satDesc }
                ) {
                    SkyViewCard(state.gnss)
                }
            })
            add(DashboardSection(SectionKeys.Compass) {
                val compassDesc = stringResource(R.string.open_compass_detail)
                Box(modifier = Modifier
                    .testTag("card_compass")
                    .clickable(onClick = onOpenCompass, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = compassDesc }
                ) {
                    CompassCard(compass)
                }
            })
            add(DashboardSection(SectionKeys.World) {
                WorldMapCard(latDeg = loc?.latitude, lonDeg = loc?.longitude, sun = state.sun)
            })
            add(DashboardSection(SectionKeys.TimeSun) {
                TimeSunCard(nowMillis = state.nowMillis, sun = state.sun)
            })
        }

        if (twoColumn) {
            TwoColumnLayout(
                padding = padding,
                sections = sections,
                onOpenAbout = onOpenAbout,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sections, key = { it.key }) { section -> section.content() }
                item(key = "footer-spacer") { Spacer(Modifier.height(16.dp)) }
                item(key = "footer-copy") { CopyrightFooter(onOpenAbout) }
                item(key = "footer-tail") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (pendingSaveDialog) {
        SaveTrailDialog(
            onCancel = { pendingSaveDialog = false },
            onDiscard = {
                pendingSaveDialog = false
                vm.discardRecording(ctx)
            },
            onSave = { name ->
                pendingSaveDialog = false
                scope.launch { vm.stopRecording(ctx, name) }
            },
        )
    }

    OnboardingDialog(
        hasSeen = onboardingSeen,
        onDismiss = { vm.markOnboardingSeen() },
    )
}

@Composable
private fun TwoColumnLayout(
    padding: PaddingValues,
    sections: List<DashboardSection>,
    onOpenAbout: () -> Unit,
) {
    // status bar full-width, then two columns
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            sections.firstOrNull()?.content?.invoke()
            val rest = sections.drop(1)
            val left = rest.filterIndexed { i, _ -> i % 2 == 0 }
            val right = rest.filterIndexed { i, _ -> i % 2 == 1 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    left.forEach { it.content() }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    right.forEach { it.content() }
                }
            }
            Spacer(Modifier.height(8.dp))
            CopyrightFooter(onOpenAbout)
        }
    }
}
