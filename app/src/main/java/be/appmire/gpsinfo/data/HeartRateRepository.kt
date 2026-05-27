package be.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import be.appmire.gpsinfo.data.model.HeartRateReading
import be.appmire.gpsinfo.data.model.HeartRateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Standard Bluetooth-SIG Heart Rate Service / Heart Rate Measurement
 * UUIDs. We exclusively talk to devices that advertise the standard
 * profile (Polar straps, Wahoo TICKR, most Garmin chest belts, many
 * wrist-based monitors); proprietary protocols (older ANT+, vendor
 * SDKs) are explicitly out of scope to keep the no-cloud-dependency
 * stance.
 */
private val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Singleton-ish BLE heart-rate manager. Owns one GATT connection at a
 * time; reads the paired device MAC from [SettingsRepository] and
 * maintains a connection lifecycle independently of the activity.
 *
 * Scoped to the Application — created once at process start. Pause /
 * resume is driven externally (the activity calls [connectIfPaired] /
 * [disconnect] from its lifecycle observer).
 *
 * Permission handling: all GATT calls are guarded with
 * `hasConnectPermission()`. The repository never crashes when the
 * runtime grant is missing — it just sits at `Idle` / `Disconnected`.
 */
class HeartRateRepository(private val appContext: Context) {

    private val _state = MutableStateFlow<HeartRateState>(HeartRateState.Idle)
    val state: StateFlow<HeartRateState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var lastScanResults: MutableMap<String, ScanResult> = mutableMapOf()

    /** Latest scan results from an active scan, keyed by MAC. UI uses
     *  this when picking a device to pair. Empty outside an active scan. */
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

    /**
     * Begin a BLE scan filtered to HR-service advertisements. Results
     * collect into [lastScanResultsView] and emit a [HeartRateState.Scanning]
     * for the duration. Caller is expected to call [stopScan] once it
     * has shown the picker and the user has chosen (or cancelled).
     *
     * No-op when BLE is off or BLUETOOTH_SCAN isn't granted — the UI
     * observes [state] and surfaces an appropriate empty state.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) return
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        // Cancel any in-flight connection while scanning — the device
        // can't accept new advertisements while servicing GATT.
        disconnect()
        lastScanResults.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                lastScanResults[result.device.address] = result
                // Re-emit Scanning so observers re-render the list.
                _state.value = HeartRateState.Scanning
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { lastScanResults[it.device.address] = it }
                _state.value = HeartRateState.Scanning
            }
            override fun onScanFailed(errorCode: Int) {
                // Just drop back to Idle — the picker UI can prompt for
                // permission / Bluetooth-on as appropriate.
                _state.value = HeartRateState.Idle
            }
        }
        scanCallback = cb
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        _state.value = HeartRateState.Scanning
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val cb = scanCallback ?: return
        if (hasScanPermission()) scanner.stopScan(cb)
        scanCallback = null
    }

    /**
     * Connect to the device identified by [mac]. Idempotent — if
     * we're already connected to the same MAC, nothing changes.
     * Stops any active scan first.
     */
    @SuppressLint("MissingPermission")
    fun connect(mac: String, friendlyName: String?) {
        if (!hasConnectPermission()) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        stopScan()
        // If we're already wired up to this MAC, leave it alone.
        val existing = gatt
        if (existing != null && existing.device.address == mac) return
        // Otherwise tear the old connection down before starting a new one.
        existing?.close()
        gatt = null

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        _state.value = HeartRateState.Connecting(mac)
        gatt = device.connectGatt(
            appContext,
            // autoConnect=true → OS retries reconnects on its own, which is
            // exactly what we want for a paired strap that goes in and out
            // of range. The first connect on cold start may be slightly
            // slower but subsequent reconnects are essentially free.
            true,
            gattCallback(friendlyName),
        )
    }

    /**
     * Resume connection to the persisted device, if any. Called from
     * the activity ON_RESUME so a user who paired in a previous
     * session goes straight to a live connection on next launch.
     */
    fun connectIfPaired(settings: SettingsRepository) {
        scope.launch {
            val mac = settings.hrDeviceMac.first() ?: return@launch
            val name = settings.hrDeviceName.first()
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
        // If a device was paired, surface a Disconnected state so the
        // UI knows we'll retry; otherwise fall back to Idle.
        val current = _state.value
        _state.value = when (current) {
            is HeartRateState.Connecting -> HeartRateState.Disconnected(current.deviceMac, null)
            is HeartRateState.Connected -> HeartRateState.Disconnected(current.deviceMac, current.deviceName)
            is HeartRateState.Disconnected -> current
            else -> HeartRateState.Idle
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
                    _state.value = HeartRateState.Disconnected(gatt.device.address, friendlyName)
                    // autoConnect=true above means the OS handles reconnect;
                    // we don't manage it manually here.
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(HR_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(HR_MEASUREMENT_UUID) ?: return
            if (!hasConnectPermission()) return
            gatt.setCharacteristicNotification(char, true)
            // Write the Client Characteristic Configuration Descriptor
            // so the peripheral starts sending notifications.
            val ccc = char.getDescriptor(CCC_DESCRIPTOR_UUID) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(
                    ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                )
            } else {
                @Suppress("DEPRECATION")
                ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(ccc)
            }
            _state.value = HeartRateState.Connected(
                deviceMac = gatt.device.address,
                deviceName = friendlyName,
                lastBpm = null,
                lastSampleAt = System.currentTimeMillis(),
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT_UUID) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleHrPayload(gatt, data, friendlyName)
        }

        // API 33+ overload: payload comes through as a parameter rather
        // than being read off the characteristic.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT_UUID) return
            handleHrPayload(gatt, value, friendlyName)
        }
    }

    private fun handleHrPayload(gatt: BluetoothGatt, data: ByteArray, friendlyName: String?) {
        val reading = parseHeartRateMeasurement(data) ?: return
        _state.value = HeartRateState.Connected(
            deviceMac = gatt.device.address,
            deviceName = friendlyName,
            lastBpm = reading.bpm,
            lastSampleAt = System.currentTimeMillis(),
            lastRrIntervalsMs = reading.rrIntervalsMs,
        )
    }

    companion object {
        /**
         * Decode a Heart Rate Measurement characteristic payload per the
         * Bluetooth SIG spec. First byte is a flags byte:
         *
         *   bit 0   — 0 = uint8 HR value, 1 = uint16 HR value
         *   bit 1-2 — sensor contact status (00/01 = not supported;
         *             10 = sensor in contact; 11 = sensor not in contact)
         *   bit 3   — energy expended field present (uint16)
         *   bit 4   — RR-interval values present (one or more uint16)
         *
         * Public so a unit test can exercise the parsing independently
         * of the GATT lifecycle.
         */
        internal fun parseHeartRateMeasurement(data: ByteArray): HeartRateReading? {
            if (data.isEmpty()) return null
            val flags = data[0].toInt() and 0xFF
            val is16 = (flags and 0x01) != 0
            val sensorContactBits = (flags shr 1) and 0x03
            val hasEnergy = (flags and 0x08) != 0
            val hasRr = (flags and 0x10) != 0

            var offset = 1
            val bpm: Int = if (is16) {
                if (data.size < offset + 2) return null
                val v = ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    (data[offset].toInt() and 0xFF)
                offset += 2
                v
            } else {
                if (data.size < offset + 1) return null
                val v = data[offset].toInt() and 0xFF
                offset += 1
                v
            }
            if (bpm !in 0..400) return null   // sanity
            // Skip energy-expended bytes if present (uint16).
            if (hasEnergy) {
                if (data.size < offset + 2) return null
                offset += 2
            }
            // Each RR-interval is uint16, units of 1/1024 s.
            val rrIntervals = if (hasRr) {
                val out = mutableListOf<Int>()
                while (offset + 1 < data.size) {
                    val raw = ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        (data[offset].toInt() and 0xFF)
                    out.add(raw * 1000 / 1024)
                    offset += 2
                }
                out
            } else emptyList()

            val contact = when (sensorContactBits) {
                0b10 -> HeartRateReading.SensorContact.InContact
                0b11 -> HeartRateReading.SensorContact.NotInContact
                else -> HeartRateReading.SensorContact.NotSupported
            }
            return HeartRateReading(bpm = bpm, rrIntervalsMs = rrIntervals, sensorContact = contact)
        }

        @Volatile private var instance: HeartRateRepository? = null
        fun getInstance(context: Context): HeartRateRepository =
            instance ?: synchronized(this) {
                instance ?: HeartRateRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}

