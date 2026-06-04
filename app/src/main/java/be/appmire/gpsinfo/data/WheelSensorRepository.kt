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
import be.appmire.gpsinfo.data.model.WheelDeviceStatus
import be.appmire.gpsinfo.data.model.WheelReading
import be.appmire.gpsinfo.data.rally.RallyController
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bluetooth-SIG Cycling Speed and Cadence service ([CSC_SERVICE_UUID])
 * and its mandatory measurement characteristic ([CSC_MEASUREMENT_UUID]).
 * Garmin/Wahoo/Magene speed sensors and the whole commodity long tail
 * speak it — strapped to car wheel hubs, any of them becomes a
 * wireless rally wheel probe.
 */
private val CSC_SERVICE_UUID: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
private val CSC_MEASUREMENT_UUID: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * BLE wheel-speed-sensor manager. Unlike the HR/CP repositories this
 * one owns **N concurrent GATT connections** — the rally computer
 * averages every fresh probe, and two probes on one axle measure the
 * vehicle-centreline distance. Same per-link pattern otherwise:
 * `autoConnect=true` so the OS owns each reconnect loop, paired MACs
 * persisted via [SettingsRepository].
 *
 * Every decoded wheel-revolution sample is pushed straight into
 * [RallyController.offerWheelRevolutions] keyed by the device MAC —
 * the regularity computer is the consumer this sensor exists for.
 */
class WheelSensorRepository(private val appContext: Context) {

    /** Live status per paired/connected device, keyed by MAC. */
    private val _devices = MutableStateFlow<Map<String, WheelDeviceStatus>>(emptyMap())
    val devices: StateFlow<Map<String, WheelDeviceStatus>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val connections = HashMap<String, BluetoothGatt>()
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

    /** Scan for CSC devices. Existing connections stay up — adding a
     *  second probe shouldn't drop the first. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPermission()) return
        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        stopScan()
        lastScanResults.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                lastScanResults[result.device.address] = result
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { lastScanResults[it.device.address] = it }
            }
            override fun onScanFailed(errorCode: Int) {
                _scanning.value = false
            }
        }
        scanCallback = cb
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CSC_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, cb)
        _scanning.value = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        _scanning.value = false
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
        if (connections.containsKey(mac)) return

        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        updateStatus(mac) {
            (it ?: blankStatus(mac, friendlyName)).copy(name = friendlyName ?: it?.name)
        }
        connections[mac] = device.connectGatt(
            appContext,
            // autoConnect=true so the OS retries reconnects without us
            // running our own back-off loop. Same rationale as HR/CP.
            true,
            gattCallback(mac, friendlyName),
        )
    }

    /** Re-establish links to every stored probe. */
    fun connectIfPaired(settings: SettingsRepository) {
        scope.launch {
            settings.wheelDevices.first().forEach { (mac, name) -> connect(mac, name) }
        }
    }

    /** Drop one probe: close its link and forget its status. */
    @SuppressLint("MissingPermission")
    fun forget(mac: String) {
        connections.remove(mac)?.let {
            if (hasConnectPermission()) it.disconnect()
            it.close()
        }
        _devices.value = _devices.value - mac
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connections.values.forEach {
            if (hasConnectPermission()) it.disconnect()
            it.close()
        }
        connections.clear()
        _devices.value = _devices.value.mapValues { (_, d) -> d.copy(connected = false) }
    }

    private fun blankStatus(mac: String, name: String?) = WheelDeviceStatus(
        mac = mac,
        name = name,
        connected = false,
        lastCumulativeRevs = null,
        lastSampleAt = 0L,
    )

    private fun updateStatus(
        mac: String,
        transform: (WheelDeviceStatus?) -> WheelDeviceStatus,
    ) {
        _devices.value = _devices.value + (mac to transform(_devices.value[mac]))
    }

    private fun gattCallback(mac: String, friendlyName: String?) = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (hasConnectPermission()) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateStatus(mac) {
                        (it ?: blankStatus(mac, friendlyName)).copy(connected = false)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(CSC_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CSC_MEASUREMENT_UUID) ?: return
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
            updateStatus(mac) {
                (it ?: blankStatus(mac, friendlyName)).copy(connected = true)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != CSC_MEASUREMENT_UUID) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleCscPayload(mac, data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != CSC_MEASUREMENT_UUID) return
            handleCscPayload(mac, value)
        }
    }

    private fun handleCscPayload(mac: String, data: ByteArray) {
        val reading = parseCscMeasurement(data) ?: return
        updateStatus(mac) {
            (it ?: blankStatus(mac, null)).copy(
                connected = true,
                lastCumulativeRevs = reading.cumulativeRevs,
                lastSampleAt = System.currentTimeMillis(),
            )
        }
        RallyController.offerWheelRevolutions(mac, reading.cumulativeRevs)
    }

    companion object {
        /**
         * Decode a CSC Measurement (0x2A5B) payload.
         *
         * Layout per the Bluetooth CSC Service spec:
         *   byte 0    : Flags — bit0 = wheel revolution data present,
         *               bit1 = crank revolution data present
         *   bytes 1-4 : Cumulative Wheel Revolutions (uint32 LE)  [bit0]
         *   bytes 5-6 : Last Wheel Event Time (uint16 LE, 1/1024 s) [bit0]
         *   ...       : Crank fields when bit1 is set (ignored here)
         *
         * Cadence-only sensors (bit0 clear) return null — pairing UX
         * filters on the service UUID, which both kinds advertise.
         */
        internal fun parseCscMeasurement(data: ByteArray): WheelReading? {
            if (data.isEmpty()) return null
            val flags = data[0].toInt() and 0xFF
            if (flags and 0x01 == 0) return null
            if (data.size < 7) return null
            val revs = (data[1].toLong() and 0xFF) or
                ((data[2].toLong() and 0xFF) shl 8) or
                ((data[3].toLong() and 0xFF) shl 16) or
                ((data[4].toLong() and 0xFF) shl 24)
            val eventTime = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
            return WheelReading(cumulativeRevs = revs, lastEventTime1024 = eventTime)
        }

        @Volatile private var instance: WheelSensorRepository? = null
        fun getInstance(context: Context): WheelSensorRepository =
            instance ?: synchronized(this) {
                instance ?: WheelSensorRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
