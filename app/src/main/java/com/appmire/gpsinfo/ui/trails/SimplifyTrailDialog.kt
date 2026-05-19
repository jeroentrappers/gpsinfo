package com.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.gpx.TrailSimplifier
import com.appmire.gpsinfo.data.model.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Three-button "Simplify trail" dialog: pick a preset, see a live preview
 * of how many points would survive, then choose whether to overwrite the
 * stored file or save a copy.
 *
 * Preview computation is fast (RDP on tens of thousands of points takes
 * milliseconds) but we still run it on [Dispatchers.Default] so a long
 * trail can't stall the main thread for even a single frame.
 */
@Composable
fun SimplifyTrailDialog(
    originalPoints: List<TrailPoint>,
    onDismiss: () -> Unit,
    onConfirm: (epsilonMeters: Double, replace: Boolean) -> Unit,
) {
    val presets = TrailSimplifier.Preset.entries
    var selected by remember { mutableIntStateOf(presets.indexOf(TrailSimplifier.Preset.Default)) }
    var previewKept by remember { mutableStateOf<Int?>(null) }

    // Recompute the preview whenever the user picks a different preset.
    LaunchedEffect(selected, originalPoints) {
        previewKept = null
        val eps = presets[selected].epsilonMeters
        val kept = withContext(Dispatchers.Default) {
            TrailSimplifier.simplify(originalPoints, eps).size
        }
        previewKept = kept
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trail_simplify_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.trail_simplify_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                presets.forEachIndexed { idx, preset ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = selected == idx,
                            onClick = { selected = idx },
                        )
                        Text(
                            text = stringResource(preset.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                val originalCount = originalPoints.size
                val kept = previewKept
                Text(
                    text = if (kept != null && originalCount > 0) {
                        val pct = ((originalCount - kept) * 100 / originalCount).coerceIn(0, 99)
                        stringResource(R.string.trail_simplify_preview, originalCount, kept, pct)
                    } else "…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            // Stacked vertically would be neater, but Material's dialog
            // wants confirm + dismiss buttons side-by-side. We squeeze
            // "Replace" and "Save as copy" into the confirm-button slot
            // and let the dismiss slot host Cancel.
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                TextButton(onClick = { onConfirm(presets[selected].epsilonMeters, true) }) {
                    Text(stringResource(R.string.trail_simplify_replace))
                }
                TextButton(onClick = { onConfirm(presets[selected].epsilonMeters, false) }) {
                    Text(stringResource(R.string.trail_simplify_save_copy))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun TrailSimplifier.Preset.labelRes(): Int = when (this) {
    TrailSimplifier.Preset.Light -> R.string.trail_simplify_preset_light
    TrailSimplifier.Preset.Default -> R.string.trail_simplify_preset_default
    TrailSimplifier.Preset.Aggressive -> R.string.trail_simplify_preset_aggressive
}
