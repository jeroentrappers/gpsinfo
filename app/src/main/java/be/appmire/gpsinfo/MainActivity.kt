package be.appmire.gpsinfo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.ui.about.AboutScreen
import be.appmire.gpsinfo.ui.calibration.CalibrationViewModel
import be.appmire.gpsinfo.ui.calibration.CompassCalibrationScreen
import be.appmire.gpsinfo.ui.compass.CompassDetailScreen
import be.appmire.gpsinfo.ui.dashboard.DashboardScreen
import be.appmire.gpsinfo.ui.dashboard.PermissionRequiredScreen
import be.appmire.gpsinfo.ui.cyclingpower.CyclingPowerPairingScreen
import be.appmire.gpsinfo.ui.ghost.GhostPickerScreen
import be.appmire.gpsinfo.ui.share.SharePositionScreen
import be.appmire.gpsinfo.ui.waypoints.WaypointListScreen
import be.appmire.gpsinfo.ui.heartrate.HeartRatePairingScreen
import be.appmire.gpsinfo.ui.heartrate.HrZonesScreen
import be.appmire.gpsinfo.ui.navigation.NavigationPickerScreen
import be.appmire.gpsinfo.ui.nmea.NmeaReadoutScreen
import be.appmire.gpsinfo.ui.satellite.SatelliteListScreen
import be.appmire.gpsinfo.ui.speed.SpeedGaugeScreen
import be.appmire.gpsinfo.ui.sports.SportsDashboardScreen
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme
import be.appmire.gpsinfo.ui.trails.PaceTargetsEditor
import be.appmire.gpsinfo.ui.trails.TrailMapScreen
import be.appmire.gpsinfo.ui.trails.TrailsListScreen
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.io.File
import org.osmdroid.config.Configuration

private object Routes {
    const val Dashboard = "dashboard"
    const val Satellites = "satellites"
    const val Compass = "compass"
    const val Calibration = "calibration"
    const val Speed = "speed"
    const val About = "about"
    const val Trails = "trails"
    const val TrailMap = "trail/{trailId}"
    fun trailMap(id: String) = "trail/$id"
    const val Nmea = "nmea"
    const val NavPicker = "nav-picker"
    const val HrPair = "hr-pair"
    const val HrZones = "hr-zones"
    const val CpPair = "cp-pair"
    const val SharePosition = "share-position"
    const val Waypoints = "waypoints"
    const val Ghost = "ghost"
    const val StrideCalibration = "stride-calibration"
    const val DashboardProfileEditor = "dashboard-profile-editor"
    const val LiveMap = "live-map"
    const val Sports = "sports"
    const val PaceTargets = "pace-targets/{trailId}"
    fun paceTargets(id: String) = "pace-targets/$id"
    const val Rally = "rally"
    const val WheelPair = "wheel-pair"
    const val GForce = "gforce"
    const val Hub = "hub"
    const val GpsLabSimple = "gps-lab-simple"
    const val ExploreSimple = "explore-simple"
    const val RallySimple = "rally-simple"
    const val TrackSimple = "track-simple"
    const val ObdLab = "obd-lab"
}

/** Activity Hub tile → the screen it opens, honouring the per-activity
 *  Simple/Pro detail level (Phase 3). Only GPS Lab has a Simple layout so
 *  far; the others open their existing (Pro) screen regardless. */
private fun routeForActivity(
    a: be.appmire.gpsinfo.ui.activity.Activity,
    detail: be.appmire.gpsinfo.ui.activity.DetailLevel,
): String {
    val simple = detail == be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE
    return when (a) {
        // Drive & Navigate is always the map; Simple/Pro is an in-screen
        // info-density toggle (not a separate screen).
        be.appmire.gpsinfo.ui.activity.Activity.DRIVE_NAVIGATE -> Routes.LiveMap
        be.appmire.gpsinfo.ui.activity.Activity.TRACK_TRAIN ->
            if (simple) Routes.TrackSimple else Routes.Dashboard
        be.appmire.gpsinfo.ui.activity.Activity.EXPLORE_ORIENT ->
            if (simple) Routes.ExploreSimple else Routes.Compass
        be.appmire.gpsinfo.ui.activity.Activity.GPS_LAB ->
            if (simple) Routes.GpsLabSimple else Routes.Satellites
        be.appmire.gpsinfo.ui.activity.Activity.RALLY ->
            if (simple) Routes.RallySimple else Routes.Rally
        be.appmire.gpsinfo.ui.activity.Activity.CUSTOM -> Routes.Dashboard
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // osmdroid's default tile cache path is external storage, which
        // is unwritable under scoped storage (API 29+) since we cap
        // WRITE_EXTERNAL_STORAGE at API 28 in the manifest. Without
        // explicit paths the SqlTileWriter fails to open its DB —
        // visible tiles never load and CacheManager.downloadAreaAsync
        // crashes. Point both base + tile cache at app-private dirs
        // before any MapView is constructed.
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        osmConfig.osmdroidBasePath = File(filesDir, "osmdroid").apply { mkdirs() }
        osmConfig.osmdroidTileCache = File(cacheDir, "osmdroid/tiles").apply { mkdirs() }
        setContent {
            // Hoist the factory so it isn't rebuilt on every recomposition.
            val vmFactory = remember { DashboardViewModel.factory(application) }
            val vm: DashboardViewModel = viewModel(factory = vmFactory)
            val state by vm.state.collectAsStateWithLifecycle()
            val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val effectiveDark = when (state.themeOverride) {
                ThemeOverride.System -> systemDark
                ThemeOverride.Light -> false
                ThemeOverride.Dark -> true
            }

            val activeProfile by vm.dashboardProfile.collectAsStateWithLifecycle()
            val nightChrome = activeProfile.chromeStyle ==
                be.appmire.gpsinfo.data.model.ChromeStyle.NightDimRed
            GPSinfoTheme(forceDark = effectiveDark, nightDimRed = nightChrome) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    // Re-read whatever the OS settled on — granting and
                    // revoking both go through here.
                    vm.refreshPermissionState()
                }

                LaunchedEffect(Unit) {
                    // Count this cold start toward the rating nudge. The
                    // process-static guard keeps config changes (which
                    // re-run LaunchedEffect(Unit)) from double-counting;
                    // a fresh process resets it, which is exactly what we
                    // want to call a "launch".
                    if (!coldStartCounted) {
                        coldStartCounted = true
                        vm.registerColdStart()
                        // Debounced (~once/day) GitHub-releases probe; silent
                        // on failure. Surfaces the update banner when newer.
                        vm.maybeCheckForUpdate()
                    }
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    else vm.refreshPermissionState()
                    // If a heart-rate monitor was paired previously, kick
                    // off a (re)connect. No-op when nothing's paired or
                    // when BLE permissions aren't granted.
                    vm.connectHrIfPaired()
                    // Same for a paired BLE cycling-power meter.
                    vm.connectCpIfPaired()
                }

                // Re-check permission whenever the activity returns to the
                // foreground. Without this a Settings → revoke → back round
                // trip would leave the dashboard in a stale "has-permission"
                // state with no fixes coming in.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            vm.refreshPermissionState()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (hasPermission) {
                    val onboardingSeen by vm.onboardingSeen.collectAsStateWithLifecycle()
                    if (!onboardingSeen) {
                        // First run: capture Language / Units / Theme, then
                        // land on the Dashboard (no persona gate).
                        be.appmire.gpsinfo.ui.onboarding.OnboardingScreen(
                            vm = vm,
                            onDone = { vm.finishOnboarding() },
                        )
                    } else {
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = Routes.Dashboard) {
                        composable(Routes.Hub) {
                            val pinned by vm.pinnedActivities.collectAsStateWithLifecycle()
                            val last by vm.lastActivity.collectAsStateWithLifecycle()
                            val introSeen by vm.activityIntroSeen.collectAsStateWithLifecycle()
                            be.appmire.gpsinfo.ui.activity.ActivityHubScreen(
                                pinned = pinned.toSet(),
                                lastActivity = last,
                                showIntro = !introSeen,
                                onDismissIntro = { vm.markActivityIntroSeen() },
                                onOpenActivity = { activity ->
                                    vm.setLastActivity(activity)
                                    nav.navigate(routeForActivity(activity, vm.detailLevelOf(activity)))
                                },
                            )
                        }
                        composable(Routes.Dashboard) {
                            DashboardScreen(
                                isDark = effectiveDark,
                                onToggleTheme = {
                                    val next = when (state.themeOverride) {
                                        ThemeOverride.System -> if (systemDark) ThemeOverride.Light else ThemeOverride.Dark
                                        ThemeOverride.Light -> ThemeOverride.Dark
                                        ThemeOverride.Dark -> ThemeOverride.Light
                                    }
                                    vm.setThemeOverride(next)
                                },
                                onOpenSatellites = { nav.navigate(Routes.Satellites) },
                                onOpenCompass = { nav.navigate(Routes.Compass) },
                                onOpenCalibration = { nav.navigate(Routes.Calibration) },
                                onOpenSpeed = { nav.navigate(Routes.Speed) },
                                onOpenAbout = { nav.navigate(Routes.About) },
                                onOpenTrails = { nav.navigate(Routes.Trails) },
                                onOpenLiveMap = { nav.navigate(Routes.LiveMap) },
                                onOpenNavPicker = { nav.navigate(Routes.NavPicker) },
                                onOpenWaypoints = { nav.navigate(Routes.Waypoints) },
                                onOpenSports = { nav.navigate(Routes.Sports) },
                                onOpenGhost = { nav.navigate(Routes.Ghost) },
                                onOpenRally = { nav.navigate(Routes.Rally) },
                                onOpenGForce = { nav.navigate(Routes.GForce) },
                                onOpenHub = {
                                    nav.navigate(Routes.Hub) {
                                        popUpTo(Routes.Hub) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                onShowSimple = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.TRACK_TRAIN,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE,
                                    )
                                    nav.navigate(Routes.TrackSimple) {
                                        popUpTo(Routes.Dashboard) { inclusive = true }
                                    }
                                },
                                vm = vm,
                            )
                        }
                        composable(Routes.GForce) {
                            be.appmire.gpsinfo.ui.gforce.GForceScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.Rally) {
                            be.appmire.gpsinfo.ui.rally.RallyScreen(
                                onBack = { nav.popBackStack() },
                                onOpenWheelPair = { nav.navigate(Routes.WheelPair) },
                                onShowSimple = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.RALLY,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE,
                                    )
                                    nav.navigate(Routes.RallySimple) {
                                        popUpTo(Routes.Rally) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.RallySimple) {
                            be.appmire.gpsinfo.ui.activity.RallySimpleScreen(
                                onBack = { nav.popBackStack() },
                                onShowDetailed = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.RALLY,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.PRO,
                                    )
                                    nav.navigate(Routes.Rally) {
                                        popUpTo(Routes.RallySimple) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.ExploreSimple) {
                            be.appmire.gpsinfo.ui.activity.ExploreSimpleScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onShowDetailed = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.EXPLORE_ORIENT,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.PRO,
                                    )
                                    nav.navigate(Routes.Compass) {
                                        popUpTo(Routes.ExploreSimple) { inclusive = true }
                                    }
                                },
                                onMark = { nav.navigate(Routes.Waypoints) },
                                onShare = { nav.navigate(Routes.SharePosition) },
                            )
                        }
                        composable(Routes.TrackSimple) {
                            be.appmire.gpsinfo.ui.activity.TrackSimpleScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onShowDetailed = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.TRACK_TRAIN,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.PRO,
                                    )
                                    nav.navigate(Routes.Dashboard) {
                                        popUpTo(Routes.TrackSimple) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.WheelPair) {
                            be.appmire.gpsinfo.ui.rally.WheelPairingScreen(
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.NavPicker) {
                            val s = vm.state.collectAsStateWithLifecycle().value
                            NavigationPickerScreen(
                                initialLatDeg = s.gnss.location?.latitude,
                                initialLonDeg = s.gnss.location?.longitude,
                                onBack = { nav.popBackStack() },
                                onConfirm = { target ->
                                    vm.setNavigationTarget(target)
                                    nav.popBackStack()
                                },
                                onDriveTo = { target ->
                                    be.appmire.gpsinfo.data.nav.NavigationController
                                        .navigateTo(
                                            this@MainActivity, target.latDeg, target.lonDeg,
                                            destName = target.name,
                                        )
                                    nav.popBackStack()
                                },
                            )
                        }
                        composable(Routes.Trails) {
                            TrailsListScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenTrail = { id -> nav.navigate(Routes.trailMap(id)) },
                            )
                        }
                        composable(Routes.TrailMap) { entry ->
                            val id = entry.arguments?.getString("trailId").orEmpty()
                            TrailMapScreen(
                                vm = vm,
                                trailId = id,
                                onBack = { nav.popBackStack() },
                                // Track-back pops all the way to the dashboard
                                // so the user sees the navigation arrow on the
                                // primary screen they'll be glancing at, not
                                // the map detail they were just on.
                                onTrackBackStarted = {
                                    nav.popBackStack(Routes.Dashboard, inclusive = false)
                                },
                                onOpenPaceTargets = { nav.navigate(Routes.paceTargets(id)) },
                            )
                        }
                        composable(Routes.Satellites) {
                            SatelliteListScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenNmea = { nav.navigate(Routes.Nmea) },
                                onShowSimple = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.GPS_LAB,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE,
                                    )
                                    nav.navigate(Routes.GpsLabSimple) {
                                        popUpTo(Routes.Satellites) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.GpsLabSimple) {
                            be.appmire.gpsinfo.ui.activity.GpsLabSimpleScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onShowDetailed = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.GPS_LAB,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.PRO,
                                    )
                                    nav.navigate(Routes.Satellites) {
                                        popUpTo(Routes.GpsLabSimple) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.Nmea) {
                            NmeaReadoutScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable(Routes.Compass) {
                            CompassDetailScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenCalibration = { nav.navigate(Routes.Calibration) },
                                onShowSimple = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.EXPLORE_ORIENT,
                                        be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE,
                                    )
                                    nav.navigate(Routes.ExploreSimple) {
                                        popUpTo(Routes.Compass) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Routes.Calibration) {
                            // The calibration VM owns its own bounded
                            // magnetometer-sample buffer; build it lazily
                            // so the listener only registers while this
                            // screen is on-stack.
                            val calibrationVm: CalibrationViewModel = viewModel(
                                factory = CalibrationViewModel.factory(application),
                            )
                            CompassCalibrationScreen(
                                vm = calibrationVm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.Speed) {
                            SpeedGaugeScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.About) {
                            AboutScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenHrPair = { nav.navigate(Routes.HrPair) },
                                onOpenHrZones = { nav.navigate(Routes.HrZones) },
                                onOpenCpPair = { nav.navigate(Routes.CpPair) },
                                onOpenSharePosition = { nav.navigate(Routes.SharePosition) },
                                onOpenWaypoints = { nav.navigate(Routes.Waypoints) },
                                onOpenStrideCalibration = { nav.navigate(Routes.StrideCalibration) },
                                onOpenDashboardEditor = { nav.navigate(Routes.DashboardProfileEditor) },
                                onOpenObdLab = { nav.navigate(Routes.ObdLab) },
                            )
                        }
                        composable(Routes.ObdLab) {
                            be.appmire.gpsinfo.ui.obd.ObdLabScreen(onBack = { nav.popBackStack() })
                        }
                        composable(Routes.HrPair) {
                            HeartRatePairingScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.CpPair) {
                            CyclingPowerPairingScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.SharePosition) {
                            SharePositionScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.Waypoints) {
                            WaypointListScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.Ghost) {
                            GhostPickerScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.HrZones) {
                            HrZonesScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.StrideCalibration) {
                            be.appmire.gpsinfo.ui.calibration.StrideCalibrationScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.DashboardProfileEditor) {
                            be.appmire.gpsinfo.ui.dashboard.DashboardProfileEditorScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.LiveMap) {
                            val detail by vm.detailLevels.collectAsStateWithLifecycle()
                            val driveDetail = detail[
                                be.appmire.gpsinfo.ui.activity.Activity.DRIVE_NAVIGATE
                            ] ?: be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE
                            be.appmire.gpsinfo.ui.livemap.LiveMapScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onOpenDestination = { nav.navigate(Routes.NavPicker) },
                                detailLevel = driveDetail,
                                onToggleDetail = {
                                    vm.setActivityDetail(
                                        be.appmire.gpsinfo.ui.activity.Activity.DRIVE_NAVIGATE,
                                        if (driveDetail == be.appmire.gpsinfo.ui.activity.DetailLevel.PRO)
                                            be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE
                                        else be.appmire.gpsinfo.ui.activity.DetailLevel.PRO,
                                    )
                                },
                            )
                        }
                        composable(Routes.Sports) {
                            SportsDashboardScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.PaceTargets) { entry ->
                            val id = entry.arguments?.getString("trailId").orEmpty()
                            var loaded by remember { mutableStateOf<be.appmire.gpsinfo.data.model.Trail?>(null) }
                            LaunchedEffect(id) { loaded = vm.loadTrail(id) }
                            val s = vm.state.collectAsStateWithLifecycle().value
                            loaded?.let { trail ->
                                val scope = rememberCoroutineScope()
                                PaceTargetsEditor(
                                    trail = trail,
                                    unitSystem = s.unitSystem,
                                    onBack = { nav.popBackStack() },
                                    onSave = { updated ->
                                        scope.launch {
                                            vm.updateTrailPoints(trail.id, updated)
                                            nav.popBackStack()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    }
                } else {
                    PermissionRequiredScreen(
                        onRequest = { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                    )
                }
            }
        }
    }

    companion object {
        /** Guards the once-per-process cold-start count. Static, so it
         *  survives Activity recreation (config changes) but resets when
         *  the process is killed and relaunched. */
        @Volatile
        private var coldStartCounted = false
    }
}
