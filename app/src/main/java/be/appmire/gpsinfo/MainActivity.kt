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
import androidx.compose.runtime.Composable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import be.appmire.gpsinfo.R
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
    const val PaceTargets = "pace-targets/{trailId}"
    fun paceTargets(id: String) = "pace-targets/$id"
    const val Rally = "rally"
    const val WheelPair = "wheel-pair"
    const val GForce = "gforce"
    const val ObdLab = "obd-lab"
    const val Tools = "tools"
}

/** The four bottom-nav pillars (IA v2). Map = live map, Record = trails. */
private val TAB_ROUTES = listOf(Routes.Dashboard, Routes.LiveMap, Routes.Trails, Routes.Tools)

@Composable
private fun MainBottomBar(nav: androidx.navigation.NavController, currentRoute: String?) {
    if (currentRoute !in TAB_ROUTES) return
    data class Tab(val route: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val labelRes: Int)
    val tabs = listOf(
        Tab(Routes.Dashboard, Icons.Outlined.SpaceDashboard, R.string.tab_dashboard),
        Tab(Routes.LiveMap, Icons.Outlined.Map, R.string.tab_map),
        Tab(Routes.Trails, Icons.Outlined.Timeline, R.string.tab_record),
        Tab(Routes.Tools, Icons.Outlined.Tune, R.string.tab_tools),
    )
    NavigationBar {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
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
                    val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route
                    Scaffold(
                        // Inner screens own their status-bar / side insets via their
                        // own Scaffolds; the shell only contributes the bottom-bar
                        // height. Zeroing contentWindowInsets here avoids consuming
                        // the system-bar insets twice (which left fat top/bottom gaps).
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        bottomBar = { MainBottomBar(nav, currentRoute) },
                    ) { shellPadding ->
                    NavHost(
                        navController = nav,
                        startDestination = Routes.Dashboard,
                        modifier = Modifier.padding(shellPadding),
                    ) {
                        composable(Routes.Dashboard) {
                            DashboardScreen(
                                vm = vm,
                                onOpenSatellites = { nav.navigate(Routes.Satellites) },
                                onOpenCompass = { nav.navigate(Routes.Compass) },
                                onOpenCalibration = { nav.navigate(Routes.Calibration) },
                                onOpenSpeed = { nav.navigate(Routes.Speed) },
                                onOpenAbout = { nav.navigate(Routes.About) },
                                onOpenGForce = { nav.navigate(Routes.GForce) },
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
                                // Top-level tab → no back arrow.
                                onBack = null,
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
                        composable(Routes.Tools) {
                            be.appmire.gpsinfo.ui.tools.ToolsScreen(
                                onOpenGpsLab = { nav.navigate(Routes.Satellites) },
                                onOpenCompass = { nav.navigate(Routes.Compass) },
                                onOpenSpeed = { nav.navigate(Routes.Speed) },
                                onOpenNmea = { nav.navigate(Routes.Nmea) },
                                onOpenRally = { nav.navigate(Routes.Rally) },
                                onOpenObdLab = { nav.navigate(Routes.ObdLab) },
                                onOpenGhost = { nav.navigate(Routes.Ghost) },
                                onOpenNavigate = { nav.navigate(Routes.NavPicker) },
                                onOpenSettings = { nav.navigate(Routes.About) },
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
