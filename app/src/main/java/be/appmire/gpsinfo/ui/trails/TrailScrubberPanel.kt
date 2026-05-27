package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.speedUnitLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Speed + elevation chart with a slider underneath that scrubs an index
 * along the trail. Tapping the chart and dragging the slider are two
 * paths into the same `selectedIndex` state. Whatever's selected is
 * pushed to [onPointSelected] so the parent screen can paint a marker
 * on the map.
 *
 * The cursor's stats are shown above the chart — speed, elevation, and
 * timestamp at the selected sample — so the user always has a number to
 * read while scrubbing.
 */
@Composable
fun TrailScrubberPanel(
    trail: Trail,
    unitSystem: UnitSystem,
    speedColor: Color,
    elevationColor: Color,
    onPointSelected: (TrailPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = trail.points.size
    if (n < 2) return

    // The slider value is a 0..1 fraction over the index space, not the
    // raw index, so dragging stays smooth on very long trails.
    var fraction by remember(trail.id, n) { mutableFloatStateOf(0f) }

    val selectedIdx = ((n - 1) * fraction).toInt().coerceIn(0, n - 1)
    val selected = trail.points[selectedIdx]

    LaunchedEffect(selectedIdx, trail.id) { onPointSelected(selected) }

    val timeFmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CursorStat(
                    label = stringResource(R.string.metric_speed),
                    value = selected.speedMps?.let {
                        "%.1f %s".format(
                            Locale.ROOT,
                            UnitConverter.speedFromKmh(it * 3.6f, unitSystem),
                            speedUnitLabel(unitSystem),
                        )
                    } ?: DASH,
                )
                CursorStat(
                    label = stringResource(R.string.metric_altitude),
                    value = selected.eleMeters?.let {
                        "%.0f m".format(Locale.ROOT, it)
                    } ?: DASH,
                )
                CursorStat(
                    label = stringResource(R.string.metric_utc),
                    value = timeFmt.format(Date(selected.timeMillis)),
                )
            }
            TrailProfileChart(
                trail = trail,
                speedColor = speedColor,
                elevationColor = elevationColor,
                onPointSelected = { p ->
                    // When the user taps the chart, snap the slider to
                    // that point's index. We round-trip via fraction so
                    // the slider and chart stay in lockstep.
                    val idx = trail.points.indexOf(p).coerceAtLeast(0)
                    fraction = if (n > 1) idx.toFloat() / (n - 1) else 0f
                },
            )
            Slider(
                value = fraction,
                onValueChange = { fraction = it },
                colors = SliderDefaults.colors(
                    thumbColor = speedColor,
                    activeTrackColor = speedColor,
                ),
            )
        }
    }
}

@Composable
private fun CursorStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private const val DASH = "—"
