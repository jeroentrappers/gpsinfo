package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R

/**
 * The Activity Hub — the launch screen. A grid of activity tiles, each
 * a labelled front door (icon + title + one-line "what"); an info action
 * reveals the "why + how". Tapping a tile opens that activity.
 *
 * Phase 1: fixed order, every tile routes into today's screens. Pinning,
 * resume-last and per-activity Simple layouts come later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHubScreen(
    onOpenActivity: (Activity) -> Unit,
) {
    var info by remember { mutableStateOf<ActivityInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResourceSafe()) })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Activities.all, key = { it.activity }) { item ->
                ActivityTile(
                    item = item,
                    onOpen = { onOpenActivity(item.activity) },
                    onInfo = { info = item },
                )
            }
        }
    }

    info?.let { sel ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = {
                TextButton(onClick = {
                    info = null
                    onOpenActivity(sel.activity)
                }) { Text(stringResource(R.string.activity_open)) }
            },
            dismissButton = {
                TextButton(onClick = { info = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            icon = { Icon(sel.icon, contentDescription = null, tint = Color(sel.accentArgb)) },
            title = { Text(sel.title) },
            text = { Text(sel.whyHow) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityTile(
    item: ActivityInfo,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = Color(item.accentArgb),
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    item.what,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(
                onClick = onInfo,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.activity_why_how),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Title for the hub top bar, falling back gracefully if the string is
 *  not yet present in a locale during the localization phase. */
@Composable
private fun stringResourceSafe(): String = stringResource(R.string.activity_hub_title)
