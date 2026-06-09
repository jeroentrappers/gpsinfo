package be.appmire.gpsinfo.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A bonded Bluetooth device the user can pick as the OBD adapter. */
data class ObdDevice(val name: String, val address: String)

/**
 * Bluetooth-Classic SPP (RFCOMM) transport for ELM327 OBD2 dongles —
 * the same Serial Port Profile UUID the NMEA bridge uses, just on the
 * client side. Ported from id.dash; keeps the reflective channel-1
 * fallback for clone dongles that don't advertise an SDP record.
 *
 * Blocking socket I/O, confined to [Dispatchers.IO]. One adapter is
 * half-duplex, so callers (ObdManager) serialise traffic on a mutex.
 */
class ObdConnection(context: Context) {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    val isConnected: Boolean get() = socket?.isConnected == true

    val isBluetoothEnabled: Boolean
        @SuppressLint("MissingPermission")
        get() = hasConnectPermission() && adapter?.isEnabled == true

    /** Connect-time permission: BLUETOOTH_CONNECT on API 31+, implicit
     *  on older levels (the legacy BLUETOOTH permission is install-time). */
    fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<ObdDevice> {
        if (!hasConnectPermission()) return emptyList()
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return a.bondedDevices.orEmpty().map { ObdDevice(it.name ?: "(unnamed)", it.address) }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) throw SecurityException("Bluetooth permission not granted")
        val a = adapter ?: throw IOException("Bluetooth not supported on this device")
        if (!a.isEnabled) throw IOException("Bluetooth is disabled")

        val device = a.getRemoteDevice(address)
        runCatching { a.cancelDiscovery() } // discovery interferes with connect

        val primary = device.createRfcommSocketToServiceRecord(SPP_UUID)
        val connected: BluetoothSocket = try {
            primary.connect()
            primary
        } catch (e: IOException) {
            // Clone dongles often lack an SDP record for the SPP UUID —
            // reflectively open RFCOMM channel 1 directly.
            Log.w(TAG, "SDP connect failed, trying reflective channel 1: ${e.message}")
            runCatching { primary.close() }
            val fallback = try {
                @Suppress("DiscouragedPrivateApi")
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            } catch (ref: Exception) {
                throw IOException("Connect failed: ${e.message}", e)
            }
            fallback.connect()
            fallback
        }
        socket = connected
        input = connected.inputStream
        output = connected.outputStream
    }

    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val o = output ?: throw IOException("Not connected")
        o.write(bytes)
        o.flush()
    }

    /** Read until the ELM327 ">" prompt or [timeoutMs]. The prompt is
     *  the natural reply delimiter. */
    suspend fun readUntilPrompt(timeoutMs: Long = 2_000): String = withContext(Dispatchers.IO) {
        val i = input ?: throw IOException("Not connected")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(64)
        while (System.currentTimeMillis() < deadline) {
            if (i.available() > 0) {
                val n = i.read(buf)
                if (n > 0) {
                    sb.append(String(buf, 0, n, Charsets.US_ASCII))
                    if (sb.contains('>')) return@withContext sb.toString()
                }
            } else {
                Thread.sleep(10)
            }
        }
        throw IOException("Timed out waiting for ELM327 prompt; buffered=\"$sb\"")
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        private const val TAG = "ObdConnection"

        /** Canonical Bluetooth Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
