package be.appmire.gpsinfo.ui.obd

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.obd.ObdProbeController
import be.appmire.gpsinfo.obd.ObdStatus
import java.util.Locale

/**
 * OBD2 Lab — a logging-heavy probe for ELM327 adapters. Pick a paired
 * adapter, run the smart probe (it auto-detects which PIDs the vehicle
 * supports, reads the VIN, polls each), and watch the full ELM
 * transcript stream live. The session is also written to a file so
 * real-car runs build the data behind per-make sensor profiles.
 *
 * Read-only against the vehicle. The "confirm + wire" mapping step
 * (turning detected PIDs into app inputs) lands on top of this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObdLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ObdProbeController(context) }
    val state by controller.state.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) controller.refreshDevices() }

    LaunchedEffect(Unit) {
        controller.refreshDevices()
        if (!controller.hasConnectPermission() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OBD2 Lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusRow(state.status, state.error)

            if (state.status == ObdStatus.Idle) {
                if (!state.hasPermission) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }) { Text("Grant Bluetooth permission") }
                } else if (!state.bluetoothEnabled) {
                    Text(
                        "Bluetooth is off — enable it and pair your OBD adapter in system settings.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Paired adapters", style = MaterialTheme.typography.titleSmall)
                    if (state.devices.isEmpty()) {
                        Text(
                            "No paired Bluetooth devices. Pair your ELM327 adapter in system settings, then refresh.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.devices) { dev ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { controller.startProbe(dev.address) },
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(dev.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        dev.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { controller.refreshDevices() }) { Text("Refresh") }
                }
            }

            if (state.status == ObdStatus.Connecting || state.status == ObdStatus.Probing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { controller.disconnect() }) { Text("Cancel") }
            }

            state.report?.let { report ->
                ReportSummary(report)
                ProfileCard(report = report, onApply = { controller.applyProfile(it) })
            }

            // Live transcript console.
            if (state.log.isNotEmpty()) {
                Text("Transcript", style = MaterialTheme.typography.titleSmall)
                LogConsole(
                    lines = state.log,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                state.logFilePath?.let {
                    Text(
                        "Saved: $it",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusRow(status: ObdStatus, error: String?) {
    val label = when (status) {
        ObdStatus.Idle -> "Idle"
        ObdStatus.Connecting -> "Connecting…"
        ObdStatus.Probing -> "Probing…"
        ObdStatus.Done -> "Done"
        ObdStatus.Error -> "Error: ${error ?: ""}"
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (status == ObdStatus.Error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

@Composable
private fun ReportSummary(report: be.appmire.gpsinfo.obd.ProbeReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Adapter: ${report.adapterId ?: "?"}", style = MaterialTheme.typography.bodyMedium)
            Text("Protocol: ${report.protocol ?: "?"}", style = MaterialTheme.typography.bodyMedium)
            Text("VIN: ${report.vin ?: "unavailable"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${report.supportedPids.size} supported PIDs · " +
                    "${report.sensors.count { it.live }} returned data",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            report.sensors.filter { it.live }.forEach { s ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "%02X %s".format(s.pid, s.label),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = s.value?.let { "%.1f %s".format(Locale.ROOT, it, s.unit) }
                            ?: (s.rawHex ?: "—"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** The confirm step: apply a *tested profile*. The user picks which
 *  profile fits their car (auto-detected default) and sees the live
 *  values it produces — they never touch decode parameters; those live
 *  in the compiled, tested profile. "Apply" persists the choice and
 *  starts the live feed. */
@Composable
private fun ProfileCard(
    report: be.appmire.gpsinfo.obd.ProbeReport,
    onApply: (String) -> Unit,
) {
    val profiles = be.appmire.gpsinfo.obd.ObdProfiles.all
    var selected by remember(report) { mutableStateOf(report.suggestion.profileId) }
    var appliedId by remember(report) { mutableStateOf<String?>(null) }

    val vehicleKey = report.vin?.takeIf { it.isNotBlank() } ?: "this adapter"
    val mapping = remember(report, selected) {
        be.appmire.gpsinfo.obd.MappingSuggester
            .suggest(vehicleKey, report.roleReadings, forced = selected)
            .mapping
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vehicle profile", style = MaterialTheme.typography.titleSmall)
            Text(
                "Auto-detected: ${profiles.first { it.id == report.suggestion.profileId }.displayName}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    androidx.compose.material3.FilterChip(
                        selected = selected == p.id,
                        onClick = { selected = p.id; appliedId = null },
                        label = { Text(p.displayName) },
                    )
                }
            }

            // Live values this profile produces on this car — role names,
            // no raw requests or formulas.
            if (mapping == null || mapping.roles.isEmpty()) {
                Text(
                    "No live sensors for this profile on this vehicle.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                mapping.roles.forEach { (role, request) ->
                    val reading = report.roleReadings.firstOrNull {
                        it.role == role && it.request == request
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(role.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = reading?.value?.let { "%.1f %s".format(Locale.ROOT, it, role.unit) }
                                ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Button(
                onClick = { onApply(selected); appliedId = selected },
                enabled = mapping != null && mapping.roles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (appliedId == selected) "Applied — live feed started"
                    else "Apply this profile",
                )
            }
        }
    }
}

@Composable
private fun LogConsole(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
