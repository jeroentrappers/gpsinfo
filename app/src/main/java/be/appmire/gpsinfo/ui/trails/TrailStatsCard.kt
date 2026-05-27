package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import be.appmire.gpsinfo.util.TrailScoring
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.formatPace
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.paceSecondsPerUnit
import be.appmire.gpsinfo.util.paceUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel
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
    hrZoneConfig: HrZoneConfig? = null,
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
            // Pace row — only meaningful when there's enough speed to
            // compute a pace from. Below MIN_PACE_SPEED both helpers
            // return null and the row drops to "— — —", so hide it.
            val avgPaceSec = paceSecondsPerUnit(avgKmh.takeIf { it > 0f }, unitSystem)
            val bestPaceSec = paceSecondsPerUnit(maxKmh.takeIf { it > 0f }, unitSystem)
            if (avgPaceSec != null || bestPaceSec != null) {
                val paceUnit = paceUnitLabel(unitSystem)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell(
                        label = stringResource(R.string.trail_stats_avg_pace),
                        value = avgPaceSec?.let { "${formatPace(it)} $paceUnit" } ?: DASH,
                    )
                    StatCell(
                        label = stringResource(R.string.trail_stats_best_pace),
                        value = bestPaceSec?.let { "${formatPace(it)} $paceUnit" } ?: DASH,
                    )
                    // Empty trailing cell so the SpaceBetween alignment
                    // matches the rows above without re-balancing weights.
                    StatCell(label = "", value = "")
                }
            }

            // HR summary row — only when the trail carries BPM samples.
            val avgHr = trail.avgHr
            val maxHr = trail.maxHr
            if (avgHr != null && maxHr != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell(
                        label = stringResource(R.string.trail_stats_avg_hr),
                        value = "$avgHr BPM",
                    )
                    StatCell(
                        label = stringResource(R.string.trail_stats_max_hr),
                        value = "$maxHr BPM",
                    )
                    StatCell(label = "", value = "")
                }
                // Time-in-zone strip only when we know how to colour
                // the bands (i.e. we have a zone config).
                if (hrZoneConfig != null) {
                    TimeInZoneStrip(trail = trail, config = hrZoneConfig)
                }
            }

            // Score badge — pace adherence vs the persisted target, plus
            // HR-zone adherence when the trail carries HR samples + we
            // know the zones. Combined score is 50 % pace + 50 % HR;
            // falls back to pace-only without HR data.
            trail.targetPaceSecondsPerKm?.let { targetKm ->
                val targetForUnit = TrailScoring.targetPaceInUnit(targetKm, unitSystem)
                val timeInZone = if (hrZoneConfig != null && trail.hrSamples.isNotEmpty()) {
                    trail.timeInZoneSeconds(hrZoneConfig)
                } else IntArray(0)
                val score = TrailScoring.scoreCombined(
                    avgSpeedKmh = avgKmh,
                    targetPaceSecondsPerUnit = targetForUnit,
                    unitSystem = unitSystem,
                    timeInZoneSeconds = timeInZone,
                )
                if (score != null) ScoreBadge(score = score, unitSystem = unitSystem)
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: TrailScoring.TrailScore, unitSystem: UnitSystem) {
    val color = scoreColor(score.overall)
    val paceUnit = paceUnitLabel(unitSystem)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.trail_stats_score).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.trail_stats_score_pace,
                            formatPace(score.actualPaceSecondsPerUnit),
                            formatPace(score.targetPaceSecondsPerUnit),
                            paceUnit,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${score.overall}",
                        style = MaterialTheme.typography.displaySmall,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Sub-score breakdown — only show when HR also contributed,
            // otherwise the overall = pace and the extra line is noise.
            if (score.hrScore != null) {
                Text(
                    text = stringResource(
                        R.string.trail_stats_score_breakdown,
                        score.paceScore,
                        score.hrScore,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Horizontal coloured strip showing the proportion of recording time
 *  spent in each HR zone — at a glance "did I spend my run in the
 *  zone I meant to?" */
@Composable
private fun TimeInZoneStrip(trail: Trail, config: HrZoneConfig) {
    val perZoneSec = remember(trail, config) { trail.timeInZoneSeconds(config) }
    val total = perZoneSec.sum()
    if (total <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        for (i in 0..4) {
            val weight = (perZoneSec[i].toFloat() / total).coerceAtLeast(0f)
            if (weight <= 0f) continue
            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(10.dp)
                    .background(zoneStripColor(i + 1)),
            )
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 85 -> SignalGreen
    score >= 60 -> SignalYellow
    score >= 30 -> SignalOrange
    else -> SignalRed
}

private fun zoneStripColor(zone: Int): Color = when (zone) {
    1 -> SignalGreen.copy(alpha = 0.55f)
    2 -> SignalGreen
    3 -> SignalYellow
    4 -> SignalOrange
    else -> SignalRed
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
