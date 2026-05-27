package be.appmire.gpsinfo.ui.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme

/**
 * One-shot Play Store rating prompt. Surfaced after the user has launched
 * the app enough times to have an opinion (see the threshold in
 * [be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel]). Three ways out:
 *
 *  - **Rate** opens the Play listing and never prompts again.
 *  - **Not now** dismisses but lets the prompt return after more launches.
 *  - **Don't ask again** dismisses permanently.
 *
 * Persistence lives in the ViewModel / [be.appmire.gpsinfo.data.SettingsRepository];
 * this composable is pure UI driven by [show] and the three callbacks.
 */
@Composable
fun RateNudgeDialog(
    show: Boolean,
    onRate: () -> Unit,
    onSnooze: () -> Unit,
    onDecline: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        // Back press / tap-outside is the least committal exit — treat it
        // as "Not now" so the prompt can come back later.
        onDismissRequest = onSnooze,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.rate_nudge_title)) },
        text = {
            Text(
                text = stringResource(R.string.rate_nudge_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            TextButton(onClick = onRate) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rate_nudge_rate))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDecline) {
                    Text(stringResource(R.string.rate_nudge_never))
                }
                TextButton(onClick = onSnooze) {
                    Text(stringResource(R.string.rate_nudge_later))
                }
            }
        },
    )
}

@Preview(name = "RateNudgeDialog")
@Composable
private fun PreviewRateNudgeDialog() {
    GPSinfoTheme(forceDark = true) {
        RateNudgeDialog(show = true, onRate = {}, onSnooze = {}, onDecline = {})
    }
}

@Preview(name = "RateNudgeDialog — light")
@Composable
private fun PreviewRateNudgeDialogLight() {
    GPSinfoTheme(forceDark = false) {
        RateNudgeDialog(show = true, onRate = {}, onSnooze = {}, onDecline = {})
    }
}
