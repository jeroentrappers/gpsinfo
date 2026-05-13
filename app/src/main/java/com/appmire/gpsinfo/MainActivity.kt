package com.appmire.gpsinfo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmire.gpsinfo.ui.about.AboutScreen
import com.appmire.gpsinfo.ui.compass.CompassDetailScreen
import com.appmire.gpsinfo.ui.dashboard.DashboardScreen
import com.appmire.gpsinfo.ui.dashboard.PermissionRequiredScreen
import com.appmire.gpsinfo.ui.satellite.SatelliteListScreen
import com.appmire.gpsinfo.ui.speed.SpeedGaugeScreen
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var forceDark by remember { mutableStateOf<Boolean?>(null) }
            val effectiveDark = forceDark ?: systemDark

            GPSinfoTheme(forceDark = effectiveDark) {
                val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory())
                val hasPermission by vm.hasPermission.collectAsStateWithLifecycle()

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
                    var screen by remember { mutableStateOf<GpsInfoScreen>(GpsInfoScreen.Dashboard) }
                    BackHandler(enabled = screen != GpsInfoScreen.Dashboard) {
                        screen = GpsInfoScreen.Dashboard
                    }
                    when (screen) {
                        GpsInfoScreen.Dashboard -> DashboardScreen(
                            isDark = effectiveDark,
                            onToggleTheme = { forceDark = !effectiveDark },
                            onOpenSatellites = { screen = GpsInfoScreen.Satellites },
                            onOpenCompass = { screen = GpsInfoScreen.Compass },
                            onOpenSpeed = { screen = GpsInfoScreen.Speed },
                            onOpenAbout = { screen = GpsInfoScreen.About },
                            vm = vm,
                        )
                        GpsInfoScreen.Satellites -> SatelliteListScreen(
                            vm = vm,
                            onBack = { screen = GpsInfoScreen.Dashboard },
                        )
                        GpsInfoScreen.Compass -> CompassDetailScreen(
                            vm = vm,
                            onBack = { screen = GpsInfoScreen.Dashboard },
                        )
                        GpsInfoScreen.Speed -> SpeedGaugeScreen(
                            vm = vm,
                            onBack = { screen = GpsInfoScreen.Dashboard },
                        )
                        GpsInfoScreen.About -> AboutScreen(
                            onBack = { screen = GpsInfoScreen.Dashboard },
                        )
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

private sealed interface GpsInfoScreen {
    data object Dashboard : GpsInfoScreen
    data object Satellites : GpsInfoScreen
    data object Compass : GpsInfoScreen
    data object Speed : GpsInfoScreen
    data object About : GpsInfoScreen
}
