package be.appmire.gpsinfo.ui.sports

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.util.GhostInterpolator
import java.util.Locale

/**
 * "How am I doing vs past me?" panel surfaced during a live recording
 * when the user has picked a ghost trail (Trails list → long-press →
 * Race against this).
 *
 * Compares the live distance covered against the ghost's distance at
 * the same elapsed time. Positive delta = ahead of past self.
 *
 * Hidden entirely when no ghost is set.
 */
@Composable
fun GhostPacerCard(
    ghostTrail: Trail,
    elapsedMillis: Long,
    liveDistanceMetres: Double,
    onClearGhost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ghostDistance = GhostInterpolator.distanceAtElapsedMs(ghostTrail, elapsedMillis)
    val deltaMetres = ghostDistance?.let { liveDistanceMetres - it }
    val ahead = (deltaMetres ?: 0.0) >= 0.0
    val ghostFinished = ghostDistance != null &&
        elapsedMillis >= ghostTrail.durationMillis &&
        ghostTrail.durationMillis > 0L

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ghost_card_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ghostTrail.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = onClearGhost) {
                    Text(stringResource(R.string.ghost_card_clear))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell(
                    label = stringResource(R.string.ghost_card_you),
                    value = formatDistance(liveDistanceMetres),
                )
                StatCell(
                    label = stringResource(R.string.ghost_card_ghost),
                    value = ghostDistance?.let { formatDistance(it) } ?: "—",
                )
                StatCell(
                    label = stringResource(R.string.ghost_card_delta),
                    value = deltaMetres?.let { signed(it) } ?: "—",
                    valueColor = when {
                        deltaMetres == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        ahead -> SignalGreen
                        else -> SignalRed
                    },
                )
            }
            if (ghostFinished) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.ghost_card_finished),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatDistance(metres: Double): String =
    if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
    else "%.2f km".format(Locale.ROOT, metres / 1_000.0)

private fun signed(metres: Double): String {
    val sign = if (metres >= 0) "+" else "−"
    val abs = kotlin.math.abs(metres)
    return if (abs < 1_000.0) "%s%d m".format(Locale.ROOT, sign, abs.toInt())
    else "%s%.2f km".format(Locale.ROOT, sign, abs / 1_000.0)
}
