package be.appmire.gpsinfo.ui.dashboard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.DashboardDensity
import be.appmire.gpsinfo.data.TrailRecordingService
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.ui.components.CompassCard
import be.appmire.gpsinfo.ui.components.PositionCard
import be.appmire.gpsinfo.ui.components.SkyViewCard
import be.appmire.gpsinfo.ui.components.SpeedCard
import be.appmire.gpsinfo.ui.components.StatusBar
import be.appmire.gpsinfo.ui.components.TimeSunCard
import be.appmire.gpsinfo.ui.components.WorldMapCard
import be.appmire.gpsinfo.ui.onboarding.OnboardingDialog
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.CoordinateFormat
import be.appmire.gpsinfo.util.TrailNaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Named section keys for the dashboard's [LazyColumn]. Identifies each
 * card so a future reorder doesn't collapse the children's `remember`
 * state — index-based keys silently break this.
 */
private object SectionKeys {
    const val UpdateAvailable = "section-update-available"
    const val LocationDisabled = "section-location-disabled"
    const val CompassCalibration = "section-compass-calibration"
    const val AutoPause = "section-auto-pause"
    const val Navigation = "section-navigation"
    const val HeartRate = "section-heart-rate"
    const val Status = "section-status"
    const val Position = "section-position"
    const val Speed = "section-speed"
    const val Sky = "section-sky"
    const val Compass = "section-compass"
    const val World = "section-world"
    const val TimeSun = "section-time-sun"
    const val TripComputer = "section-trip-computer"
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
    onOpenCalibration: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenTrails: () -> Unit = {},
    onOpenLiveMap: () -> Unit = {},
    onOpenNavPicker: () -> Unit = {},
    onOpenSports: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenGhost: () -> Unit = {},
    onOpenRally: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val compass by vm.compass.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val onboardingSeen by vm.onboardingSeen.collectAsStateWithLifecycle()
    val showRateNudge by vm.showRateNudge.collectAsStateWithLifecycle()
    val updateAvailable by vm.updateAvailable.collectAsStateWithLifecycle()
    val navigationTarget by vm.navigationTarget.collectAsStateWithLifecycle()
    val hrState by vm.hrState.collectAsStateWithLifecycle()
    val hrZoneConfig by vm.hrZoneConfig.collectAsStateWithLifecycle()
    val density by vm.dashboardDensity.collectAsStateWithLifecycle()
    val tripStats by vm.tripStats.collectAsStateWithLifecycle()
    val activeProfile by vm.dashboardProfile.collectAsStateWithLifecycle()
    val headingMode by vm.headingMode.collectAsStateWithLifecycle()
    var showPaceGoalDialog by remember { mutableStateOf(false) }
    // Lifted out of the (now-removed) top-bar action lambda so the
    // slide-out drawer can trigger the same waypoint-capture sheet.
    var captureOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pendingSaveDialog by remember { mutableStateOf(false) }
    var coordFormat by remember { mutableStateOf(CoordinateFormat.DMS) }
    val waypointDefault = stringResource(R.string.trail_waypoint_default_name)
    val waypointNoFix = stringResource(R.string.trail_waypoint_no_fix)
    val waypointSaved = stringResource(R.string.trail_waypoint_saved)
    val lapToast = stringResource(R.string.lap_recorded_toast)
    // LocalWindowInfo follows window resizes (foldables, multi-window)
    // more reliably than LocalConfiguration, which Compose 1.8+ lint
    // discourages for layout breakpoints.
    val containerWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val twoColumn = containerWidthDp.value >= 720f
    val isRecording = recording is be.appmire.gpsinfo.data.RecordingState.Recording

    // Mark a lap and surface a confirmation toast. Shared by the
    // top-bar quick action (visible only while recording) and the
    // drawer's "Mark lap" entry so both behave identically.
    val markLapWithToast = {
        val marker = vm.markLap()
        if (marker != null) {
            android.widget.Toast.makeText(
                ctx,
                String.format(
                    java.util.Locale.ROOT,
                    lapToast,
                    marker.index,
                    marker.lapDistanceM / 1000.0,
                    marker.lapDurationMs / 60_000L,
                    (marker.lapDurationMs / 1000L) % 60L,
                ),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // Quick-save the current fix as a timestamped waypoint, with a
    // toast on success / no-fix.
    val saveQuickWaypoint = {
        scope.launch {
            val id = vm.saveCurrentAsWaypoint(
                TrailNaming.timestamped(waypointDefault, System.currentTimeMillis()),
            )
            android.widget.Toast.makeText(
                ctx,
                if (id != null) waypointSaved else waypointNoFix,
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        Unit
    }
    val closeDrawer = { scope.launch { drawerState.close() }; Unit }

    // Recording start needs two best-effort runtime permissions. We
    // register the launchers here (composable scope) so the top-bar
    // record action can fire them — recording still works if denied,
    // the notification / step-count just won't appear.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored */ }
    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored */ }
    val startRecording = {
        TrailRecordingService.ensureNotificationChannel(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            activityPermissionLauncher.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
        }
        vm.startRecording(ctx)
    }
    // 1 Hz elapsed ticker for the recording title — advances even when
    // GNSS emissions stall (tunnel, indoors).
    val activeRecording = recording as? be.appmire.gpsinfo.data.RecordingState.Recording
    val recElapsedSec by produceState(0L, activeRecording?.startedAtMillis) {
        val start = activeRecording?.startedAtMillis
        if (start == null) {
            value = 0L
        } else {
            while (true) {
                value = (System.currentTimeMillis() - start) / 1000L
                delay(1_000L)
            }
        }
    }

    // enterAlways: bar hides on scroll down and reappears immediately on
    // scroll up. Both LazyColumn (phone) and Modifier.verticalScroll
    // (tablet TwoColumnLayout) dispatch nested-scroll events, so a single
    // behavior wired to the Scaffold covers both layouts.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // While recording, tint the top app bar with the error-container
    // colour. Persistent indicator that survives scroll-bar-collapse-then-
    // reveal cycles and remains visible alongside the FAB.
    val topBarContainer = if (isRecording) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    val topBarContent = if (isRecording) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onBackground
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DashboardDrawerContent(
                isRecording = isRecording,
                isDark = isDark,
                activeProfileId = activeProfile.id,
                onSelectProfile = { id ->
                    closeDrawer()
                    vm.setDashboardProfile(id)
                },
                onNewWaypoint = {
                    closeDrawer()
                    captureOpen = true
                },
                onSaveWaypoint = {
                    closeDrawer()
                    saveQuickWaypoint()
                },
                onMarkLap = {
                    closeDrawer()
                    markLapWithToast()
                },
                onOpenLiveMap = { closeDrawer(); onOpenLiveMap() },
                onOpenTrails = { closeDrawer(); onOpenTrails() },
                onOpenNavPicker = { closeDrawer(); onOpenNavPicker() },
                onOpenWaypoints = { closeDrawer(); onOpenWaypoints() },
                onOpenSports = { closeDrawer(); onOpenSports() },
                onOpenGhost = { closeDrawer(); onOpenGhost() },
                onOpenRally = { closeDrawer(); onOpenRally() },
                onToggleTheme = onToggleTheme,
                onOpenSettings = { closeDrawer(); onOpenAbout() },
            )
        },
    ) {
    Scaffold(
        // testTagsAsResourceId surfaces every Modifier.testTag(...) as an
        // Android resource-id, which UiAutomator can then findObject(By.res …)
        // against. This is what makes our locale-independent screengrab
        // tests work without coordinate guesswork.
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    // While recording, the title doubles as the live
                    // readout the FAB used to show (record dot + elapsed
                    // + point count) — moving the action to the top bar
                    // shouldn't cost the user that glanceable info.
                    if (activeRecording != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.FiberManualRecord,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(
                                    R.string.trail_recording_stats,
                                    activeRecording.pointCount,
                                    formatRecElapsed(recElapsedSec),
                                ),
                            )
                        }
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            Icons.Outlined.Menu,
                            contentDescription = stringResource(R.string.drawer_open),
                        )
                    }
                },
                actions = {
                    // Mark-lap is the one action time-critical enough to
                    // keep one tap away mid-activity. Shown only while
                    // recording.
                    if (isRecording) {
                        IconButton(onClick = { markLapWithToast() }) {
                            Icon(
                                Icons.Outlined.Flag,
                                contentDescription = stringResource(R.string.lap_action),
                            )
                        }
                    }
                    // Record / stop toggle — moved here from the old FAB,
                    // which floated over and obscured dashboard content.
                    if (isRecording) {
                        IconButton(onClick = { pendingSaveDialog = true }) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = stringResource(R.string.trail_stop),
                            )
                        }
                    } else {
                        IconButton(onClick = { startRecording() }) {
                            Icon(
                                Icons.Outlined.FiberManualRecord,
                                contentDescription = stringResource(R.string.trail_record),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainer,
                    scrolledContainerColor = topBarContainer,
                    titleContentColor = topBarContent,
                    navigationIconContentColor = topBarContent,
                    actionIconContentColor = topBarContent,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
    ) { padding ->
        val loc = state.gnss.location
        val sections = buildList {
            // Update-available banner — surfaced when the GitHub-releases
            // check has seen a newer version than this build and the user
            // hasn't dismissed it. Tapping opens the Play Store listing.
            updateAvailable?.let { newVersion ->
                add(DashboardSection(SectionKeys.UpdateAvailable) {
                    UpdateAvailableBanner(
                        versionName = newVersion,
                        onUpdate = {
                            be.appmire.gpsinfo.util.IntentHelpers.openPlayStoreListing(ctx)
                        },
                        onDismiss = { vm.dismissUpdate() },
                    )
                })
            }
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
                    CompassCalibrationBanner(onOpenCalibration = onOpenCalibration)
                }
            )
            // Auto-pause banner — only while recording, and only when
            // either paused or stationary above the auto-pause threshold.
            val rec = recording as? be.appmire.gpsinfo.data.RecordingState.Recording
            if (rec != null && (rec.paused || rec.secondsSinceMovement >= 30L)) {
                add(DashboardSection(SectionKeys.AutoPause) {
                    AutoPausedBanner(
                        paused = rec.paused,
                        onResume = { vm.resumeRecording() },
                        onPauseManually = { vm.pauseRecording() },
                    )
                })
            }
            // Navigation card — only when the user has actually picked
            // a destination. Sits high in the stack so the arrow stays
            // glanceable above the rest of the instrument-panel cards.
            // Heart-rate card — shown only when actively connected and
            // delivering BPM. Connecting / Disconnected / Scanning all
            // hide the card; transient state surfaces on the pairing
            // screen instead. Disconnect button on the card itself drops
            // the live link without forgetting the device.
            if (hrState is be.appmire.gpsinfo.data.model.HeartRateState.Connected) {
                add(DashboardSection(SectionKeys.HeartRate) {
                    be.appmire.gpsinfo.ui.components.HeartRateCard(
                        hrState = hrState,
                        zoneConfig = hrZoneConfig,
                        onDisconnect = { vm.disconnectHr() },
                    )
                })
            }
            navigationTarget?.let { target ->
                add(DashboardSection(SectionKeys.Navigation) {
                    be.appmire.gpsinfo.ui.components.NavigationCard(
                        target = target,
                        currentLatDeg = loc?.latitude,
                        currentLonDeg = loc?.longitude,
                        // For the relative-bearing arrow: above
                        // 3 km/h, GPS course-over-ground is what "I'm
                        // facing" means in motion (driving / cycling
                        // with phone mounted). Below that we fall
                        // back to the magnetic compass — stationary
                        // orienteering use.
                        currentHeadingDeg = if (headingMode ==
                            be.appmire.gpsinfo.util.HeadingMode.DualWithCourse
                        ) {
                            loc?.takeIf { it.hasBearing() }?.bearing
                                ?: compass.magneticHeadingDeg
                        } else compass.magneticHeadingDeg,
                        currentSpeedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        unitSystem = state.unitSystem,
                        onStop = { vm.clearNavigation() },
                        onEditGoal = { showPaceGoalDialog = true },
                    )
                })
            }
            // Profile-controlled cards (Status, Position, Speed, Sky,
            // Compass, World, TimeSun). Build each one in its own
            // section block, then append them in the profile's order
            // below.
            val profileBuilders =
                mutableMapOf<be.appmire.gpsinfo.data.model.DashboardSection, DashboardSection>()
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.Status] =
                DashboardSection(SectionKeys.Status) {
                StatusBar(
                    fix = state.gnss.fix,
                    accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    unitSystem = state.unitSystem,
                )
            }
            // Build a one-line nav context for the Share button to
            // attach to the payload — only when the user is actively
            // navigating. Format: "Heading to <waypoint>, ETA HH:MM".
            val navContextLine: String? = navigationTarget?.let { target ->
                val curLat = loc?.latitude
                val curLon = loc?.longitude
                val pace = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
                if (curLat != null && curLon != null && pace != null && pace > 0f) {
                    val targetLat = target.targetLatDeg
                    val targetLon = target.targetLonDeg
                    val distM = be.appmire.gpsinfo.util.NavigationMath.distanceMetres(
                        curLat, curLon, targetLat, targetLon,
                    )
                    val etaSec = be.appmire.gpsinfo.util.NavigationMath.etaSeconds(distM, pace)
                    if (etaSec != null) {
                        val etaMillis = System.currentTimeMillis() + etaSec * 1000L
                        val etaStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(etaMillis))
                        val labelKm = "%.1f km".format(java.util.Locale.ROOT, distM / 1000.0)
                        ctx.getString(R.string.share_nav_context, labelKm, etaStr)
                    } else null
                } else null
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.Position] =
                DashboardSection(SectionKeys.Position) {
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
                        // Cycle through every format in declaration order.
                        // Long-press on the coordinates still copies whatever
                        // the active format renders.
                        val all = CoordinateFormat.entries
                        coordFormat = all[(coordFormat.ordinal + 1) % all.size]
                    },
                    unitSystem = state.unitSystem,
                    navContextLine = navContextLine,
                )
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.Speed] =
                DashboardSection(SectionKeys.Speed) {
                val speedDesc = stringResource(R.string.open_speed_gauge)
                Box(modifier = Modifier
                    .testTag("card_speed")
                    .clickable(onClick = onOpenSpeed, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = speedDesc }
                ) {
                    SpeedCard(
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        headingDegMagnetic = compass.magneticHeadingDeg,
                        // GPS course-over-ground — preferred over the
                        // magnetometer once the user is moving above
                        // the 3 km/h threshold. Below that the Doppler
                        // bearing is unstable; we suppress it and
                        // let the magnetic compass take over.
                        gpsBearingDeg = if (headingMode ==
                            be.appmire.gpsinfo.util.HeadingMode.DualWithCourse
                        ) loc?.takeIf { it.hasBearing() }?.bearing else null,
                        altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                        unitSystem = state.unitSystem,
                    )
                }
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.Sky] =
                DashboardSection(SectionKeys.Sky) {
                val satDesc = stringResource(R.string.open_satellites)
                Box(modifier = Modifier
                    .testTag("card_satellites")
                    .clickable(onClick = onOpenSatellites, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = satDesc }
                ) {
                    SkyViewCard(state.gnss)
                }
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.Compass] =
                DashboardSection(SectionKeys.Compass) {
                val compassDesc = stringResource(R.string.open_compass_detail)
                Box(modifier = Modifier
                    .testTag("card_compass")
                    .clickable(onClick = onOpenCompass, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = compassDesc }
                ) {
                    CompassCard(
                        reading = compass,
                        courseHeadingDeg = if (headingMode ==
                            be.appmire.gpsinfo.util.HeadingMode.DualWithCourse
                        ) loc?.takeIf { it.hasBearing() }?.bearing else null,
                    )
                }
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.World] =
                DashboardSection(SectionKeys.World) {
                WorldMapCard(latDeg = loc?.latitude, lonDeg = loc?.longitude, sun = state.sun)
            }
            profileBuilders[be.appmire.gpsinfo.data.model.DashboardSection.TimeSun] =
                DashboardSection(SectionKeys.TimeSun) {
                TimeSunCard(nowMillis = state.nowMillis, sun = state.sun)
            }
            // Emit profile-controlled cards in the user-chosen order.
            // A profile that omits a section silently hides it; the
            // map lookup returns null and is filtered out.
            for (sectionKey in activeProfile.cards) {
                profileBuilders[sectionKey]?.let(::add)
            }
            // Trip computer — surfaced only once at least one trail
            // exists since the last reset. Long-press the card to reset.
            if (!tripStats.isEmpty) {
                add(DashboardSection(SectionKeys.TripComputer) {
                    be.appmire.gpsinfo.ui.components.TripComputerCard(
                        stats = tripStats,
                        onReset = { vm.resetTrip() },
                    )
                })
            }
        }

        val itemSpacing = when (density) {
            DashboardDensity.Standard -> 10.dp
            DashboardDensity.Glanceable -> 18.dp
        }
        val horizontalPadding = when (density) {
            DashboardDensity.Standard -> 12.dp
            DashboardDensity.Glanceable -> 16.dp
        }
        val verticalPadding = when (density) {
            DashboardDensity.Standard -> 8.dp
            DashboardDensity.Glanceable -> 14.dp
        }
        // Bespoke persona layouts: one screen, no scrolling. Pre-Phase-2
        // every profile rendered the same scrolling LazyColumn with
        // reordered cards. Bespoke layouts are hand-crafted per
        // persona and fill the remaining vertical space below the
        // top banner strip. Default + Custom fall through to the
        // legacy scrolling path below.
        if (isBespokeLayout(activeProfile.id)) {
            // Bespoke persona layouts are responsive on their own — the
            // face picks a stacked (portrait), two-panel (landscape) or
            // scaled-up (tablet) arrangement from its own constraints,
            // so we no longer divert wide screens to the generic grid.
            val bannerKeys = setOf(
                SectionKeys.UpdateAvailable,
                SectionKeys.LocationDisabled,
                SectionKeys.CompassCalibration,
                SectionKeys.AutoPause,
            )
            val banners = sections.filter { it.key in bannerKeys }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (banners.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing),
                    ) {
                        banners.forEach { it.content() }
                    }
                }
                RenderBespokeLayout(
                    profile = activeProfile,
                    vm = vm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        } else if (twoColumn) {
            TwoColumnLayout(
                padding = padding,
                sections = sections,
                onOpenAbout = onOpenAbout,
                itemSpacing = itemSpacing,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                items(sections, key = { it.key }) { section -> section.content() }
                item(key = "footer-spacer") { Spacer(Modifier.height(16.dp)) }
                item(key = "footer-copy") { CopyrightFooter(onOpenAbout) }
                item(key = "footer-tail") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
    }

    // Waypoint-capture sheet, triggered from the drawer's "New
    // waypoint" entry. Lifted here so it overlays the whole screen.
    if (captureOpen) {
        be.appmire.gpsinfo.ui.waypoints.WaypointCaptureSheet(
            vm = vm,
            onDismiss = { captureOpen = false },
        )
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

    be.appmire.gpsinfo.ui.rating.RateNudgeDialog(
        show = showRateNudge,
        onRate = {
            vm.onRateNudgeAccepted()
            be.appmire.gpsinfo.util.IntentHelpers.openPlayStoreListing(ctx)
        },
        onSnooze = { vm.onRateNudgeSnoozed() },
        onDecline = { vm.onRateNudgeDeclined() },
    )

    if (showPaceGoalDialog) {
        be.appmire.gpsinfo.ui.navigation.PaceGoalDialog(
            initialSecondsPerUnit = navigationTarget?.targetPaceSecondsPerUnit,
            unitSystem = state.unitSystem,
            onDismiss = { showPaceGoalDialog = false },
            onConfirm = { pace ->
                vm.setTargetPace(pace)
                showPaceGoalDialog = false
            },
        )
    }
}

@Composable
private fun TwoColumnLayout(
    padding: PaddingValues,
    sections: List<DashboardSection>,
    onOpenAbout: () -> Unit,
    itemSpacing: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
) {
    // status bar full-width, then two columns
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            sections.firstOrNull()?.content?.invoke()
            val rest = sections.drop(1)
            val left = rest.filterIndexed { i, _ -> i % 2 == 0 }
            val right = rest.filterIndexed { i, _ -> i % 2 == 1 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                    left.forEach { it.content() }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                    right.forEach { it.content() }
                }
            }
            Spacer(Modifier.height(8.dp))
            CopyrightFooter(onOpenAbout)
        }
    }
}

/**
 * Slide-out navigation drawer. Replaces the old top-bar action strip,
 * which had grown to ~10 unlabeled icons. Everything is now grouped
 * into labelled sections with an icon AND text, so destinations are
 * self-explanatory:
 *
 *  - Quick actions : new waypoint, save-location pin, mark lap.
 *  - Go to         : live map, trails, navigate, waypoints, sports.
 *  - Dashboard     : the persona-profile picker (was a top-bar dropdown).
 *  - App           : theme toggle, settings.
 *
 * Recording-only entries (Mark lap, Sports view) appear only while a
 * trail recording is active. The single time-critical action (Mark
 * lap) is also mirrored in the top bar for one-tap access.
 */
@Composable
private fun DashboardDrawerContent(
    isRecording: Boolean,
    isDark: Boolean,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    onNewWaypoint: () -> Unit,
    onSaveWaypoint: () -> Unit,
    onMarkLap: () -> Unit,
    onOpenLiveMap: () -> Unit,
    onOpenTrails: () -> Unit,
    onOpenNavPicker: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenSports: () -> Unit,
    onOpenGhost: () -> Unit,
    onOpenRally: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Brand header.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, top = 22.dp, bottom = 14.dp, end = 28.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.drawer_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp))

            // --- Quick actions --- //
            DrawerSectionLabel(stringResource(R.string.drawer_section_quick))
            DrawerItem(
                label = stringResource(R.string.waypoint_capture_title),
                icon = Icons.Outlined.PinDrop,
                onClick = onNewWaypoint,
            )
            DrawerItem(
                label = stringResource(R.string.trail_waypoint_save),
                icon = Icons.Outlined.AddLocationAlt,
                onClick = onSaveWaypoint,
            )
            if (isRecording) {
                DrawerItem(
                    label = stringResource(R.string.lap_action),
                    icon = Icons.Outlined.Flag,
                    onClick = onMarkLap,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))

            // --- Navigation destinations --- //
            DrawerSectionLabel(stringResource(R.string.drawer_section_navigate))
            DrawerItem(
                label = stringResource(R.string.live_map_open),
                icon = Icons.Outlined.MyLocation,
                onClick = onOpenLiveMap,
            )
            DrawerItem(
                label = stringResource(R.string.trails_open),
                icon = Icons.Outlined.Map,
                onClick = onOpenTrails,
            )
            DrawerItem(
                label = stringResource(R.string.nav_start),
                icon = Icons.Outlined.NearMe,
                onClick = onOpenNavPicker,
            )
            DrawerItem(
                label = stringResource(R.string.settings_waypoints),
                icon = Icons.AutoMirrored.Outlined.List,
                onClick = onOpenWaypoints,
            )
            if (isRecording) {
                DrawerItem(
                    label = stringResource(R.string.sports_dashboard_open),
                    icon = Icons.AutoMirrored.Outlined.DirectionsRun,
                    onClick = onOpenSports,
                )
            }
            DrawerItem(
                label = stringResource(R.string.settings_ghost),
                icon = Icons.Outlined.SportsScore,
                onClick = onOpenGhost,
            )
            DrawerItem(
                label = stringResource(R.string.drawer_rally),
                icon = Icons.Outlined.Timer,
                onClick = onOpenRally,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))

            // --- Dashboard profile picker (was a top-bar dropdown) --- //
            DrawerSectionLabel(stringResource(R.string.drawer_section_dashboard))
            be.appmire.gpsinfo.data.model.DashboardProfile.builtIns.forEach { p ->
                NavigationDrawerItem(
                    label = { Text(p.displayName) },
                    selected = p.id == activeProfileId,
                    onClick = { onSelectProfile(p.id) },
                    icon = { ProfileSwatch(argb = p.accentArgb) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            val customId = be.appmire.gpsinfo.data.model.DashboardProfile.CUSTOM_ID
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.dashboard_profile_custom)) },
                selected = activeProfileId == customId,
                onClick = { onSelectProfile(customId) },
                icon = {
                    ProfileSwatch(
                        argb = be.appmire.gpsinfo.data.model.DashboardProfile.COLOR_ORANGE,
                    )
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp))

            // --- App --- //
            DrawerSectionLabel(stringResource(R.string.drawer_section_app))
            // Theme toggle stays open so the user sees the drawer (and the
            // whole UI) recolour live, and can flip back in one tap.
            DrawerItem(
                label = stringResource(R.string.action_toggle_theme),
                icon = if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                onClick = onToggleTheme,
            )
            DrawerItem(
                label = stringResource(R.string.screen_settings),
                icon = Icons.Outlined.Settings,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Tracked-out uppercase section heading inside the drawer. */
@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A standard icon+label drawer row. Non-selectable (these are
 *  actions / one-shot navigations, not a persistent destination
 *  selection — that pattern is reserved for the profile picker). */
@Composable
private fun DrawerItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/** Compact elapsed-time formatter for the recording title:
 *  `M:SS`, or `H:MM:SS` once past an hour. */
private fun formatRecElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    else "%d:%02d".format(java.util.Locale.ROOT, m, s)
}

/** 12-dp coloured circle used to label each profile in the switcher
 *  dropdown so the user can recognise a profile by accent at a
 *  glance. Null swatch falls back to the theme primary. */
@Composable
private fun ProfileSwatch(argb: Int?) {
    val colour = argb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(colour, androidx.compose.foundation.shape.CircleShape),
    )
}
