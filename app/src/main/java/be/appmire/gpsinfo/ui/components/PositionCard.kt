package be.appmire.gpsinfo.ui.components

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
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.util.CoordinateFormat
import be.appmire.gpsinfo.util.CoordinateFormatter
import be.appmire.gpsinfo.util.FormattedCoord
import be.appmire.gpsinfo.util.IntentHelpers
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.lengthUnitLabel

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
    /** Optional one-line context (e.g. "Heading to Waypoint 3, ETA 14:32")
     *  appended to the shared payload when present. Lets the recipient
     *  see *intent* alongside the literal coords. */
    navContextLine: String? = null,
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
                // clipboard. Short-press cycles through the formats.
                val copyAction = {
                    IntentHelpers.copyToClipboard(
                        ctx,
                        copyLabel,
                        CoordinateFormatter.copyString(formatted),
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
                    when (formatted) {
                        is FormattedCoord.Pair -> {
                            Text(formatted.lat, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                            Text(formatted.lon, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        is FormattedCoord.Single -> {
                            // Single-line formats (Plus Code, Maidenhead, MGRS)
                            // need a smaller type size — they're shorter as a
                            // string but live on one line so they fit
                            // comfortably under headline rather than display.
                            Text(
                                formatted.text,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = formatLabelFor(format),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Text(dash, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.acquiring_fix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    onClick = {
                        latDeg?.let { la ->
                            lonDeg?.let { lo ->
                                // Battery is queried at share-time
                                // (not held in state) — one-shot is
                                // cheap and means the shared number
                                // is always current.
                                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
                                    as? android.os.BatteryManager
                                val battery = bm?.getIntProperty(
                                    android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
                                )?.takeIf { it in 0..100 }
                                IntentHelpers.shareLocation(
                                    ctx, la, lo, altMeters,
                                    navContextLine = navContextLine,
                                    batteryPct = battery,
                                )
                            }
                        }
                    }
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
private fun formatLabelFor(format: CoordinateFormat): String = when (format) {
    CoordinateFormat.DMS, CoordinateFormat.DECIMAL -> ""
    CoordinateFormat.PLUS_CODE -> stringResource(R.string.coord_format_plus_code)
    CoordinateFormat.MAIDENHEAD -> stringResource(R.string.coord_format_maidenhead)
    CoordinateFormat.MGRS -> stringResource(R.string.coord_format_mgrs)
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
