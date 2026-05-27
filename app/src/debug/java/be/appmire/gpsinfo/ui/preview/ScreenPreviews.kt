package be.appmire.gpsinfo.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import be.appmire.gpsinfo.ui.about.AboutScreen
import be.appmire.gpsinfo.ui.compass.CompassDetailScreen
import be.appmire.gpsinfo.ui.dashboard.DashboardScreen
import be.appmire.gpsinfo.ui.nmea.NmeaReadoutScreen
import be.appmire.gpsinfo.ui.satellite.SatelliteListScreen
import be.appmire.gpsinfo.ui.speed.SpeedGaugeScreen
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme
import be.appmire.gpsinfo.ui.trails.TrailsListScreen

/**
 * Screen-level previews for everything that takes a [DashboardViewModel].
 *
 * Why this file lives under `app/src/debug/java/...`:
 *   - The fakes it depends on ([PreviewFakes]) are debug-only. Release
 *     R8 never sees them and they cost nothing in production.
 *   - `@Preview` rendering uses the debug variant, so Studio finds these
 *     just fine.
 *
 * `TrailMapScreen` is intentionally absent — osmdroid's tile renderer
 * doesn't initialise inside Studio's preview sandbox (no User-Agent set,
 * no asset/tile cache). A static screenshot would lie about what the
 * screen looks like on device.
 */

@Preview(name = "DashboardScreen", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewDashboardScreen() {
    GPSinfoTheme(forceDark = true) {
        DashboardScreen(
            isDark = true,
            onToggleTheme = {},
            vm = rememberPreviewVm(),
        )
    }
}

@Preview(name = "DashboardScreen — light", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewDashboardScreenLight() {
    GPSinfoTheme(forceDark = false) {
        DashboardScreen(
            isDark = false,
            onToggleTheme = {},
            vm = rememberPreviewVm(),
        )
    }
}

@Preview(name = "DashboardScreen — wide", widthDp = 840, heightDp = 700)
@Composable
private fun PreviewDashboardScreenWide() {
    GPSinfoTheme(forceDark = true) {
        DashboardScreen(
            isDark = true,
            onToggleTheme = {},
            vm = rememberPreviewVm(),
        )
    }
}

@Preview(name = "AboutScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewAboutScreen() {
    GPSinfoTheme(forceDark = true) {
        AboutScreen(vm = rememberPreviewVm(), onBack = {})
    }
}

@Preview(name = "AboutScreen — light", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewAboutScreenLight() {
    GPSinfoTheme(forceDark = false) {
        AboutScreen(vm = rememberPreviewVm(), onBack = {})
    }
}

@Preview(name = "CompassDetailScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewCompassDetailScreen() {
    GPSinfoTheme(forceDark = true) {
        CompassDetailScreen(vm = rememberPreviewVm(), onBack = {})
    }
}

@Preview(name = "SatelliteListScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewSatelliteListScreen() {
    GPSinfoTheme(forceDark = true) {
        SatelliteListScreen(
            vm = rememberPreviewVm(),
            onBack = {},
            onOpenNmea = {},
        )
    }
}

@Preview(name = "SpeedGaugeScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewSpeedGaugeScreen() {
    GPSinfoTheme(forceDark = true) {
        SpeedGaugeScreen(vm = rememberPreviewVm(), onBack = {})
    }
}

@Preview(name = "NmeaReadoutScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewNmeaReadoutScreen() {
    GPSinfoTheme(forceDark = true) {
        NmeaReadoutScreen(vm = rememberPreviewVm(), onBack = {})
    }
}

@Preview(name = "TrailsListScreen", widthDp = 412, heightDp = 800)
@Composable
private fun PreviewTrailsListScreen() {
    GPSinfoTheme(forceDark = true) {
        TrailsListScreen(
            vm = rememberPreviewVm(),
            onBack = {},
            onOpenTrail = {},
        )
    }
}

// The "empty trails" state isn't currently previewed at screen level —
// `rememberPreviewVm()` seeds one trail. To preview the empty state,
// instantiate a `DashboardViewModel` directly with `FakeTrailDataSource(emptyList())`.
