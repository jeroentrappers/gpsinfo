package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import be.appmire.gpsinfo.util.TrailScoring
import be.appmire.gpsinfo.util.formatPace
import be.appmire.gpsinfo.util.paceUnitLabel
import java.util.Locale

/**
 * Editor for per-segment pace targets on a recorded trail. Samples the
 * trail into a manageable number of waypoints (default 10) and lets
 * the user edit a target pace for the segment leading up to each one.
 *
 * The "segment to point N" is the trail span from the previous
 * sampled waypoint to N — so target paces are coarse-grained on
 * purpose. Fine-grained per-point targets would be unauthorable for a
 * 5,000-point trail without much more sophisticated UI.
 *
 * Targets are stored on the underlying [TrailPoint]s (the canonical
 * persistence in GPX). Saving re-emits the trail with the updated
 * point list via [onSave].
 *
 * A "Apply to all" button below the list sets the first waypoint's
 * value across every sampled segment — useful when the user wants a
 * uniform pace and only differs at certain landmarks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaceTargetsEditor(
    trail: Trail,
    unitSystem: UnitSystem,
    onBack: () -> Unit,
    onSave: (updated: List<TrailPoint>) -> Unit,
) {
    // Sample the trail down to ~10 waypoints. Endpoints + evenly
    // spaced interior. Indices are into the original [trail.points].
    val sampleIndices = remember(trail) { sampleEvenly(trail.points.size, count = 10) }

    // Editable state — per-sample target in seconds/unit (display unit).
    // Seeded from existing per-point targets (converted from km).
    val initial = remember(trail, unitSystem) {
        sampleIndices.map { idx ->
            trail.points[idx].targetPaceSecondsPerKm?.let {
                TrailScoring.targetPaceInUnit(it, unitSystem)
            }
        }
    }
    val texts = remember(trail) {
        initial.map { mutableStateOf(it?.let(::formatPaceMinSec) ?: "") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pace_targets_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.pace_targets_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sampleIndices) { idx ->
                    val pos = sampleIndices.indexOf(idx)
                    val pct = if (sampleIndices.size <= 1) 100 else (pos * 100 / (sampleIndices.size - 1))
                    SampleRow(
                        positionLabel = stringResource(R.string.pace_targets_waypoint, pct),
                        valueText = texts[pos].value,
                        unitLabel = paceUnitLabel(unitSystem),
                        onTextChange = { texts[pos].value = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Propagate the first row's value to every other row.
                        val seed = texts.firstOrNull()?.value ?: ""
                        for (i in 1 until texts.size) texts[i].value = seed
                    },
                ) {
                    Text(stringResource(R.string.pace_targets_apply_all))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val parsed = texts.map { parseMinSec(it.value) }
                        val updated = applyTargets(trail.points, sampleIndices, parsed, unitSystem)
                        onSave(updated)
                    },
                ) {
                    Text(stringResource(R.string.trail_save))
                }
            }
        }
    }
}

@Composable
private fun SampleRow(
    positionLabel: String,
    valueText: String,
    unitLabel: String,
    onTextChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = positionLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = valueText,
                onValueChange = onTextChange,
                singleLine = true,
                placeholder = { Text("5:30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** Pick [count] evenly-spaced indices through `[0, size)`. Always
 *  includes the first and last index when size > 1. */
internal fun sampleEvenly(size: Int, count: Int): List<Int> {
    if (size <= 0) return emptyList()
    if (size <= count) return (0 until size).toList()
    val n = count.coerceAtLeast(2)
    return (0 until n).map { ((it.toLong() * (size - 1)) / (n - 1)).toInt() }
}

/** Format `sec/unit` into a "M:SS" string for the input field. */
private fun formatPaceMinSec(secondsPerUnit: Float): String {
    val sec = secondsPerUnit.toInt().coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(Locale.ROOT, m, s)
}

/** Parse "M:SS" or "M" back into a Float seconds-per-unit. Returns
 *  null when the field is blank or malformed. */
internal fun parseMinSec(text: String): Float? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':')
    return when (parts.size) {
        1 -> parts[0].toIntOrNull()?.let { (it * 60).toFloat() }
        2 -> {
            val m = parts[0].toIntOrNull() ?: return null
            val s = parts[1].toIntOrNull() ?: return null
            if (s !in 0..59) return null
            (m * 60 + s).toFloat()
        }
        else -> null
    }
}

/**
 * Write [targets] (one per sample position, in user's unit) back onto
 * the trail's point list. Every trail point between sample[i-1] and
 * sample[i] gets sample[i]'s target — that's the "segment leading to
 * waypoint i". Returns a new immutable list; the original is untouched.
 *
 * Internal so tests can drive it directly.
 */
internal fun applyTargets(
    points: List<TrailPoint>,
    sampleIndices: List<Int>,
    targetsPerUnit: List<Float?>,
    unitSystem: UnitSystem,
): List<TrailPoint> {
    if (sampleIndices.isEmpty() || points.isEmpty()) return points
    val targetsKm = targetsPerUnit.map { perUnit ->
        perUnit?.let { unitToSecPerKm(it, unitSystem) }
    }
    val out = points.toMutableList()
    // For each segment between consecutive sampled indices, apply the
    // *destination* sample's target to every point in that range.
    var prevSample = 0
    for (sIdx in sampleIndices.indices) {
        val endIdx = sampleIndices[sIdx].coerceAtMost(points.size - 1)
        val target = targetsKm[sIdx]
        for (i in prevSample..endIdx) {
            out[i] = out[i].copy(targetPaceSecondsPerKm = target)
        }
        prevSample = endIdx + 1
    }
    return out.toList()
}

private fun unitToSecPerKm(secondsPerUnit: Float, unit: UnitSystem): Float = when (unit) {
    UnitSystem.Metric -> secondsPerUnit
    UnitSystem.Imperial -> secondsPerUnit / 1.609344f
    UnitSystem.Nautical -> secondsPerUnit / 1.852f
}
