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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.appmire.gpsinfo.data.ThemeOverride
import com.appmire.gpsinfo.ui.about.AboutScreen
import com.appmire.gpsinfo.ui.compass.CompassDetailScreen
import com.appmire.gpsinfo.ui.dashboard.DashboardScreen
import com.appmire.gpsinfo.ui.dashboard.PermissionRequiredScreen
import com.appmire.gpsinfo.ui.satellite.SatelliteListScreen
import com.appmire.gpsinfo.ui.speed.SpeedGaugeScreen
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

private object Routes {
    const val Dashboard = "dashboard"
    const val Satellites = "satellites"
    const val Compass = "compass"
    const val Speed = "speed"
    const val About = "about"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(application))
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
                ) { granted ->
                    if (granted) vm.onPermissionGranted()
                }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    else vm.onPermissionGranted()
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
                                onOpenSpeed = { nav.navigate(Routes.Speed) },
                                onOpenAbout = { nav.navigate(Routes.About) },
                                vm = vm,
                            )
                        }
                        composable(Routes.Satellites) {
                            SatelliteListScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(Routes.Compass) {
                            CompassDetailScreen(
                                vm = vm,
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
