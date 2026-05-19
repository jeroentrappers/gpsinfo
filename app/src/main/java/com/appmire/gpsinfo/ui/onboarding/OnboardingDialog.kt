package com.appmire.gpsinfo.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R

/**
 * One-shot first-run tour. Walks through the four primary actions the
 * user might miss otherwise — toggle units / theme is incidental, but
 * "tap the FAB to record" and "tap the map icon to view recorded
 * trails" are not obvious from icon alone.
 *
 * Persistence: shown when [hasSeen] is false. Caller is responsible for
 * flipping it to true on dismissal so the dialog doesn't reappear.
 */
@Composable
fun OnboardingDialog(
    hasSeen: Boolean,
    onDismiss: () -> Unit,
) {
    if (hasSeen) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TourItem(
                    icon = Icons.Outlined.MyLocation,
                    title = stringResource(R.string.onboarding_dashboard_title),
                    body = stringResource(R.string.onboarding_dashboard_body),
                )
                TourItem(
                    icon = Icons.Outlined.FiberManualRecord,
                    title = stringResource(R.string.onboarding_record_title),
                    body = stringResource(R.string.onboarding_record_body),
                )
                TourItem(
                    icon = Icons.Outlined.AddLocationAlt,
                    title = stringResource(R.string.onboarding_waypoint_title),
                    body = stringResource(R.string.onboarding_waypoint_body),
                )
                TourItem(
                    icon = Icons.Outlined.Map,
                    title = stringResource(R.string.onboarding_trails_title),
                    body = stringResource(R.string.onboarding_trails_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_dismiss))
            }
        },
    )
}

@Composable
private fun TourItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
