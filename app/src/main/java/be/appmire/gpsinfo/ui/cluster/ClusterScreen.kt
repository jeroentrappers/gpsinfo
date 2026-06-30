package be.appmire.gpsinfo.ui.cluster

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.ui.overlay.LocalOverlayEdit
import be.appmire.gpsinfo.ui.overlay.OverlayEditScope
import be.appmire.gpsinfo.ui.overlay.PhoneOverlayContext
import be.appmire.gpsinfo.ui.overlay.PhoneOverlayElement
import be.appmire.gpsinfo.ui.overlay.removable
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/** Housing colour the integrated gauge fills with — keep the surround the
 *  same so the cluster reads as one piece in any app theme. */
private val ClusterBackground = Color(0xFF0B0B0B)

/**
 * Full-screen instrument cluster — the phone's standalone analogue of the
 * Android Auto cluster. Picks the layout by orientation exactly like the car
 * surface does: the cockpit edge-HUD in landscape, the integrated single
 * gauge in portrait (speed, compass + G-meter, posted limit; no power without
 * OBD). The toolbar's edit toggle lets you drag and pinch the cluster to
 * reposition / resize it (saved per orientation). Works idle and while
 * driving; no route required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val data by vm.clusterData.collectAsStateWithLifecycle()
    val persisted by vm.phoneOverlayLayout.collectAsStateWithLifecycle()

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val ctx = if (landscape) PhoneOverlayContext.CLUSTER_LANDSCAPE else PhoneOverlayContext.CLUSTER_PORTRAIT

    var editing by remember { mutableStateOf(false) }
    var parentPx by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<PhoneOverlayElement?>(null) }
    // Working copy edited live; seeded from the persisted layout whenever not
    // editing, saved back when the user leaves edit mode.
    var working by remember { mutableStateOf(persisted) }
    LaunchedEffect(persisted) { if (!editing) working = persisted }

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
                actions = {
                    if (editing) {
                        IconButton(
                            onClick = { selected?.let { working = working.hide(ctx, it); selected = null } },
                            enabled = selected?.removable == true,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.overlay_edit_remove))
                        }
                        IconButton(onClick = { working = working.cleared(ctx); selected = null }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.overlay_edit_reset))
                        }
                    }
                    IconButton(onClick = {
                        if (editing) vm.savePhoneOverlayLayout(working)
                        selected = null
                        editing = !editing
                    }) {
                        Icon(
                            if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                            contentDescription = stringResource(
                                if (editing) R.string.overlay_edit_done else R.string.overlay_edit_layout,
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ClusterBackground,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = ClusterBackground,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(ClusterBackground)
                .padding(padding)
                .onSizeChanged { parentPx = it },
            contentAlignment = Alignment.Center,
        ) {
            val scope = OverlayEditScope(
                editing = editing,
                context = ctx,
                layout = working,
                parentPx = parentPx,
                onChange = { el, ov -> working = working.with(ctx, el, ov) },
                selected = selected,
                onSelect = { selected = it },
            )
            CompositionLocalProvider(LocalOverlayEdit provides scope) {
                ClusterGauges(
                    data = data,
                    showCompass = true,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
    }
}
