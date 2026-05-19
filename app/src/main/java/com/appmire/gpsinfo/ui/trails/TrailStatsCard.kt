package com.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.data.model.Trail
import com.appmire.gpsinfo.util.UnitConverter
import com.appmire.gpsinfo.util.lengthUnitLabel
import com.appmire.gpsinfo.util.speedUnitLabel
import java.util.Locale

/**
 * Compact at-a-glance summary of a recorded trail, rendered as a slightly
 * translucent card floating over the map. Always-visible stats: distance,
 * duration, average + max speed, ascent + descent. Falls back to em-dash
 * for any quantity that's zero / unknown, so an empty single-point
 * waypoint doesn't surface noise.
 */
@Composable
fun TrailStatsCard(
    trail: Trail,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val distance = trail.distanceMeters
    val ascent = trail.ascentMeters
    val descent = trail.descentMeters
    val avgKmh = trail.avgSpeedKmh
    val maxKmh = trail.maxSpeedKmh

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell(
                    label = stringResource(R.string.trail_stats_distance),
                    value = if (distance > 0.0) formatDistance(distance, unitSystem) else DASH,
                )
                StatCell(
                    label = stringResource(R.string.trail_stats_duration),
                    value = if (trail.durationMillis > 0L) formatDuration(trail.durationMillis) else DASH,
                )
                StatCell(
                    label = stringResource(R.string.trail_stats_avg_speed),
                    value = if (avgKmh > 0f) formatSpeed(avgKmh, unitSystem) else DASH,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell(
                    label = stringResource(R.string.trail_stats_max_speed),
                    value = if (maxKmh > 0f) formatSpeed(maxKmh, unitSystem) else DASH,
                )
                StatCell(
                    label = stringResource(R.string.trail_stats_ascent),
                    value = if (ascent > 0.0) formatVertical(ascent, unitSystem) else DASH,
                )
                StatCell(
                    label = stringResource(R.string.trail_stats_descent),
                    value = if (descent > 0.0) formatVertical(descent, unitSystem) else DASH,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun formatDistance(metres: Double, unit: UnitSystem): String = when (unit) {
    UnitSystem.Metric -> if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
                        else "%.2f km".format(Locale.ROOT, metres / 1_000.0)
    UnitSystem.Imperial -> {
        val ft = UnitConverter.lengthFromMeters(metres, unit)
        if (ft < 5_280) "%d ft".format(Locale.ROOT, ft.toInt())
        else "%.2f mi".format(Locale.ROOT, ft / 5_280.0)
    }
    UnitSystem.Nautical -> "%.2f NM".format(Locale.ROOT, metres / 1_852.0)
}

@Composable
private fun formatSpeed(kmh: Float, unit: UnitSystem): String {
    val v = UnitConverter.speedFromKmh(kmh, unit)
    return "%.1f %s".format(Locale.ROOT, v, speedUnitLabel(unit))
}

@Composable
private fun formatVertical(metres: Double, unit: UnitSystem): String {
    val v = UnitConverter.lengthFromMeters(metres, unit)
    return "%d %s".format(Locale.ROOT, v.toInt(), lengthUnitLabel(unit))
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    else "%d:%02d".format(Locale.ROOT, m, s)
}

private const val DASH = "—"
