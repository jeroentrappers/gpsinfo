package com.appmire.gpsinfo.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme

@Preview(name = "OnboardingDialog")
@Composable
private fun PreviewOnboardingDialog() {
    GPSinfoTheme(forceDark = true) {
        // `hasSeen = false` is what triggers the dialog to render at all;
        // `hasSeen = true` would no-op the composable.
        OnboardingDialog(hasSeen = false, onDismiss = {})
    }
}

@Preview(name = "OnboardingDialog — light")
@Composable
private fun PreviewOnboardingDialogLight() {
    GPSinfoTheme(forceDark = false) {
        OnboardingDialog(hasSeen = false, onDismiss = {})
    }
}
