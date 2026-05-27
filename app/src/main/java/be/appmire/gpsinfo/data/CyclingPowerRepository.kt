package be.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import be.appmire.gpsinfo.data.model.CyclingPowerReading
import be.appmire.gpsinfo.data.model.CyclingPowerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Bluetooth-SIG Cycling Power Service ([CP_SERVICE_UUID]) and its
 * mandatory measurement characteristic ([CP_MEASUREMENT_UUID]). We
 * only consume devices advertising this standard profile — no
 * vendor SDKs, no ANT+. The Wahoo Kickr, the 4iiii Precision, every
 * Stages crank, every Quarq spider, every Garmin Vector pedal pair,
 * and a long tail of cheap Chinese power meters all speak it.
 */
private val CP_SERVICE_UUID: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
private val CP_MEASUREMENT_UUID: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE Cycling Power manager — same shape as [HeartRateRepository].
 * Owns one GATT connection at a time; reads the paired device MAC
 * from [SettingsRepository] on startup and re-establishes the link.
 *
 * Permission handling mirrors HR — all GATT calls are guarded with
 * `hasConnectPermission()`; the repo sits at `Idle` /
 * `Disconnected` when permissions aren't granted.
 */
class CyclingPowerRepository(private val appContext: Context) {

    private val _state = MutableStateFlow<CyclingPowerState>(CyclingPowerState.Idle)
    val state: StateFlow<CyclingPowerState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var lastScanResults: MutableMap<String, ScanResult> = mutableMapOf()

    val lastScanResultsView: Map<String, ScanResult> get() = lastScanResults.toMap()

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
                ?: return null
            return manager.adapter
        }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) return
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        disconnect()
        lastScanResults.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                lastScanResults[result.device.address] = result
                _state.value = CyclingPowerState.Scanning
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { lastScanResults[it.device.address] = it }
                _state.value = CyclingPowerState.Scanning
            }
            override fun onScanFailed(errorCode: Int) {
                _state.value = CyclingPowerState.Idle
            }
        }
        scanCallback = cb
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CP_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        _state.value = CyclingPowerState.Scanning
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val cb = scanCallback ?: return
        if (hasScanPermission()) scanner.stopScan(cb)
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun connect(mac: String, friendlyName: String?) {
        if (!hasConnectPermission()) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        stopScan()
        val existing = gatt
        if (existing != null && existing.device.address == mac) return
        existing?.close()
        gatt = null

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        _state.value = CyclingPowerState.Connecting(mac)
        gatt = device.connectGatt(
            appContext,
            // autoConnect=true so the OS retries reconnects without us
            // running our own back-off loop. Same rationale as HR.
            true,
            gattCallback(friendlyName),
        )
    }

    fun connectIfPaired(settings: SettingsRepository) {
        scope.launch {
            val mac = settings.cpDeviceMac.first() ?: return@launch
            val name = settings.cpDeviceName.first()
            connect(mac, name)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        gatt?.let {
            if (hasConnectPermission()) it.disconnect()
            it.close()
        }
        gatt = null
        val current = _state.value
        _state.value = when (current) {
            is CyclingPowerState.Connecting -> CyclingPowerState.Disconnected(current.deviceMac, null)
            is CyclingPowerState.Connected -> CyclingPowerState.Disconnected(current.deviceMac, current.deviceName)
            is CyclingPowerState.Disconnected -> current
            else -> CyclingPowerState.Idle
        }
    }

    private fun gattCallback(friendlyName: String?) = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (hasConnectPermission()) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = CyclingPowerState.Disconnected(gatt.device.address, friendlyName)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(CP_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CP_MEASUREMENT_UUID) ?: return
            if (!hasConnectPermission()) return
            gatt.setCharacteristicNotification(char, true)
            val ccc = char.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(ccc)
            }
            _state.value = CyclingPowerState.Connected(
                deviceMac = gatt.device.address,
                deviceName = friendlyName,
                lastWatts = null,
                lastSampleAt = System.currentTimeMillis(),
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != CP_MEASUREMENT_UUID) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleCpPayload(gatt, data, friendlyName)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != CP_MEASUREMENT_UUID) return
            handleCpPayload(gatt, value, friendlyName)
        }
    }

    private fun handleCpPayload(gatt: BluetoothGatt, data: ByteArray, friendlyName: String?) {
        val reading = parseCyclingPowerMeasurement(data) ?: return
        _state.value = CyclingPowerState.Connected(
            deviceMac = gatt.device.address,
            deviceName = friendlyName,
            lastWatts = reading.watts,
            lastSampleAt = System.currentTimeMillis(),
        )
    }

    companion object {
        /**
         * Decode a Cycling Power Measurement (0x2A63) payload.
         *
         * Layout per the Bluetooth Cycling Power Service spec:
         *   bytes 0-1 : Flags (uint16 LE) — optional-field selector
         *   bytes 2-3 : Instantaneous Power (sint16 LE, watts)
         *   bytes 4+  : Optional fields (Pedal Power Balance, Accum.
         *               Torque, Wheel Rev Data, Crank Rev Data, etc.)
         *
         * We only consume the instantaneous power — the headline
         * cycling metric and the only mandatory non-flag field.
         */
        internal fun parseCyclingPowerMeasurement(data: ByteArray): CyclingPowerReading? {
            if (data.size < 4) return null
            // sint16 LE — sign-extend before returning.
            val low = data[2].toInt() and 0xFF
            val high = data[3].toInt()
            val watts = (high shl 8) or low
            // Sanity-clamp: real meters never exceed 4000 W (Tour de
            // France sprinters peak below 2000). Out-of-range values
            // are almost always a malformed payload.
            if (watts !in -200..4000) return null
            return CyclingPowerReading(watts = watts.coerceAtLeast(0))
        }

        @Volatile private var instance: CyclingPowerRepository? = null
        fun getInstance(context: Context): CyclingPowerRepository =
            instance ?: synchronized(this) {
                instance ?: CyclingPowerRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
