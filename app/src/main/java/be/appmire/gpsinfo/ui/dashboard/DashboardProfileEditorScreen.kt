package be.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.DashboardProfile
import be.appmire.gpsinfo.data.model.DashboardSection
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * Editor for the "Custom" dashboard profile. Lists every section with
 * a visibility checkbox and up/down arrows; Save persists the new
 * ordering and switches the active profile to Custom.
 *
 * Drag-reorder is the obvious next step but needs either a drag
 * library or a hand-rolled Modifier.draggable + animation pass —
 * arrow buttons stay accessible and ship the feature without that
 * complexity (R2.c).
 *
 * The editor seeds from whichever profile is currently active, so
 * "switch to Cyclist, customize one card, save" works in one shot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardProfileEditorScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val active by vm.dashboardProfile.collectAsStateWithLifecycle()

    // Working copy: every known section, marked visible iff it appears
    // in the currently active profile, and ordered as it does there.
    val workingCopy = remember { mutableStateListOf<EditableEntry>() }
    // Working accent — seeded from the active profile, updated when
    // the user taps a swatch below, persisted on save.
    var workingAccent by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            active.accentArgb ?: be.appmire.gpsinfo.data.model.DashboardProfile.COLOR_ORANGE
        )
    }
    // Working chrome — Normal / NightDimRed. Seeded from active.
    var workingChrome by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(active.chromeStyle)
    }
    LaunchedEffect(active.id) {
        if (workingCopy.isEmpty()) {
            // First entry into the editor — seed from active profile.
            // Subsequent profile switches in the background don't
            // clobber the user's in-flight edits.
            val activeSet = active.cards.toSet()
            workingCopy.clear()
            // Visible cards first, in their active order, then hidden
            // cards alphabetically at the end so they're discoverable.
            for (sect in active.cards) workingCopy.add(EditableEntry(sect, visible = true))
            val missing = DashboardSection.entries.filter { it !in activeSet }.sortedBy { it.name }
            for (sect in missing) workingCopy.add(EditableEntry(sect, visible = false))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_editor_title)) },
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
                .padding(padding),
        ) {
            Text(
                text = stringResource(R.string.profile_editor_body),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AccentPickerRow(
                selected = workingAccent,
                onSelect = { workingAccent = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ChromePickerRow(
                selected = workingChrome,
                onSelect = { workingChrome = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // Drag-to-reorder state. The dragged row floats with the
            // pointer; non-dragged rows shift smoothly via
            // animateItem() on each LazyColumn item below. Item
            // height is captured from the first laid-out row and
            // used as the swap threshold.
            var dragIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
            var dragOffsetPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            var rowHeightPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            // Pre-compute the inter-item gap in pixels once, in a
            // composable scope. The drag callback is plain Kotlin
            // (not composable) and can't reach LocalDensity directly.
            val gapPx = with(LocalDensity.current) { 6.dp.toPx() }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(workingCopy, key = { _, e -> e.section.name }) { idx, entry ->
                    val isDragged = dragIndex == idx
                    EditableRow(
                        entry = entry,
                        canMoveUp = idx > 0,
                        canMoveDown = idx < workingCopy.size - 1,
                        isDragged = isDragged,
                        dragOffsetY = if (isDragged) dragOffsetPx else 0f,
                        onMeasureHeight = { h ->
                            if (rowHeightPx == 0f && h > 0) rowHeightPx = h.toFloat()
                        },
                        onDragStart = {
                            dragIndex = idx
                            dragOffsetPx = 0f
                        },
                        onDrag = { delta ->
                            val current = dragIndex ?: return@EditableRow
                            val threshold = rowHeightPx + gapPx
                            if (threshold <= 0f) return@EditableRow
                            dragOffsetPx += delta
                            // Cross half the row height (plus the
                            // inter-item gap) — swap and snap the
                            // visual offset back by one slot so the
                            // pointer stays anchored to the row.
                            if (dragOffsetPx > threshold / 2f && current < workingCopy.size - 1) {
                                val a = workingCopy[current]
                                workingCopy[current] = workingCopy[current + 1]
                                workingCopy[current + 1] = a
                                dragIndex = current + 1
                                dragOffsetPx -= threshold
                            } else if (dragOffsetPx < -threshold / 2f && current > 0) {
                                val a = workingCopy[current]
                                workingCopy[current] = workingCopy[current - 1]
                                workingCopy[current - 1] = a
                                dragIndex = current - 1
                                dragOffsetPx += threshold
                            }
                        },
                        onDragEnd = {
                            dragIndex = null
                            dragOffsetPx = 0f
                        },
                        onToggleVisible = { entry.visible = !entry.visible },
                        onMoveUp = { swap(workingCopy, entry, -1) },
                        onMoveDown = { swap(workingCopy, entry, +1) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        // Reset to the active profile's ordering /
                        // visibility — the seed step above runs again.
                        workingCopy.clear()
                        val activeSet = active.cards.toSet()
                        for (sect in active.cards) workingCopy.add(EditableEntry(sect, visible = true))
                        val missing = DashboardSection.entries.filter { it !in activeSet }.sortedBy { it.name }
                        for (sect in missing) workingCopy.add(EditableEntry(sect, visible = false))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.profile_editor_reset)) }
                Button(
                    onClick = {
                        val cards = workingCopy.filter { it.visible }.map { it.section }
                        vm.saveCustomProfile(
                            cards,
                            accentArgb = workingAccent,
                            chromeStyle = workingChrome,
                        )
                        onBack()
                    },
                    enabled = workingCopy.any { it.visible },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.profile_editor_save)) }
            }
        }
    }
}

private class EditableEntry(
    val section: DashboardSection,
    visible: Boolean,
) {
    var visible: Boolean by androidx.compose.runtime.mutableStateOf(visible)
}

private fun swap(list: SnapshotStateList<EditableEntry>, entry: EditableEntry, delta: Int) {
    val idx = list.indexOf(entry)
    val target = idx + delta
    if (idx < 0 || target < 0 || target >= list.size) return
    val a = list[idx]
    val b = list[target]
    list[idx] = b
    list[target] = a
}

@Composable
private fun LazyItemScope.EditableRow(
    entry: EditableEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragged: Boolean,
    dragOffsetY: Float,
    onMeasureHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onToggleVisible: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    // animateItem() handles the smooth slide-in/slide-out of the
    // NON-dragged rows when a swap happens. The dragged row keeps
    // its own absolute offset via graphicsLayer below — animating
    // it would fight the pointer.
    val baseModifier = if (isDragged) {
        Modifier
            .zIndex(1f)
            .graphicsLayer { translationY = dragOffsetY }
    } else {
        Modifier.animateItem()
    }
    Surface(
        modifier = baseModifier
            .fillMaxWidth()
            .onSizeChanged { onMeasureHeight(it.height) },
        color = if (isDragged) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (isDragged) 4.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle. Long-press + drag here reorders the row;
            // the arrow buttons on the right still work for users
            // who prefer (or need) tap-only interaction.
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { _, dragAmount -> onDrag(dragAmount.y) },
                        )
                    },
            )
            Checkbox(checked = entry.visible, onCheckedChange = { onToggleVisible() })
            Spacer(Modifier.height(0.dp))
            Text(
                text = sectionLabel(entry.section),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.visible) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChromePickerRow(
    selected: be.appmire.gpsinfo.data.model.ChromeStyle,
    onSelect: (be.appmire.gpsinfo.data.model.ChromeStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.profile_editor_chrome_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        val options = listOf(
            be.appmire.gpsinfo.data.model.ChromeStyle.Normal
                to R.string.profile_editor_chrome_normal,
            be.appmire.gpsinfo.data.model.ChromeStyle.NightDimRed
                to R.string.profile_editor_chrome_night,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { idx, (value, labelRes) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = options.size),
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun AccentPickerRow(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.profile_editor_accent_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            be.appmire.gpsinfo.data.model.DashboardProfile.customColorChoices.forEach { argb ->
                val isSelected = argb == selected
                Surface(
                    onClick = { onSelect(argb) },
                    color = androidx.compose.ui.graphics.Color(argb),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ) else null,
                    modifier = Modifier.size(if (isSelected) 28.dp else 24.dp),
                ) {}
            }
        }
    }
}

@Composable
private fun sectionLabel(section: DashboardSection): String = when (section) {
    DashboardSection.Status -> stringResource(R.string.profile_editor_section_status)
    DashboardSection.Position -> stringResource(R.string.section_position)
    DashboardSection.Speed -> stringResource(R.string.profile_editor_section_speed)
    DashboardSection.Sky -> stringResource(R.string.section_sky)
    DashboardSection.Compass -> stringResource(R.string.section_compass)
    DashboardSection.World -> stringResource(R.string.section_world)
    DashboardSection.TimeSun -> stringResource(R.string.section_time)
}
