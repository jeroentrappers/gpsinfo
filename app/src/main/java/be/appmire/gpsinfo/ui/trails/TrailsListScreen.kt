package be.appmire.gpsinfo.ui.trails

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.TrailSummary
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.PersonalRecords
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class TrailSort(val labelRes: Int) {
    DateDesc(R.string.trails_sort_date_desc),
    DateAsc(R.string.trails_sort_date_asc),
    DistanceDesc(R.string.trails_sort_distance_desc),
    DistanceAsc(R.string.trails_sort_distance_asc),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrailsListScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onOpenTrail: (String) -> Unit,
) {
    val trails by vm.trails.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var pendingDelete by remember { mutableStateOf<TrailSummary?>(null) }
    var editingTagsFor by remember { mutableStateOf<TrailSummary?>(null) }
    var sort by remember { mutableStateOf(TrailSort.DateDesc) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeTagFilter by remember { mutableStateOf<String?>(null) }
    val importFailedMsg = stringResource(R.string.trail_import_failed)

    val importLauncher = rememberLauncherForActivityResult(
        // We pass "*/*" because GPX is `application/gpx+xml`, which Android's
        // DocumentsUI doesn't always advertise as a known MIME; many file
        // managers report it as `text/xml` or `application/xml`. Filtering
        // by extension at parse time is more reliable than at picker time.
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stream = ctx.contentResolver.openInputStream(uri)
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.removeSuffix(".gpx")
                ?.ifBlank { null }
                ?: "Imported trail"
            val id = if (stream == null) null else vm.importGpx(stream, name)
            if (id == null) {
                Toast.makeText(ctx, importFailedMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_trails)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(R.string.trails_sort_label),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            TrailSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(option.labelRes)) },
                                    onClick = {
                                        sort = option
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.trail_import),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (trails.isEmpty()) {
            EmptyState(
                padding = padding,
                onRecord = onBack,
                onImport = { importLauncher.launch(arrayOf("*/*")) },
            )
        } else {
            val allTags = remember(trails) {
                trails.flatMap { it.tags }.distinct().sorted()
            }
            val filtered = remember(trails, query, activeTagFilter, sort) {
                val q = query.trim().lowercase()
                trails.asSequence()
                    .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                    .filter { activeTagFilter == null || activeTagFilter in it.tags }
                    .toList()
                    .let { list ->
                        when (sort) {
                            TrailSort.DateDesc -> list.sortedByDescending { it.startTimeMillis ?: 0L }
                            TrailSort.DateAsc -> list.sortedBy { it.startTimeMillis ?: 0L }
                            TrailSort.DistanceDesc -> list.sortedByDescending { it.distanceMeters }
                            TrailSort.DistanceAsc -> list.sortedBy { it.distanceMeters }
                        }
                    }
            }
            val records = remember(trails) { PersonalRecords.from(trails) }
            val ghostSetMsg = stringResource(R.string.ghost_set_toast)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "__search__") {
                    SearchField(query = query, onQueryChange = { query = it })
                }
                if (allTags.isNotEmpty()) {
                    item(key = "__tag_filter__") {
                        TagFilterRow(
                            tags = allTags,
                            active = activeTagFilter,
                            onSelect = { activeTagFilter = it },
                        )
                    }
                }
                if (records.hasAny && query.isBlank() && activeTagFilter == null) {
                    item(key = "__personal_records__") {
                        PersonalRecordsCard(records = records)
                    }
                }
                if (filtered.isEmpty()) {
                    item(key = "__no_results__") {
                        Text(
                            text = stringResource(R.string.trails_no_results),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    items(filtered, key = { it.id }) { summary ->
                        TrailRow(
                            summary = summary,
                            onClick = { onOpenTrail(summary.id) },
                            onDelete = { pendingDelete = summary },
                            onEditTags = { editingTagsFor = summary },
                            onTagClick = { activeTagFilter = it },
                            onRaceGhost = {
                                vm.setGhostTrail(summary.id)
                                Toast.makeText(
                                    ctx,
                                    String.format(ghostSetMsg, summary.name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.trail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trail_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    pendingDelete = null
                    scope.launch { vm.deleteTrail(id) }
                }) { Text(stringResource(R.string.trail_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    editingTagsFor?.let { target ->
        TagsEditorDialog(
            initialTags = target.tags,
            onDismiss = { editingTagsFor = null },
            onConfirm = { newTags ->
                val id = target.id
                editingTagsFor = null
                scope.launch { vm.setTrailTags(id, newTags) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.trails_search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                }
            }
        },
        singleLine = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFilterRow(
    tags: List<String>,
    active: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = active == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.trails_filter_all)) },
        )
        tags.forEach { tag ->
            FilterChip(
                selected = active == tag,
                onClick = { onSelect(if (active == tag) null else tag) },
                label = { Text(tag) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TrailRow(
    summary: TrailSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditTags: () -> Unit,
    onTagClick: (String) -> Unit,
    onRaceGhost: () -> Unit,
) {
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                ),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = summary.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val started = summary.startTimeMillis?.let { dateFormat.format(Date(it)) } ?: "—"
                    val distance = formatDistanceKm(summary.distanceMeters)
                    Text(
                        text = stringResource(
                            R.string.trail_list_meta,
                            started,
                            summary.pointCount,
                            distance,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (summary.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            summary.tags.forEach { tag ->
                                AssistChip(
                                    onClick = { onTagClick(tag) },
                                    label = {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trail_action_edit_tags)) },
                onClick = {
                    menuExpanded = false
                    onEditTags()
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.LocalOffer,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trail_action_race_ghost)) },
                onClick = {
                    menuExpanded = false
                    onRaceGhost()
                },
                leadingIcon = {
                    Icon(
                        Icons.AutoMirrored.Outlined.DirectionsRun,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trail_action_delete)) },
                onClick = {
                    menuExpanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptyState(
    padding: PaddingValues,
    onRecord: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Map,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.trail_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.trail_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRecord) {
            Icon(
                Icons.Outlined.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.trail_empty_action_record))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImport) {
            Icon(
                Icons.Outlined.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.trail_empty_action_import))
        }
    }
}

internal fun formatDistanceKm(metres: Double): String =
    if (metres < 1_000.0) "%d m".format(java.util.Locale.ROOT, metres.toInt())
    else "%.2f km".format(java.util.Locale.ROOT, metres / 1_000.0)
