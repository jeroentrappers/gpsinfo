package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.components.CompassCard
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale

/**
 * Explore & Orient — **Simple** layout: a big compass, the current
 * position, and one-tap Mark / Share. Pro is the full compass detail
 * screen (declination, calibration, etc.); the app-bar toggle switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreSimpleScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onShowDetailed: () -> Unit,
    onMark: () -> Unit,
    onShare: () -> Unit,
) {
    val reading by vm.compass.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val loc = state.gnss.location

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explore_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onShowDetailed) {
                        Text(stringResource(R.string.detail_detailed))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            CompassCard(reading = reading, courseHeadingDeg = null)
            Text(
                text = if (loc != null) {
                    "%.5f, %.5f".format(Locale.ROOT, loc.latitude, loc.longitude) +
                        (if (loc.hasAltitude()) " · ${loc.altitude.toInt()} m" else "")
                } else {
                    stringResource(R.string.explore_no_fix)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onMark, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AddLocationAlt, contentDescription = null)
                    Text(
                        stringResource(R.string.explore_mark),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(
                        stringResource(R.string.explore_share),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
