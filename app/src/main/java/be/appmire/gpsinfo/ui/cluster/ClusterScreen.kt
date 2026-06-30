package be.appmire.gpsinfo.ui.cluster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/** Housing colour the integrated gauge fills with — keep the surround the
 *  same so the cluster reads as one piece in any app theme. */
private val ClusterBackground = Color(0xFF0B0B0B)

/**
 * Full-screen instrument cluster — the phone's standalone analogue of the
 * Android Auto cluster. Picks the layout by orientation exactly like the car
 * surface does: the cockpit edge-HUD in landscape, the integrated single
 * gauge in portrait (speed, compass + G-meter, posted limit; no power without
 * OBD). Works idle and while driving; no route required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val data by vm.clusterData.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_cluster)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClusterBackground,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = ClusterBackground,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(ClusterBackground)
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            GaugeCluster(
                data = data,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                showCompass = true,
                mode = ClusterMode.AUTO,
            )
        }
    }
}
