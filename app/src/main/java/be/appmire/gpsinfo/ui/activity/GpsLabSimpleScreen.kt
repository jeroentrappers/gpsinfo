package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.material.icons.outlined.GpsOff
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * GPS Lab — **Simple** layout. Plain-language fix status, satellites in
 * use / in view, and accuracy. The Pro layout is the full satellite list
 * (sky plot, CN0 bars, NMEA). The app-bar action toggles to Pro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpsLabSimpleScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onShowDetailed: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val gnss = state.gnss
    val (icon, statusText, tint) = when (gnss.fix) {
        FixStatus.THREE_D -> Triple(
            Icons.Outlined.GpsFixed, stringResource(R.string.gps_lab_good), Color(0xFF43A047),
        )
        FixStatus.TWO_D -> Triple(
            Icons.Outlined.GpsNotFixed, stringResource(R.string.gps_lab_weak), Color(0xFFF9A825),
        )
        FixStatus.NO_FIX -> Triple(
            Icons.Outlined.GpsOff, stringResource(R.string.gps_lab_searching), Color(0xFFE53935),
        )
    }
    val accuracyM = gnss.location?.takeIf { it.hasAccuracy() }?.accuracy?.toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gps_lab_title)) },
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
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(96.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.gps_lab_sats_summary, gnss.satellitesInUse, gnss.satellitesInView),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (accuracyM != null) {
                Text(
                    stringResource(R.string.gps_lab_accuracy, accuracyM),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
