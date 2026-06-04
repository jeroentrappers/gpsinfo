package be.appmire.gpsinfo.ui.rally

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.rally.RallyController
import be.appmire.gpsinfo.data.rally.RallyStageRepository
import be.appmire.gpsinfo.data.rally.RallyState
import be.appmire.gpsinfo.data.rally.RegularityStage
import be.appmire.gpsinfo.data.rally.SpeedChange
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Regularity-rally ("regelmatigheidsproef") control centre.
 *
 * Three faces, switched by [RallyController]'s state:
 *   - **Stage library** (Idle): fast manual entry of stages — rows of
 *     "from km / speed" breakpoints, exactly the shape of a roadbook
 *     speed table. Tap a stage to arm it.
 *   - **Armed**: big START button for the marshal's go (manual tap by
 *     design — RT starts are human-flagged), plus stage summary.
 *   - **Running**: co-driver panel. Huge early/late delta (the only
 *     number that matters mid-stage), target speed, rally distance
 *     with ±10 m sync nudges for continuous recalibration against
 *     roadbook landmarks.
 *
 * The same Running state drives the Android Auto HUD; this screen is
 * the co-driver's handheld mirror of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RallyScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { RallyStageRepository(context) }
    val scope = rememberCoroutineScope()
    val stages by repo.stages.collectAsStateWithLifecycle()
    val rallyState by RallyController.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<RegularityStage?>(null) }

    LaunchedEffect(Unit) { repo.loadIfNeeded() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rally_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when (val s = rallyState) {
                is RallyState.Running -> RunningPanel(s)
                is RallyState.Armed -> ArmedPanel(s)
                RallyState.Idle -> {
                    val toEdit = editing
                    if (toEdit != null) {
                        StageEditor(
                            initial = toEdit,
                            onSave = { stage ->
                                scope.launch {
                                    repo.save(stage)
                                    editing = null
                                }
                            },
                            onCancel = { editing = null },
                        )
                    } else {
                        StageLibrary(
                            stages = stages ?: emptyList(),
                            onArm = { RallyController.arm(it) },
                            onEdit = { editing = it },
                            onDelete = { scope.launch { repo.delete(it.id) } },
                            onNew = {
                                editing = RegularityStage(
                                    id = "",
                                    name = "",
                                    changes = listOf(SpeedChange(0.0, 50.0)),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Running: the co-driver panel ───────────────────────────────────

@Composable
private fun RunningPanel(s: RallyState.Running) {
    val delta = s.deltaSeconds
    // Rally convention: both directions are penalties. Green only in
    // the sub-second sweet spot, amber inside 3 s, red beyond.
    val deltaColor = when {
        abs(delta) < 1.0 -> Color(0xFF2E7D32)
        abs(delta) < 3.0 -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(s.stage.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "%+.0f s".format(Locale.ROOT, delta),
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = deltaColor,
        )
        Text(
            text = stringResource(
                if (delta >= 0) R.string.rally_late_speed_up else R.string.rally_early_slow_down
            ),
            style = MaterialTheme.typography.titleMedium,
            color = deltaColor,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.rally_target_speed),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "%.0f km/h".format(Locale.ROOT, s.targetSpeedKmh),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.rally_distance),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "%.2f km".format(Locale.ROOT, s.drivenKm),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        // Distance sync: passing a roadbook landmark, correct the
        // tripmeter — each correction also tightens calibration.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { RallyController.nudge(-10.0) }) { Text("−10 m") }
            OutlinedButton(onClick = { RallyController.nudge(+10.0) }) { Text("+10 m") }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { RallyController.stop() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.rally_stop))
        }
        if (s.finished) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.rally_finished),
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// ── Armed: waiting on the marshal ──────────────────────────────────

@Composable
private fun ArmedPanel(s: RallyState.Armed) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(s.stage.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            stageSummary(s.stage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { RallyController.start() },
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
        ) {
            Text(stringResource(R.string.rally_start), fontSize = 32.sp)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { RallyController.disarm() }) {
            Text(stringResource(R.string.rally_disarm))
        }
    }
}

// ── Idle: stage library ────────────────────────────────────────────

@Composable
private fun StageLibrary(
    stages: List<RegularityStage>,
    onArm: (RegularityStage) -> Unit,
    onEdit: (RegularityStage) -> Unit,
    onDelete: (RegularityStage) -> Unit,
    onNew: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rally_new_stage))
            }
        }
        if (stages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.rally_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        items(stages, key = { it.id }) { stage ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stage.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stageSummary(stage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onArm(stage) }) {
                        Text(stringResource(R.string.rally_arm))
                    }
                    IconButton(onClick = { onEdit(stage) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.rally_edit))
                    }
                    IconButton(onClick = { onDelete(stage) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.rally_delete))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Editor: fast manual entry ──────────────────────────────────────

/** Editable row mirror of [SpeedChange] — raw strings so partial
 *  input ("3.", "3,4") never fights the keyboard. */
private data class ChangeRow(var atKm: String, var speedKmh: String)

@Composable
private fun StageEditor(
    initial: RegularityStage,
    onSave: (RegularityStage) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var lengthKm by remember {
        mutableStateOf(initial.lengthKm?.let { fmt(it) } ?: "")
    }
    val rows = remember {
        initial.changes.map { ChangeRow(fmt(it.atKm), fmt(it.speedKmh)) }
            .toMutableList()
            .let { androidx.compose.runtime.mutableStateListOf(*it.toTypedArray()) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rally_stage_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                stringResource(R.string.rally_changes_header),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        itemsIndexed(rows) { i, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = row.atKm,
                    onValueChange = { rows[i] = row.copy(atKm = it) },
                    label = { Text(stringResource(R.string.rally_from_km)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = i > 0, // first breakpoint is pinned at km 0
                )
                OutlinedTextField(
                    value = row.speedKmh,
                    onValueChange = { rows[i] = row.copy(speedKmh = it) },
                    label = { Text(stringResource(R.string.rally_speed_kmh)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (i > 0) rows.removeAt(i) }, enabled = i > 0) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.rally_delete))
                }
            }
        }
        item {
            OutlinedButton(onClick = { rows.add(ChangeRow("", "")) }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rally_add_change))
            }
        }
        item {
            OutlinedTextField(
                value = lengthKm,
                onValueChange = { lengthKm = it },
                label = { Text(stringResource(R.string.rally_length_km)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        val changes = rows.mapIndexedNotNull { i, r ->
                            val at = if (i == 0) 0.0 else parse(r.atKm) ?: return@mapIndexedNotNull null
                            val sp = parse(r.speedKmh) ?: return@mapIndexedNotNull null
                            if (sp <= 0.0) return@mapIndexedNotNull null
                            SpeedChange(at, sp)
                        }.sortedBy { it.atKm }
                        if (changes.isNotEmpty()) {
                            onSave(
                                initial.copy(
                                    name = name.ifBlank { "RT" },
                                    changes = changes,
                                    lengthKm = parse(lengthKm),
                                )
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.rally_save))
                }
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.rally_cancel))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun stageSummary(stage: RegularityStage): String {
    val len = stage.lengthKm?.let { "%.2f km · ".format(Locale.ROOT, it) } ?: ""
    val speeds = stage.changes.joinToString(" → ") {
        "%.0f".format(Locale.ROOT, it.speedKmh)
    }
    return "$len$speeds km/h"
}

/** Comma-tolerant decimal parse — Belgian roadbooks write "3,42". */
private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.2f".format(Locale.ROOT, value)
