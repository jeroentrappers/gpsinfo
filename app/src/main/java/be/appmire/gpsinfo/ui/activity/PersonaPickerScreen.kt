package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.DashboardProfile

/**
 * First-run persona picker (Phase 2). Multi-select: the first persona
 * chosen is "primary" (sets the dashboard profile + accent); the pinned
 * activities are the union across all chosen personas. Calls [onDone]
 * with the selected profile ids in selection order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPickerScreen(
    onDone: (selectedProfileIds: List<String>) -> Unit,
) {
    // Selection order matters — the first pick is the primary persona.
    val selected = remember { mutableStateListOf<String>() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.persona_picker_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                stringResource(R.string.persona_picker_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(Personas.all, key = { it.profileId }) { persona ->
                    val on = persona.profileId in selected
                    val accent = Color(
                        DashboardProfile.fromId(persona.profileId).accentArgb
                            ?: DashboardProfile.COLOR_ORANGE,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (on) selected.remove(persona.profileId)
                                else selected.add(persona.profileId)
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                        ) {
                            Surface(color = accent, modifier = Modifier.fillMaxSize()) {}
                        }
                        Text(
                            stringResource(persona.displayNameRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp),
                        )
                        Checkbox(checked = on, onCheckedChange = null)
                    }
                }
            }
            Button(
                onClick = { onDone(selected.toList()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(stringResource(R.string.persona_picker_continue))
            }
        }
    }
}
