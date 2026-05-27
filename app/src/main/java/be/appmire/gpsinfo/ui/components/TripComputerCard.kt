package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.util.TripStats
import java.util.Locale

/**
 * Compact car-style trip computer surfaced on the dashboard. Shows
 * cumulative distance, time and trail count since the user last
 * tapped "Reset". Long-press to open the reset menu — explicit
 * confirmation step avoids accidental wipes on stray taps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TripComputerCard(
    stats: TripStats,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true },
                ),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.trip_computer_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TripCell(
                        label = stringResource(R.string.trip_computer_distance),
                        value = formatDistanceCompact(stats.totalDistanceMeters),
                        modifier = Modifier.weight(1f),
                    )
                    TripCell(
                        label = stringResource(R.string.trip_computer_time),
                        value = formatDurationCompact(stats.totalDurationMillis),
                        modifier = Modifier.weight(1f),
                    )
                    TripCell(
                        label = stringResource(R.string.trip_computer_trails),
                        value = stats.trailCount.toString(),
                        modifier = Modifier.weight(0.7f),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trip_computer_reset)) },
                onClick = {
                    menuOpen = false
                    onReset()
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Restore,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun TripCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
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
