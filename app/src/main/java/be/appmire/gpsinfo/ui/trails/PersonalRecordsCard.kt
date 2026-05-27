package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.util.PersonalRecords
import java.util.Locale

/**
 * Aggregated personal-best summary surfaced at the top of the trails
 * list. Cheap to render — relies entirely on the already-computed
 * fields on each [TrailSummary].
 */
@Composable
fun PersonalRecordsCard(records: PersonalRecords, modifier: Modifier = Modifier) {
    if (!records.hasAny) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(R.string.pr_section_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PrTile(
                    label = stringResource(R.string.pr_trail_count),
                    value = records.trailCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                PrTile(
                    label = stringResource(R.string.pr_total_distance),
                    value = formatDistanceCompact(records.totalDistanceMeters),
                    modifier = Modifier.weight(1f),
                )
                PrTile(
                    label = stringResource(R.string.pr_total_time),
                    value = formatDurationCompact(records.totalDurationMillis),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            records.longest?.let { t ->
                PrRecordRow(
                    label = stringResource(R.string.pr_longest),
                    primary = formatDistanceCompact(t.distanceMeters),
                    name = t.name,
                )
            }
            records.fastestAvg?.let { t ->
                PrRecordRow(
                    label = stringResource(R.string.pr_fastest_avg),
                    primary = "%.1f km/h".format(Locale.ROOT, t.avgSpeedKmh),
                    name = t.name,
                )
            }
            records.biggestClimb?.let { t ->
                PrRecordRow(
                    label = stringResource(R.string.pr_biggest_climb),
                    primary = "%.0f m".format(Locale.ROOT, t.ascentMeters),
                    name = t.name,
                )
            }
            records.longestDuration?.let { t ->
                PrRecordRow(
                    label = stringResource(R.string.pr_longest_duration),
                    primary = formatDurationCompact(t.durationMillis),
                    name = t.name,
                )
            }
        }
    }
}

@Composable
private fun PrTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrRecordRow(label: String, primary: String, name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = primary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(0.4f),
        )
    }
}

private fun formatDistanceCompact(metres: Double): String =
    if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
    else "%.1f km".format(Locale.ROOT, metres / 1_000.0)

private fun formatDurationCompact(millis: Long): String {
    if (millis <= 0L) return "—"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "%dh %02dm".format(Locale.ROOT, hours, minutes)
    else "%dm".format(Locale.ROOT, minutes)
}
