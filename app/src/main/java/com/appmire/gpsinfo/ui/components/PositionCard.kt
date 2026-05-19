package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.util.CoordinateFormat
import com.appmire.gpsinfo.util.CoordinateFormatter
import com.appmire.gpsinfo.util.IntentHelpers
import com.appmire.gpsinfo.util.UnitConverter
import com.appmire.gpsinfo.util.lengthUnitLabel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PositionCard(
    latDeg: Double?,
    lonDeg: Double?,
    altMeters: Double?,
    hAccuracyMeters: Float?,
    vAccuracyMeters: Float?,
    format: CoordinateFormat,
    onToggleFormat: () -> Unit,
    unitSystem: UnitSystem = UnitSystem.Metric,
) {
    val ctx = LocalContext.current
    val copyLabel = stringResource(R.string.section_position)
    val copyToast = stringResource(R.string.coords_copied)
    SectionCard(
        title = stringResource(R.string.section_position),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onToggleFormat) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = stringResource(R.string.action_toggle_units))
                }
            }
        }
    ) {
        Column {
            val dash = stringResource(R.string.placeholder_dash)
            if (latDeg != null && lonDeg != null) {
                val formatted = CoordinateFormatter.format(latDeg, lonDeg, format)
                // Long-press anywhere on the coords copies them to the
                // clipboard in "lat, lon" form. Short-press toggles
                // DMS / decimal via the existing onToggleFormat callback.
                val copyAction = {
                    IntentHelpers.copyToClipboard(
                        ctx,
                        copyLabel,
                        "${formatted.lat}, ${formatted.lon}",
                    )
                    android.widget.Toast.makeText(
                        ctx, copyToast, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                val coordsModifier = Modifier.combinedClickable(
                    onClick = onToggleFormat,
                    onLongClick = copyAction,
                )
                Column(modifier = coordsModifier) {
                    Text(formatted.lat, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text(formatted.lon, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(dash, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))

            val lengthLabel = lengthUnitLabel(unitSystem)
            val altDisplay = altMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
            val hAccDisplay = hAccuracyMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
            val vAccDisplay = vAccuracyMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCell(stringResource(R.string.metric_altitude), altDisplay?.let { "${it.toInt()} $lengthLabel" } ?: dash)
                MetricCell(stringResource(R.string.metric_h_accuracy), hAccDisplay?.let { "±${it.toInt()} $lengthLabel" } ?: dash)
                MetricCell(stringResource(R.string.metric_v_accuracy), vAccDisplay?.let { "±${it.toInt()} $lengthLabel" } ?: dash)
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(
                    enabled = latDeg != null && lonDeg != null,
                    onClick = { latDeg?.let { la -> lonDeg?.let { lo -> IntentHelpers.shareLocation(ctx, la, lo, altMeters) } } }
                ) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share_location)) }
                FilledTonalIconButton(
                    enabled = latDeg != null && lonDeg != null,
                    onClick = { latDeg?.let { la -> lonDeg?.let { lo -> IntentHelpers.openInMaps(ctx, la, lo) } } }
                ) { Icon(Icons.Outlined.Map, contentDescription = stringResource(R.string.action_open_maps)) }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
