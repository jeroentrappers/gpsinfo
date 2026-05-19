package com.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.appmire.gpsinfo.data.RecordingState
import com.appmire.gpsinfo.data.TrailRecordingService
import com.appmire.gpsinfo.data.model.MagneticAccuracy
import com.appmire.gpsinfo.ui.components.CompassCard
import com.appmire.gpsinfo.ui.onboarding.OnboardingDialog
import com.appmire.gpsinfo.ui.components.PositionCard
import com.appmire.gpsinfo.ui.components.SkyViewCard
import com.appmire.gpsinfo.ui.components.SpeedCard
import com.appmire.gpsinfo.ui.components.StatusBar
import com.appmire.gpsinfo.ui.components.TimeSunCard
import com.appmire.gpsinfo.ui.components.WorldMapCard
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.CoordinateFormat
import kotlinx.coroutines.launch

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
                                    "$waypointDefault @ ${
                                        java.text.SimpleDateFormat(
                                            "yyyy-MM-dd HH:mm", java.util.Locale.US
                                        ).format(java.util.Date())
                                    }"
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
        val sections = listOfNotNull<@Composable () -> Unit>(
            // Banner when system Location toggle is off — without this the
            // user just sees perpetual NO_FIX with no actionable text.
            if (!state.locationEnabled) {
                {
                    LocationDisabledBanner(onOpenSettings = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
            } else null,
            // Compass-calibration banner — only when the magnetometer is
            // bad enough that the heading is meaningfully wrong. Shown
            // above the cards so users notice before reading the compass.
            if (compass.accuracy == MagneticAccuracy.LOW ||
                compass.accuracy == MagneticAccuracy.UNRELIABLE
            ) {
                { CompassCalibrationBanner() }
            } else null,
            {
                StatusBar(
                    fix = state.gnss.fix,
                    accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    satellitesInView = state.gnss.satellitesInView,
                    satellitesInUse = state.gnss.satellitesInUse,
                    averageSnr = state.gnss.averageSnr,
                    unitSystem = state.unitSystem,
                )
            },
            {
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
            },
            {
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
            },
            {
                val satDesc = stringResource(R.string.open_satellites)
                Box(modifier = Modifier
                    .testTag("card_satellites")
                    .clickable(onClick = onOpenSatellites, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = satDesc }
                ) {
                    SkyViewCard(state.gnss)
                }
            },
            {
                val compassDesc = stringResource(R.string.open_compass_detail)
                Box(modifier = Modifier
                    .testTag("card_compass")
                    .clickable(onClick = onOpenCompass, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = compassDesc }
                ) {
                    CompassCard(compass)
                }
            },
            { WorldMapCard(latDeg = loc?.latitude, lonDeg = loc?.longitude, sun = state.sun) },
            { TimeSunCard(nowMillis = state.nowMillis, sun = state.sun) }
        )

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
                items(sections.size, key = { "section-$it" }) { idx -> sections[idx]() }
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
private fun RecordFab(
    recording: RecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when (recording) {
        is RecordingState.Idle -> {
            FloatingActionButton(
                onClick = onStart,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.FiberManualRecord,
                    contentDescription = stringResource(R.string.trail_record),
                )
            }
        }
        is RecordingState.Recording -> {
            // Elapsed seconds. Cheap to format as plain time-string —
            // we don't bother with a ticker because the underlying
            // pointCount already re-emits at the GNSS rate.
            val elapsedSeconds = (System.currentTimeMillis() - recording.startedAtMillis) / 1000L
            ExtendedFloatingActionButton(
                onClick = onStop,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.trail_stop),
                    )
                },
                text = {
                    Text(
                        stringResource(
                            R.string.trail_recording_stats,
                            recording.pointCount,
                            formatElapsed(elapsedSeconds),
                        ),
                    )
                },
            )
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    else "%d:%02d".format(java.util.Locale.ROOT, m, s)
}

@Composable
private fun SaveTrailDialog(
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: (String) -> Unit,
) {
    val default = remember {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        "Trail " + fmt.format(java.util.Date())
    }
    var name by remember { mutableStateOf(default) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.trail_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.trail_save_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(stringResource(R.string.trail_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.trail_discard))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun CopyrightFooter(onClick: () -> Unit) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val description = stringResource(R.string.action_open_about)
    Surface(
        modifier = Modifier
            .testTag("footer_about")
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "© $year Appmire",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.action_about),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.action_open_about),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CompassCalibrationBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Closest stock icon to "figure-8 motion" — a rotational
            // arrow loop. Good enough as a visual hint without shipping
            // a custom vector.
            Icon(
                imageVector = Icons.Outlined.Loop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.compass_calibrate_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.compass_calibrate_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun LocationDisabledBanner(onOpenSettings: () -> Unit) {
    val description = stringResource(R.string.location_off_open_settings)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.location_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.location_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(R.string.location_off_open_settings),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TwoColumnLayout(
    padding: PaddingValues,
    sections: List<@Composable () -> Unit>,
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
            sections.firstOrNull()?.invoke()
            val rest = sections.drop(1)
            val left = rest.filterIndexed { i, _ -> i % 2 == 0 }
            val right = rest.filterIndexed { i, _ -> i % 2 == 1 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    left.forEach { it() }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    right.forEach { it() }
                }
            }
            Spacer(Modifier.height(8.dp))
            CopyrightFooter(onOpenAbout)
        }
    }
}
