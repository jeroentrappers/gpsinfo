package be.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme

/**
 * Previews for the dashboard's leaf-level composables — the bits the
 * dashboard frame is built from but that aren't dashboard cards.
 *
 * Why a separate file instead of folding into `ui/components/Previews.kt`:
 * these subjects live in the `ui.dashboard` package; co-locating their
 * previews keeps each package self-contained for navigation.
 */

@Preview(name = "LocationDisabledBanner")
@Composable
private fun PreviewLocationDisabledBanner() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            LocationDisabledBanner(onOpenSettings = {})
        }
    }
}

@Preview(name = "CompassCalibrationBanner")
@Composable
private fun PreviewCompassCalibrationBanner() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            CompassCalibrationBanner(onOpenCalibration = {})
        }
    }
}

@Preview(name = "CopyrightFooter")
@Composable
private fun PreviewCopyrightFooter() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            CopyrightFooter(onClick = {})
        }
    }
}

@Preview(name = "SaveTrailDialog")
@Composable
private fun PreviewSaveTrailDialog() {
    GPSinfoTheme(forceDark = true) {
        SaveTrailDialog(
            onCancel = {},
            onDiscard = {},
            onSave = {},
        )
    }
}

@Preview(name = "PermissionRequiredScreen")
@Composable
private fun PreviewPermissionRequiredScreen() {
    GPSinfoTheme(forceDark = true) {
        PermissionRequiredScreen(onRequest = {})
    }
}

@Preview(name = "PermissionRequiredScreen — light")
@Composable
private fun PreviewPermissionRequiredScreenLight() {
    GPSinfoTheme(forceDark = false) {
        PermissionRequiredScreen(onRequest = {})
    }
}
