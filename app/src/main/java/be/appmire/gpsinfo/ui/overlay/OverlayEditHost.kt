package be.appmire.gpsinfo.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R

/**
 * A drop-in host that makes its [content]'s tagged overlay elements
 * ([Modifier.overlayElement]) draggable / pinch-scalable / removable. Owns the
 * edit-mode state, provides [LocalOverlayEdit] to the content, renders the
 * edit/done · reset · remove controls, and persists via [onSave] on leaving
 * edit mode. Decoupled from the ViewModel — pass the persisted layout in and a
 * save callback out — so any screen can wrap an overlay in it.
 */
@Composable
fun OverlayEditBox(
    persisted: PhoneOverlayLayout,
    onSave: (PhoneOverlayLayout) -> Unit,
    context: PhoneOverlayContext,
    modifier: Modifier = Modifier,
    controlsAlignment: Alignment = Alignment.CenterEnd,
    /** Fires when edit mode toggles, so the host can e.g. freeze reveal-on-touch
     *  chrome that would otherwise resize the content while dragging. */
    onEditingChange: (Boolean) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PhoneOverlayElement?>(null) }
    var parentPx by remember { mutableStateOf(IntSize.Zero) }
    var working by remember { mutableStateOf(persisted) }
    LaunchedEffect(persisted) { if (!editing) working = persisted }
    LaunchedEffect(editing) { onEditingChange(editing) }

    Box(modifier.onSizeChanged { parentPx = it }) {
        val scope = OverlayEditScope(
            editing = editing,
            context = context,
            layout = working,
            parentPx = parentPx,
            onChange = { el, ov -> working = working.with(context, el, ov) },
            selected = selected,
            onSelect = { selected = it },
        )
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayEdit provides scope) {
            content()
        }

        Column(
            modifier = Modifier.align(controlsAlignment).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (editing && selected?.removable == true) {
                FloatingActionButton(
                    onClick = { selected?.let { working = working.hide(context, it); selected = null } },
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.overlay_edit_remove)) }
            }
            if (editing) {
                FloatingActionButton(
                    onClick = { working = working.cleared(context); selected = null },
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.overlay_edit_reset)) }
            }
            FloatingActionButton(
                onClick = {
                    if (editing) onSave(working)
                    selected = null
                    editing = !editing
                },
                modifier = Modifier.size(52.dp),
                containerColor = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (editing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                    contentDescription = stringResource(
                        if (editing) R.string.overlay_edit_done else R.string.overlay_edit_layout,
                    ),
                )
            }
        }

        if (editing) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(50),
                tonalElevation = 3.dp,
            ) {
                Text(
                    stringResource(R.string.overlay_edit_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
