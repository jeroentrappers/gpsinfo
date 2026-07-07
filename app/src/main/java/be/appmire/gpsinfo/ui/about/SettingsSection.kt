package be.appmire.gpsinfo.ui.about

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.DashboardDensity
import be.appmire.gpsinfo.data.UnitSystem

/**
 * Compact settings block: unit-system selector (3-way segmented), a
 * language picker that opens a dialog, and a "Show tour again" entry.
 * Language switching only takes effect on Android 13+ — on older
 * versions LocaleManager isn't available and the row is hidden.
 */
@Composable
fun SettingsSection(
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onShowTour: () -> Unit = {},
    onOpenHrPair: () -> Unit = {},
    onOpenHrZones: () -> Unit = {},
    onOpenCpPair: () -> Unit = {},
    onOpenSharePosition: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    onOpenStrideCalibration: () -> Unit = {},
    onOpenDashboardEditor: () -> Unit = {},
    onOpenObdLab: () -> Unit = {},
    onOpenVoiceGuidance: () -> Unit = {},
    nmeaLoggingEnabled: Boolean = false,
    onNmeaLoggingEnabledChange: (Boolean) -> Unit = {},
    nmeaBtBridgeEnabled: Boolean = false,
    onNmeaBtBridgeEnabledChange: (Boolean) -> Unit = {},
    altitudeSmoothEnabled: Boolean = false,
    onAltitudeSmoothEnabledChange: (Boolean) -> Unit = {},
    audibleCuesEnabled: Boolean = false,
    onAudibleCuesEnabledChange: (Boolean) -> Unit = {},
    vibrationCuesEnabled: Boolean = false,
    onVibrationCuesEnabledChange: (Boolean) -> Unit = {},
    dashboardDensity: DashboardDensity = DashboardDensity.Standard,
    onDashboardDensityChange: (DashboardDensity) -> Unit = {},
    carOverlaySpeed: Boolean = true,
    onCarOverlaySpeedChange: (Boolean) -> Unit = {},
    carOverlaySpeedLimit: Boolean = true,
    onCarOverlaySpeedLimitChange: (Boolean) -> Unit = {},
    carOverlayCluster: Boolean = false,
    onCarOverlayClusterChange: (Boolean) -> Unit = {},
    carOverlayCompass: Boolean = false,
    onCarOverlayCompassChange: (Boolean) -> Unit = {},
    carOverlayRecordingStrip: Boolean = false,
    onCarOverlayRecordingStripChange: (Boolean) -> Unit = {},
    carOverlayRallyPanel: Boolean = false,
    onCarOverlayRallyPanelChange: (Boolean) -> Unit = {},
    carLiveGlMap: Boolean = false,
    onCarLiveGlMapChange: (Boolean) -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.screen_settings).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.padding(top = 12.dp))

            UnitsRow(unitSystem = unitSystem, onChange = onUnitSystemChange)

            Spacer(Modifier.padding(top = 16.dp))
            DensityRow(density = dashboardDensity, onChange = onDashboardDensityChange)

            Spacer(Modifier.padding(top = 8.dp))
            DashboardEditorRow(onOpen = onOpenDashboardEditor)

            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.padding(top = 16.dp))
                LanguageRow()
            }

            Spacer(Modifier.padding(top = 16.dp))
            HeartRatePairRow(onOpenHrPair = onOpenHrPair)

            Spacer(Modifier.padding(top = 8.dp))
            HrZonesRow(onOpenHrZones = onOpenHrZones)

            Spacer(Modifier.padding(top = 8.dp))
            CyclingPowerPairRow(onOpenCpPair = onOpenCpPair)

            Spacer(Modifier.padding(top = 8.dp))
            ObdLabRow(onOpen = onOpenObdLab)

            Spacer(Modifier.padding(top = 8.dp))
            VoiceGuidanceRow(onOpen = onOpenVoiceGuidance)

            Spacer(Modifier.padding(top = 8.dp))
            SharePositionRow(onOpenSharePosition = onOpenSharePosition)

            Spacer(Modifier.padding(top = 8.dp))
            WaypointsRow(onOpenWaypoints = onOpenWaypoints)

            Spacer(Modifier.padding(top = 8.dp))
            StrideCalibrationRow(onOpen = onOpenStrideCalibration)

            Spacer(Modifier.padding(top = 16.dp))
            AudibleCuesRow(
                enabled = audibleCuesEnabled,
                onChange = onAudibleCuesEnabledChange,
            )

            Spacer(Modifier.padding(top = 8.dp))
            VibrationCuesRow(
                enabled = vibrationCuesEnabled,
                onChange = onVibrationCuesEnabledChange,
            )

            Spacer(Modifier.padding(top = 8.dp))
            NmeaLoggingRow(
                enabled = nmeaLoggingEnabled,
                onChange = onNmeaLoggingEnabledChange,
            )

            Spacer(Modifier.padding(top = 8.dp))
            NmeaBtBridgeRow(
                enabled = nmeaBtBridgeEnabled,
                onChange = onNmeaBtBridgeEnabledChange,
            )

            Spacer(Modifier.padding(top = 8.dp))
            AltitudeSmoothRow(
                enabled = altitudeSmoothEnabled,
                onChange = onAltitudeSmoothEnabledChange,
            )

            Spacer(Modifier.padding(top = 20.dp))
            Text(
                text = stringResource(R.string.settings_car_overlays_label).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_car_overlays_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 12.dp))
            // Speed + speed-limit are always-on editable elements now (hide via
            // the on-surface edit-mode Remove), so they no longer have toggles.
            ToggleRow(
                R.string.settings_car_overlay_cluster,
                R.string.settings_car_overlay_cluster_body,
                carOverlayCluster, onCarOverlayClusterChange,
            )
            Spacer(Modifier.padding(top = 8.dp))
            ToggleRow(
                R.string.settings_car_overlay_compass,
                R.string.settings_car_overlay_compass_body,
                carOverlayCompass, onCarOverlayCompassChange,
            )
            Spacer(Modifier.padding(top = 8.dp))
            ToggleRow(
                R.string.settings_car_overlay_recording,
                R.string.settings_car_overlay_recording_body,
                carOverlayRecordingStrip, onCarOverlayRecordingStripChange,
            )
            Spacer(Modifier.padding(top = 8.dp))
            ToggleRow(
                R.string.settings_car_overlay_rally,
                R.string.settings_car_overlay_rally_body,
                carOverlayRallyPanel, onCarOverlayRallyPanelChange,
            )
            Spacer(Modifier.padding(top = 8.dp))
            ToggleRow(
                R.string.settings_car_live_gl_map,
                R.string.settings_car_live_gl_map_body,
                carLiveGlMap, onCarLiveGlMapChange,
            )

            Spacer(Modifier.padding(top = 16.dp))
            ShowTourRow(onShowTour = onShowTour)
        }
    }
}

/** A title + subtitle row with a trailing switch — the shared shape of the
 *  toggle rows above (NMEA, cues, car overlays). */
@Composable
private fun ToggleRow(
    titleRes: Int,
    bodyRes: Int,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun DensityRow(density: DashboardDensity, onChange: (DashboardDensity) -> Unit) {
    Text(
        text = stringResource(R.string.settings_density_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 6.dp))
    val options = listOf(
        DashboardDensity.Standard to R.string.settings_density_standard,
        DashboardDensity.Glanceable to R.string.settings_density_glanceable,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (value, labelRes) ->
            SegmentedButton(
                selected = value == density,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun AltitudeSmoothRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_altitude_smooth),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_altitude_smooth_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun NmeaBtBridgeRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_nmea_bt_bridge),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_nmea_bt_bridge_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun NmeaLoggingRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_nmea_logging),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_nmea_logging_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun VibrationCuesRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_vibration_cues),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_vibration_cues_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun AudibleCuesRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange(!enabled) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_audible_cues),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_audible_cues_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

@Composable
private fun DashboardEditorRow(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_dashboard_editor),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StrideCalibrationRow(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_stride_calibration),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HrZonesRow(onOpenHrZones: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenHrZones),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_hr_zones),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HeartRatePairRow(onOpenHrPair: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenHrPair),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_pair_hr),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WaypointsRow(onOpenWaypoints: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenWaypoints),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_waypoints),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SharePositionRow(onOpenSharePosition: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSharePosition),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_share_position),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CyclingPowerPairRow(onOpenCpPair: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCpPair),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_pair_cp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun VoiceGuidanceRow(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_voice_guidance),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ObdLabRow(onOpen: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_obd_lab),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ShowTourRow(onShowTour: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onShowTour),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_show_tour),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun UnitsRow(
    unitSystem: UnitSystem,
    onChange: (UnitSystem) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_units_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 6.dp))
    val options = listOf(
        UnitSystem.Metric to R.string.unit_system_metric,
        UnitSystem.Imperial to R.string.unit_system_imperial,
        UnitSystem.Nautical to R.string.unit_system_nautical,
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { idx, (value, labelRes) ->
            SegmentedButton(
                selected = unitSystem == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
            ) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@androidx.annotation.RequiresApi(33)
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val localeManager = remember(context) {
        context.getSystemService(LocaleManager::class.java)
    } ?: return  // System service should always exist on API 33+; bail if not.
    var showDialog by remember { mutableStateOf(false) }

    val currentTag: String? = remember(localeManager) {
        val list = localeManager.applicationLocales
        if (list.isEmpty) null else list[0].toLanguageTag()
    }
    val systemDefaultLabel = stringResource(R.string.language_system)
    val currentLabel = AppLanguages.all
        .firstOrNull { AppLanguages.matches(it.tag, currentTag) }
        ?.let { if (it.tag == null) systemDefaultLabel else it.endonym }
        ?: systemDefaultLabel

    Text(
        text = stringResource(R.string.settings_language_label),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.padding(top = 6.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = currentLabel,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
            title = { Text(stringResource(R.string.settings_language_label)) },
            text = {
                // The list is taller than a typical dialog content; wrap in a
                // scroll so all 13 options stay reachable on short screens.
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    AppLanguages.all.forEach { lang ->
                        val label = if (lang.tag == null) systemDefaultLabel else lang.endonym
                        val selected = AppLanguages.matches(lang.tag, currentTag)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val list = if (lang.tag.isNullOrEmpty())
                                        LocaleList.getEmptyLocaleList()
                                    else
                                        LocaleList.forLanguageTags(lang.tag)
                                    localeManager.applicationLocales = list
                                    showDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
        )
    }
}

/** BCP47 tags can be returned with either dash or region-script variants;
 *  match leniently so "pt-BR" in our list also matches "pt-Latn-BR" etc. */
