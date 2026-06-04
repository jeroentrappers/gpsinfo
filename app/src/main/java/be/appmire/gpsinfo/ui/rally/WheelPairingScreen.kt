package be.appmire.gpsinfo.ui.rally

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
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.SettingsRepository
import be.appmire.gpsinfo.data.WheelSensorRepository
import be.appmire.gpsinfo.data.model.WheelSensorState
import kotlinx.coroutines.launch

/**
 * Pair-a-wheel-speed-sensor screen. Mirrors the HR/CP pairing flows —
 * same permission dance, same scan-then-tap-to-pair UX — filtered to
 * the Bluetooth-SIG Cycling Speed and Cadence service (0x1816).
 *
 * Self-contained on [WheelSensorRepository] + [SettingsRepository]
 * rather than threading through DashboardViewModel: the wheel sensor
 * exists for the rally subsystem, not the dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WheelPairingScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { WheelSensorRepository.getInstance(context) }
    val settings = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val wheelState by repo.state.collectAsStateWithLifecycle()
    var permissionRequested by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        permissionGranted = grants.values.all { it }
        if (permissionGranted) repo.startScan()
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
            repo.startScan()
        }
    }

    DisposableEffect(Unit) {
        onDispose { repo.stopScan() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wheel_pair_title)) },
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
                text = stringResource(R.string.wheel_pair_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ConnectionStatusRow(
                wheelState = wheelState,
                onForget = {
                    repo.disconnect()
                    scope.launch { settings.setWheelDevice(null, null) }
                },
            )

            if (!permissionGranted && permissionRequested) {
                Text(
                    text = stringResource(R.string.wheel_pair_needs_permissions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wheelState is WheelSensorState.Scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.wheel_pair_scanning),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = repo::startScan) {
                        Text(stringResource(R.string.wheel_pair_scan_again))
                    }
                }
            }

            val devices = repo.lastScanResultsView.values.sortedByDescending { it.rssi }
            if (devices.isEmpty() && wheelState is WheelSensorState.Scanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.wheel_pair_no_devices_yet),
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
                                repo.stopScan()
                                val name = friendlyNameOf(result)
                                scope.launch {
                                    settings.setWheelDevice(result.device.address, name)
                                }
                                repo.connect(result.device.address, name)
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
    wheelState: WheelSensorState,
    onForget: () -> Unit,
) {
    val (label, action) = when (wheelState) {
        WheelSensorState.Idle -> stringResource(R.string.wheel_status_idle) to null
        WheelSensorState.Scanning -> stringResource(R.string.wheel_status_scanning) to null
        is WheelSensorState.Connecting ->
            stringResource(R.string.wheel_status_connecting, wheelState.deviceMac) to onForget
        is WheelSensorState.Connected -> {
            val nameOrMac = wheelState.deviceName ?: wheelState.deviceMac
            stringResource(
                R.string.wheel_status_connected,
                nameOrMac,
                wheelState.lastCumulativeRevs ?: 0L,
            ) to onForget
        }
        is WheelSensorState.Disconnected -> {
            val nameOrMac = wheelState.deviceName ?: wheelState.deviceMac
            stringResource(R.string.wheel_status_disconnected, nameOrMac) to onForget
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
                TextButton(onClick = action) {
                    Text(stringResource(R.string.wheel_forget_device))
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
    val name = friendlyNameOf(result) ?: stringResource(R.string.wheel_pair_unknown_device)
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
                Icons.Outlined.Speed,
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
