package be.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Mirrors raw NMEA sentences from the platform GNSS to one or more
 * Bluetooth-SPP (RFCOMM) clients. The intended audience is marine
 * chart-plotters, OBD-II dashboards, and the small ecosystem of
 * desktop tools that read NMEA from a serial-over-Bluetooth pairing —
 * pair the phone in the system Bluetooth settings, point the
 * downstream tool at the same SPP UUID, and our app behaves like an
 * external GPS puck.
 *
 * The SPP UUID is the canonical Bluetooth Serial Port Profile UUID
 * (`00001101-0000-1000-8000-00805F9B34FB`); every chart-plotter,
 * arduino BT module, and serial-bridge utility recognises it.
 *
 * Connections are kept open until the client disconnects or the
 * bridge is stopped. Each sentence is written with `\r\n` line
 * endings — NMEA-0183 spec.
 *
 * Threading: one accept thread for new clients, one shared executor
 * for writes. No coroutines — sockets are blocking and the JDK SPP
 * APIs aren't suspending.
 */
class NmeaBluetoothBridge(private val appContext: Context) {

    private val lm: LocationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nmea-bt-writer").apply { isDaemon = true }
    }
    private var acceptThread: Thread? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var nmeaListener: OnNmeaMessageListener? = null

    // CopyOnWriteArrayList so the write loop can iterate without
    // synchronisation while the accept loop adds new clients. Lists
    // are tiny (typically 0-1 client) so the copy cost is negligible.
    private val clients = CopyOnWriteArrayList<ClientLink>()

    @Volatile private var running = false

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

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasConnectPermission() || !hasFineLocationPermission()) return false
        val adapter = bluetoothAdapter ?: return false
        if (!adapter.isEnabled) return false

        val server = runCatching {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        }.getOrNull() ?: return false
        serverSocket = server
        running = true

        val accept = Thread({ runAccept(server) }, "nmea-bt-accept")
        accept.isDaemon = true
        acceptThread = accept
        accept.start()

        val l = OnNmeaMessageListener { message, _ ->
            val sentence = message.trimEnd('\r', '\n') + "\r\n"
            broadcast(sentence.toByteArray(Charsets.US_ASCII))
        }
        try {
            lm.addNmeaListener(writeExecutor, l)
            nmeaListener = l
        } catch (_: SecurityException) {
            stop()
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun runAccept(server: BluetoothServerSocket) {
        while (running) {
            val sock: BluetoothSocket = try {
                server.accept()
            } catch (_: Throwable) {
                return
            }
            try {
                val out = sock.outputStream
                clients.add(ClientLink(sock, out))
            } catch (t: Throwable) {
                Log.w(TAG, "Could not open client outputStream", t)
                sock.runCatching { close() }
            }
        }
    }

    private fun broadcast(bytes: ByteArray) {
        if (clients.isEmpty()) return
        val dead = ArrayList<ClientLink>(0)
        for (c in clients) {
            try {
                c.out.write(bytes)
                c.out.flush()
            } catch (_: Throwable) {
                dead.add(c)
            }
        }
        if (dead.isNotEmpty()) {
            for (c in dead) {
                c.socket.runCatching { close() }
            }
            clients.removeAll(dead)
        }
    }

    fun stop() {
        running = false
        nmeaListener?.let {
            runCatching { lm.removeNmeaListener(it) }
        }
        nmeaListener = null
        serverSocket?.runCatching { close() }
        serverSocket = null
        acceptThread = null
        for (c in clients) c.socket.runCatching { close() }
        clients.clear()
    }

    val connectedClientCount: Int get() = clients.size

    private data class ClientLink(val socket: BluetoothSocket, val out: OutputStream)

    companion object {
        private const val TAG = "NmeaBluetoothBridge"

        /** Canonical Bluetooth Serial Port Profile UUID. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Display name shown when a remote device queries the SDP
         *  record. Short and unambiguous — most chart-plotters truncate
         *  to ~20 chars in their pairing list. */
        private const val SERVICE_NAME = "GPSinfo NMEA"

        @Volatile private var instance: NmeaBluetoothBridge? = null
        fun getInstance(context: Context): NmeaBluetoothBridge =
            instance ?: synchronized(this) {
                instance ?: NmeaBluetoothBridge(context.applicationContext)
                    .also { instance = it }
            }
    }
}
