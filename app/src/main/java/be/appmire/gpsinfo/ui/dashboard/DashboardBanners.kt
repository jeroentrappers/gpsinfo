package be.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R

/**
 * Banner shown above the dashboard cards when the rotation-vector
 * accuracy is LOW or UNRELIABLE — the cue for a figure-of-eight motion.
 * Tapping it opens the dedicated calibration screen.
 */
@Composable
internal fun CompassCalibrationBanner(onOpenCalibration: () -> Unit) {
    val description = stringResource(R.string.compass_calibrate_title)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCalibration, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Closest stock icon to "figure-8 motion" — a rotational
            // arrow loop. Good enough as a visual hint without shipping
            // a custom vector.
            Icon(
                imageVector = Icons.Outlined.Loop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.compass_calibrate_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(R.string.compass_calibrate_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Banner shown above the dashboard cards when the recorder is
 * auto-paused (stationary > 30 s) or when the user has manually
 * paused. Surfaces a Resume button — even though movement triggers
 * auto-resume, the explicit button lets a runner kick the recorder
 * back on without taking a real step (e.g. cleared a red light and
 * is already running).
 */
@Composable
internal fun AutoPausedBanner(paused: Boolean, onResume: () -> Unit, onPauseManually: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PauseCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (paused) R.string.auto_pause_paused_title
                        else R.string.auto_pause_idle_title
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(
                        if (paused) R.string.auto_pause_paused_body
                        else R.string.auto_pause_idle_body
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            FilledTonalButton(onClick = if (paused) onResume else onPauseManually) {
                Icon(
                    imageVector = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.PauseCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Banner shown when a newer release has been seen on GitHub than the
 * installed build. Tapping the banner opens the Play Store listing; the
 * trailing close button dismisses it for this version (a later release
 * brings it back). Availability is decided upstream by the ViewModel —
 * this composable only renders when there's genuinely something newer.
 */
@Composable
internal fun UpdateAvailableBanner(
    versionName: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val description = stringResource(R.string.update_available_title)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUpdate, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.update_available_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.update_available_body, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.update_dismiss),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Banner shown when the system Location toggle is off. Tapping opens
 * the OS Location settings screen — the dashboard would otherwise show
 * perpetual NO_FIX with no actionable cue.
 */
@Composable
internal fun LocationDisabledBanner(onOpenSettings: () -> Unit) {
    val description = stringResource(R.string.location_off_open_settings)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.location_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.location_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(R.string.location_off_open_settings),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
