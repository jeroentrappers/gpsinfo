package be.appmire.gpsinfo.ui.cyclingpower

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.CyclingPowerState
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * Pair-a-cycling-power-meter screen. Mirrors the HR pairing flow —
 * same permission dance, same scan-then-tap-to-pair UX — but filtered
 * to the Bluetooth-SIG Cycling Power Service (0x1818). Once paired,
 * the MAC is persisted and the app auto-reconnects on next launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CyclingPowerPairingScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val cpState by vm.cpState.collectAsStateWithLifecycle()
    var permissionRequested by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionGranted = grants.values.all { it }
        if (permissionGranted) vm.startCpScan()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 31 && !permissionRequested) {
            permissionRequested = true
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            )
        } else {
            permissionGranted = true
            vm.startCpScan()
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopCpScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cp_pair_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.cp_pair_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectionStatusRow(cpState = cpState, onForget = vm::forgetCpDevice)

            if (!permissionGranted && permissionRequested) {
                Text(
                    text = stringResource(R.string.cp_pair_needs_permissions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cpState is CyclingPowerState.Scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.cp_pair_scanning),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = vm::startCpScan) {
                        Text(stringResource(R.string.cp_pair_scan_again))
                    }
                }
            }

            val devices = vm.cpScanResultsView.values.sortedByDescending { it.rssi }
            if (devices.isEmpty() && cpState is CyclingPowerState.Scanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cp_pair_no_devices_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(devices, key = { it.device.address }) { result ->
                        DeviceRow(
                            result = result,
                            onPair = {
                                vm.stopCpScan()
                                vm.pairCpDevice(result.device.address, friendlyNameOf(result))
                                onBack()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(
    cpState: CyclingPowerState,
    onForget: () -> Unit,
) {
    val (label, action) = when (cpState) {
        CyclingPowerState.Idle -> stringResource(R.string.cp_status_idle) to null
        CyclingPowerState.Scanning -> stringResource(R.string.cp_status_scanning) to null
        is CyclingPowerState.Connecting ->
            stringResource(R.string.cp_status_connecting, cpState.deviceMac) to onForget
        is CyclingPowerState.Connected -> {
            val labelText = if (cpState.deviceName != null)
                stringResource(R.string.cp_status_connected_named, cpState.deviceName, cpState.lastWatts ?: 0)
            else stringResource(R.string.cp_status_connected, cpState.lastWatts ?: 0)
            labelText to onForget
        }
        is CyclingPowerState.Disconnected -> {
            val nameOrMac = cpState.deviceName ?: cpState.deviceMac
            stringResource(R.string.cp_status_disconnected, nameOrMac) to onForget
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                androidx.compose.material3.TextButton(onClick = action) {
                    Text(stringResource(R.string.cp_forget_device))
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun friendlyNameOf(result: ScanResult): String? =
    result.scanRecord?.deviceName
        ?: try { result.device.name } catch (_: SecurityException) { null }

@Composable
private fun DeviceRow(result: ScanResult, onPair: () -> Unit) {
    val name = friendlyNameOf(result) ?: stringResource(R.string.cp_pair_unknown_device)
    val mac = result.device.address
    val rssi = result.rssi
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPair),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "$mac · ${rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
