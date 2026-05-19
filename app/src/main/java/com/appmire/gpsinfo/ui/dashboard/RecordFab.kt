package com.appmire.gpsinfo.ui.dashboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.RecordingState
import java.util.Locale

/**
 * Bottom-right FAB on the dashboard. Idle → record button; Recording →
 * extended FAB with elapsed-time + point-count text.
 *
 * No internal ticker — the underlying [RecordingState.Recording.pointCount]
 * re-emits at the GNSS rate, which is plenty to keep the elapsed read
 * fresh enough for a glance.
 */
@Composable
internal fun RecordFab(
    recording: RecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    when (recording) {
        is RecordingState.Idle -> {
            FloatingActionButton(
                onClick = onStart,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.FiberManualRecord,
                    contentDescription = stringResource(R.string.trail_record),
                )
            }
        }
        is RecordingState.Recording -> {
            val elapsedSeconds = (System.currentTimeMillis() - recording.startedAtMillis) / 1000L
            ExtendedFloatingActionButton(
                onClick = onStop,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                icon = {
                    Icon(
                        Icons.Outlined.Stop,
                        contentDescription = stringResource(R.string.trail_stop),
                    )
                },
                text = {
                    Text(
                        stringResource(
                            R.string.trail_recording_stats,
                            recording.pointCount,
                            formatElapsed(elapsedSeconds),
                        ),
                    )
                },
            )
        }
    }
}

internal fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    else "%d:%02d".format(Locale.ROOT, m, s)
}
