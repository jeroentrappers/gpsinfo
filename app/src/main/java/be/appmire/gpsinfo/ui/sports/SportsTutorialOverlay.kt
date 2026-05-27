package be.appmire.gpsinfo.ui.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R

/**
 * First-run tutorial for the Sports view. A single dialog enumerating
 * the gauges and what they mean — cheaper than a tap-through showcase
 * pattern, and equally effective for a screen the runner is
 * stationary for when they first see it.
 *
 * Dismiss-only — "Got it" closes the dialog and persists the seen flag
 * via [onDismiss]. The dialog never re-appears unless the user resets
 * the tour from Settings.
 */
@Composable
fun SportsTutorialOverlay(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sports_tutorial_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.sports_tutorial_lede),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                GaugeHint(
                    title = stringResource(R.string.sports_tutorial_pace_title),
                    body = stringResource(R.string.sports_tutorial_pace_body),
                )
                GaugeHint(
                    title = stringResource(R.string.sports_tutorial_hr_title),
                    body = stringResource(R.string.sports_tutorial_hr_body),
                )
                GaugeHint(
                    title = stringResource(R.string.sports_tutorial_intensity_title),
                    body = stringResource(R.string.sports_tutorial_intensity_body),
                )
                GaugeHint(
                    title = stringResource(R.string.sports_tutorial_cues_title),
                    body = stringResource(R.string.sports_tutorial_cues_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sports_tutorial_dismiss))
            }
        },
    )
}

@Composable
private fun GaugeHint(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
