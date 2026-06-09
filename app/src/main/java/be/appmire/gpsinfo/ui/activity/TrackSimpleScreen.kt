package be.appmire.gpsinfo.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.TrailNaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Track & Train — **Simple** layout: one big Start / Stop, with distance,
 * time and pace while recording. Pro is the full dashboard (cards, sports
 * view, sensors, laps); the app-bar toggle switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSimpleScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
    onShowDetailed: () -> Unit,
) {
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rec = recording as? RecordingState.Recording

    // Tick every second so the elapsed time advances.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(rec != null) {
        while (rec != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.track_title)) },
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
            if (rec != null) {
                val km = rec.distanceMetres / 1000.0
                val elapsedSec = ((now - rec.startedAtMillis).coerceAtLeast(0L)) / 1000
                val paceStr = if (km > 0.05) {
                    val secPerKm = elapsedSec / km
                    "%d:%02d".format(Locale.ROOT, (secPerKm / 60).toInt(), (secPerKm % 60).toInt())
                } else "—"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Readout(stringResource(R.string.track_distance), "%.2f km".format(Locale.ROOT, km))
                    Readout(
                        stringResource(R.string.track_time),
                        "%d:%02d".format(Locale.ROOT, elapsedSec / 60, elapsedSec % 60),
                    )
                    Readout(stringResource(R.string.track_pace), "$paceStr /km")
                }
                Button(
                    onClick = {
                        scope.launch {
                            vm.stopRecording(context, TrailNaming.defaultTrailName(System.currentTimeMillis()))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) { Text(stringResource(R.string.track_stop)) }
            } else {
                Button(onClick = { vm.startRecording(context) }) {
                    Text(stringResource(R.string.track_start))
                }
            }
        }
    }
}

@Composable
private fun Readout(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
