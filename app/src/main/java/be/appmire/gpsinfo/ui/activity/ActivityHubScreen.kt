package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
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
 * The Activity Hub — the launch screen. Persona-pinned activities sit
 * under "Your activities"; the rest under "More". A Resume shortcut
 * reopens the last activity, and a one-time intro card greets users
 * upgrading from the old single-dashboard launch. Tapping a tile opens
 * the activity; the info action reveals the why/how.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityHubScreen(
    onOpenActivity: (Activity) -> Unit,
    pinned: Set<Activity> = emptySet(),
    lastActivity: Activity? = null,
    showIntro: Boolean = false,
    onDismissIntro: () -> Unit = {},
) {
    var info by remember { mutableStateOf<ActivityInfo?>(null) }
    val pinnedItems = remember(pinned) { Activities.all.filter { it.activity in pinned } }
    val restItems = remember(pinned) { Activities.all.filter { it.activity !in pinned } }
    val resumeItem = lastActivity?.let { a -> Activities.all.firstOrNull { it.activity == a } }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.activity_hub_title)) }) },
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
            if (showIntro) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "intro") {
                    IntroCard(onDismiss = onDismissIntro)
                }
            }
            if (resumeItem != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "resume") {
                    ResumeRow(item = resumeItem, onClick = { onOpenActivity(resumeItem.activity) })
                }
            }

            if (pinnedItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-yours") {
                    SectionHeader(stringResource(R.string.activity_hub_your))
                }
                items(pinnedItems, key = { it.activity }) { item ->
                    ActivityTile(item, { onOpenActivity(item.activity) }, { info = item })
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-more") {
                    SectionHeader(stringResource(R.string.activity_hub_more))
                }
            }
            items(restItems, key = { it.activity }) { item ->
                ActivityTile(item, { onOpenActivity(item.activity) }, { info = item })
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResumeRow(item: ActivityInfo, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(item.icon, contentDescription = null, tint = Color(item.accentArgb))
            Text(
                stringResource(R.string.activity_resume, item.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun IntroCard(onDismiss: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.activity_intro_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.activity_intro_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.activity_intro_dismiss))
            }
        }
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
