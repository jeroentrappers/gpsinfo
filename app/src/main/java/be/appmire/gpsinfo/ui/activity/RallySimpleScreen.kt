package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
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
import be.appmire.gpsinfo.data.rally.RallyController
import be.appmire.gpsinfo.data.rally.RallyState
import java.util.Locale
import kotlin.math.abs

/**
 * Rally / Regularity — **Simple** layout: the live early/late delta and
 * target speed with one big Start/Stop, for use mid-stage. Setting up the
 * speed table, wheel sensors and ±recal lives in the Pro screen; the
 * app-bar toggle (and the idle-state button) switch to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RallySimpleScreen(
    onBack: () -> Unit,
    onShowDetailed: () -> Unit,
) {
    val state by RallyController.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rally_title)) },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val s = state) {
                is RallyState.Running -> {
                    val delta = s.deltaSeconds
                    val color = when {
                        abs(delta) < 1.0 -> Color(0xFF43A047)
                        abs(delta) < 3.0 -> Color(0xFFF9A825)
                        else -> Color(0xFFE53935)
                    }
                    Text(
                        "%+.0f s".format(Locale.ROOT, delta),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Text(
                        stringResource(
                            R.string.rally_simple_target,
                            s.targetSpeedKmh, s.drivenKm,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Button(
                        onClick = { RallyController.stop() },
                        modifier = Modifier.padding(top = 28.dp),
                    ) { Text(stringResource(R.string.rally_simple_stop)) }
                }
                is RallyState.Armed -> {
                    Text(
                        stringResource(R.string.rally_armed),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Button(
                        onClick = { RallyController.start() },
                        modifier = Modifier.padding(top = 24.dp),
                    ) { Text(stringResource(R.string.rally_simple_start)) }
                }
                RallyState.Idle -> {
                    Text(
                        stringResource(R.string.rally_simple_idle),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onShowDetailed,
                        modifier = Modifier.padding(top = 20.dp),
                    ) { Text(stringResource(R.string.rally_simple_setup)) }
                }
            }
        }
    }
}
