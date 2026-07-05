package be.appmire.gpsinfo.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R

/**
 * The "Tools" pillar: a flat list of specialist + diagnostic screens
 * (GPS Lab, compass, speed, raw GNSS, rally, OBD2 Lab, ghost runner,
 * navigate-to) plus Settings. Each item just navigates; the callbacks
 * are wired to routes in MainActivity (where Routes is private).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onOpenGpsLab: () -> Unit,
    onOpenCompass: () -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenNmea: () -> Unit,
    onOpenRally: () -> Unit,
    onOpenObdLab: () -> Unit,
    onOpenEvProfile: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenGhost: () -> Unit,
    onOpenNavigate: () -> Unit,
    onOpenCluster: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items = listOf(
        ToolItem(Icons.Outlined.SatelliteAlt, R.string.activity_gpslab_title, onOpenGpsLab),
        ToolItem(Icons.Outlined.Explore, R.string.screen_compass, onOpenCompass),
        ToolItem(Icons.Outlined.Speed, R.string.screen_speed, onOpenSpeed),
        ToolItem(Icons.Outlined.SpaceDashboard, R.string.screen_cluster, onOpenCluster),
        ToolItem(Icons.Outlined.Terminal, R.string.screen_nmea, onOpenNmea),
        ToolItem(Icons.Outlined.Navigation, R.string.nav_start, onOpenNavigate),
        ToolItem(Icons.Outlined.SportsScore, R.string.rally_title, onOpenRally),
        ToolItem(Icons.Outlined.Tune, R.string.settings_obd_lab, onOpenObdLab),
        ToolItem(Icons.Outlined.Bolt, R.string.ev_profile_title, onOpenEvProfile),
        ToolItem(Icons.Outlined.Route, R.string.trips_title, onOpenTrips),
        ToolItem(Icons.AutoMirrored.Outlined.DirectionsRun, R.string.ghost_picker_title, onOpenGhost),
        ToolItem(Icons.Outlined.Settings, R.string.screen_settings, onOpenSettings),
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_tools)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items) { item ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = item.onClick),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(item.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private data class ToolItem(val icon: ImageVector, val labelRes: Int, val onClick: () -> Unit)
