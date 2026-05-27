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
import be.appmire.gpsinfo.data.model.LapMarker
import java.util.Locale

/**
 * Renders the lap splits collected during a recording. Hidden entirely
 * when no laps were marked — laps are an opt-in feature, and a runner
 * who never tapped the button shouldn't see an empty table.
 */
@Composable
fun LapsCard(laps: List<LapMarker>, modifier: Modifier = Modifier) {
    if (laps.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.laps_section_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LapHeaderRow()
            Spacer(Modifier.height(4.dp))
            for (lap in laps) LapRow(lap)
        }
    }
}

@Composable
private fun LapHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderCell(stringResource(R.string.laps_column_lap), weight = 0.5f)
        HeaderCell(stringResource(R.string.laps_column_distance), weight = 1f)
        HeaderCell(stringResource(R.string.laps_column_time), weight = 1f)
        HeaderCell(stringResource(R.string.laps_column_pace), weight = 1f)
        HeaderCell(stringResource(R.string.laps_column_hr), weight = 0.7f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun LapRow(lap: LapMarker) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell(lap.index.toString(), weight = 0.5f, emphasis = true)
        Cell(formatKm(lap.lapDistanceM), weight = 1f)
        Cell(formatDuration(lap.lapDurationMs), weight = 1f)
        Cell(formatPace(lap.lapDurationMs, lap.lapDistanceM), weight = 1f)
        Cell(lap.avgHrBpm?.toString() ?: "—", weight = 0.7f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    emphasis: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.weight(weight),
    )
}

private fun formatKm(metres: Double): String =
    if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
    else "%.2f km".format(Locale.ROOT, metres / 1_000.0)

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(Locale.ROOT, m, s)
}

private fun formatPace(durationMs: Long, distanceM: Double): String {
    if (distanceM < 1.0 || durationMs <= 0L) return "—"
    val secPerKm = (durationMs.toDouble() / 1000.0) * (1000.0 / distanceM)
    val m = (secPerKm / 60.0).toInt()
    val s = (secPerKm % 60.0).toInt()
    return "%d:%02d /km".format(Locale.ROOT, m, s)
}
