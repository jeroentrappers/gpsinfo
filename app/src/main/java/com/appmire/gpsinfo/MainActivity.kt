package com.appmire.gpsinfo

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
import androidx.compose.runtime.remember
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
import com.appmire.gpsinfo.data.ThemeOverride
import com.appmire.gpsinfo.ui.about.AboutScreen
import com.appmire.gpsinfo.ui.calibration.CalibrationViewModel
import com.appmire.gpsinfo.ui.calibration.CompassCalibrationScreen
import com.appmire.gpsinfo.ui.compass.CompassDetailScreen
import com.appmire.gpsinfo.ui.dashboard.DashboardScreen
import com.appmire.gpsinfo.ui.dashboard.PermissionRequiredScreen
import com.appmire.gpsinfo.ui.nmea.NmeaReadoutScreen
import com.appmire.gpsinfo.ui.satellite.SatelliteListScreen
import com.appmire.gpsinfo.ui.speed.SpeedGaugeScreen
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme
import com.appmire.gpsinfo.ui.trails.TrailMapScreen
import com.appmire.gpsinfo.ui.trails.TrailsListScreen
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
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
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // osmdroid needs a unique User-Agent or the OSM tile server
        // (rightly) refuses traffic from default-UA clients. Setting it
        // to the package name keeps us identifiable + rate-limit-friendly.
        // The tile cache lives under `osmdroid/tiles` in app-private dirs.
        Configuration.getInstance().userAgentValue = packageName
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

            GPSinfoTheme(forceDark = effectiveDark) {
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    // Re-read whatever the OS settled on — granting and
                    // revoking both go through here.
                    vm.refreshPermissionState()
                }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    else vm.refreshPermissionState()
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
                    val nav = rememberNavController()
                    NavHost(navController = nav, startDestination = Routes.Dashboard) {
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
                                vm = vm,
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
                            AboutScreen(vm = vm, onBack = { nav.popBackStack() })
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
}
