package com.appmire.gpsinfo.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme

@Preview(name = "SettingsSection — metric")
@Composable
private fun PreviewSettingsSectionMetric() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SettingsSection(
                unitSystem = UnitSystem.Metric,
                onUnitSystemChange = {},
            )
        }
    }
}

@Preview(name = "SettingsSection — imperial")
@Composable
private fun PreviewSettingsSectionImperial() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SettingsSection(
                unitSystem = UnitSystem.Imperial,
                onUnitSystemChange = {},
            )
        }
    }
}

@Preview(name = "SettingsSection — light")
@Composable
private fun PreviewSettingsSectionLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SettingsSection(
                unitSystem = UnitSystem.Metric,
                onUnitSystemChange = {},
            )
        }
    }
}
