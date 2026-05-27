package be.appmire.gpsinfo.ui.ghost

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.GhostReference
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale

private enum class GhostMode { Off, Pace, Goal, Run }

/**
 * Picks the ghost-runner source: off, an even target pace, a goal
 * time over a distance, or a past recorded run. Applies through the
 * ViewModel (which persists the choice) and pops back.
 *
 * Pace/distance are entered in the user's unit (km or mi) and
 * converted to the canonical km-based storage.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostPickerScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val ref by vm.ghostReference.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val trails by vm.trails.collectAsStateWithLifecycle()
    val imperial = state.unitSystem == UnitSystem.Imperial

    var mode by remember {
        mutableStateOf(
            when (ref) {
                null -> GhostMode.Off
                is GhostReference.TargetPace -> GhostMode.Pace
                is GhostReference.Goal -> GhostMode.Goal
                is GhostReference.PastRun -> GhostMode.Run
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ghost_picker_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.ghost_picker_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    GhostMode.Off to R.string.ghost_mode_off,
                    GhostMode.Pace to R.string.ghost_mode_pace,
                    GhostMode.Goal to R.string.ghost_mode_goal,
                    GhostMode.Run to R.string.ghost_mode_run,
                )
                modes.forEachIndexed { idx, (m, labelRes) ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(idx, modes.size),
                    ) { Text(stringResource(labelRes)) }
                }
            }

            when (mode) {
                GhostMode.Off -> OffPane(
                    active = ref == null,
                    onApply = { vm.clearGhost(); onBack() },
                )
                GhostMode.Pace -> PacePane(
                    imperial = imperial,
                    initial = ref as? GhostReference.TargetPace,
                    onApply = { secPerKm -> vm.setGhostPace(secPerKm); onBack() },
                )
                GhostMode.Goal -> GoalPane(
                    imperial = imperial,
                    initial = ref as? GhostReference.Goal,
                    onApply = { secs, metres -> vm.setGhostGoal(secs, metres); onBack() },
                )
                GhostMode.Run -> RunPane(
                    trails = trails,
                    activeId = (ref as? GhostReference.PastRun)?.trailId,
                    onPick = { id -> vm.setGhostTrail(id); onBack() },
                )
            }
        }
    }
}

@Composable
private fun OffPane(active: Boolean, onApply: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ghost_off_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onApply, enabled = !active, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ghost_turn_off))
        }
    }
}

@Composable
private fun PacePane(
    imperial: Boolean,
    initial: GhostReference.TargetPace?,
    onApply: (Float) -> Unit,
) {
    // Prefill from current selection, converting km→mi when imperial.
    val initSecPerUnit = initial?.let {
        if (imperial) it.secondsPerKm * 1.609344f else it.secondsPerKm
    }
    var min by remember { mutableStateOf(initSecPerUnit?.let { (it / 60).toInt().toString() } ?: "") }
    var sec by remember { mutableStateOf(initSecPerUnit?.let { (it % 60).toInt().toString().padStart(2, '0') } ?: "") }
    val unitLabel = if (imperial) "/mi" else "/km"
    val secPerUnit = (min.toIntOrNull() ?: 0) * 60 + (sec.toIntOrNull() ?: 0)
    val valid = secPerUnit in 60..6000

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ghost_pace_hint, unitLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = min,
                onValueChange = { min = it.filter(Char::isDigit).take(2) },
                label = { Text(stringResource(R.string.ghost_minutes)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            Text(" : ", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = sec,
                onValueChange = { sec = it.filter(Char::isDigit).take(2) },
                label = { Text(stringResource(R.string.ghost_seconds)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(unitLabel, style = MaterialTheme.typography.titleMedium)
        }
        Button(
            onClick = {
                // Convert the entered per-unit pace back to canonical sec/km.
                val secPerKm = if (imperial) secPerUnit / 1.609344f else secPerUnit.toFloat()
                onApply(secPerKm)
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ghost_apply)) }
    }
}

@Composable
private fun GoalPane(
    imperial: Boolean,
    initial: GhostReference.Goal?,
    onApply: (Long, Double) -> Unit,
) {
    val unitLabel = if (imperial) stringResource(R.string.unit_mi) else stringResource(R.string.unit_km)
    val perUnitMeters = if (imperial) 1609.344 else 1000.0
    var dist by remember {
        mutableStateOf(initial?.let { "%.2f".format(Locale.US, it.totalMeters / perUnitMeters) } ?: "")
    }
    var min by remember { mutableStateOf(initial?.let { (it.totalSeconds / 60).toString() } ?: "") }
    var sec by remember { mutableStateOf(initial?.let { (it.totalSeconds % 60).toString().padStart(2, '0') } ?: "") }
    val distUnits = dist.replace(',', '.').toDoubleOrNull()
    val totalSecs = (min.toLongOrNull() ?: 0L) * 60 + (sec.toLongOrNull() ?: 0L)
    val valid = distUnits != null && distUnits > 0.0 && totalSecs > 0L

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.ghost_goal_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = dist,
                onValueChange = { dist = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                label = { Text(stringResource(R.string.ghost_goal_distance)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(160.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(unitLabel, style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = min,
                onValueChange = { min = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.ghost_minutes)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            Text(" : ", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = sec,
                onValueChange = { sec = it.filter(Char::isDigit).take(2) },
                label = { Text(stringResource(R.string.ghost_seconds)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
        }
        Button(
            onClick = {
                val metres = (distUnits ?: 0.0) * perUnitMeters
                onApply(totalSecs, metres)
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ghost_apply)) }
    }
}

@Composable
private fun RunPane(
    trails: List<be.appmire.gpsinfo.data.TrailSummary>,
    activeId: String?,
    onPick: (String) -> Unit,
) {
    if (trails.isEmpty()) {
        Text(
            text = stringResource(R.string.ghost_run_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(trails, key = { it.id }) { t ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(t.id) },
                color = if (t.id == activeId) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "%.2f km · %s".format(
                                Locale.ROOT,
                                t.distanceMeters / 1000.0,
                                formatDuration(t.durationMillis),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (t.id == activeId) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000L
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, sec)
    else "%d:%02d".format(Locale.ROOT, m, sec)
}
