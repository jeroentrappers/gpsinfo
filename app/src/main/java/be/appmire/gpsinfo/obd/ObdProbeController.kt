package be.appmire.gpsinfo.obd

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ObdStatus { Idle, Connecting, Probing, Done, Error }

data class ObdLabState(
    val status: ObdStatus = ObdStatus.Idle,
    /** Observable mirrors of the live permission / adapter checks, so a
     *  grant or BT-toggle actually recomposes the screen (reading the
     *  raw checks in composition wouldn't — they're not snapshot state). */
    val hasPermission: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val devices: List<ObdDevice> = emptyList(),
    val log: List<String> = emptyList(),
    val report: ProbeReport? = null,
    val error: String? = null,
    /** Absolute path of the written session transcript, when finished. */
    val logFilePath: String? = null,
)

/**
 * Orchestrates a single OBD Lab session — connect to a chosen adapter,
 * run the [SmartProbe], stream a heavily-logged transcript into the UI
 * state, and persist it to a shareable file. Read-only against the
 * vehicle; intended as the data-gathering tool behind the eventual
 * "confirm + wire" mapping step.
 */
class ObdProbeController(context: Context) {

    private val appContext = context.applicationContext
    private val connection = ObdConnection(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(ObdLabState())
    val state: StateFlow<ObdLabState> = _state.asStateFlow()

    private val transcript = StringBuilder()

    fun hasConnectPermission(): Boolean = connection.hasConnectPermission()

    fun isBluetoothEnabled(): Boolean = connection.isBluetoothEnabled

    fun refreshDevices() {
        _state.value = _state.value.copy(
            hasPermission = connection.hasConnectPermission(),
            bluetoothEnabled = connection.isBluetoothEnabled,
            devices = connection.pairedDevices(),
        )
    }

    /** Connect to [address] and run the probe. Safe to call again — the
     *  previous session is cancelled and the connection reset first. */
    fun startProbe(address: String) {
        job?.cancel()
        connection.close()
        transcript.setLength(0)
        _state.value = ObdLabState(
            status = ObdStatus.Connecting,
            devices = _state.value.devices,
        )
        job = scope.launch {
            try {
                log("connecting to $address")
                connection.connect(address)
                log("connected")
                _state.value = _state.value.copy(status = ObdStatus.Probing)
                val report = SmartProbe(ObdManager(connection, ::log), ::log).probe()
                val path = writeTranscript()
                _state.value = _state.value.copy(
                    status = ObdStatus.Done,
                    report = report,
                    logFilePath = path,
                )
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                val path = writeTranscript()
                _state.value = _state.value.copy(
                    status = ObdStatus.Error,
                    error = e.message ?: "Probe failed",
                    logFilePath = path,
                )
            } finally {
                connection.close()
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        connection.close()
        _state.value = _state.value.copy(status = ObdStatus.Idle)
    }

    private fun log(line: String) {
        val stamped = "${TS.format(Date(System.currentTimeMillis()))}  $line"
        transcript.append(stamped).append('\n')
        val cur = _state.value.log
        val next = if (cur.size >= MAX_UI_LINES) cur.drop(1) + stamped else cur + stamped
        _state.value = _state.value.copy(log = next)
    }

    /** Persist the full transcript under filesDir/obd-logs; returns path. */
    private fun writeTranscript(): String? = runCatching {
        val dir = File(appContext.filesDir, "obd-logs").apply { mkdirs() }
        val file = File(dir, "obd-${FILE_TS.format(Date(System.currentTimeMillis()))}.txt")
        file.writeText(transcript.toString())
        file.absolutePath
    }.getOrNull()

    private companion object {
        const val MAX_UI_LINES = 400
        val TS = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        val FILE_TS = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
    }
}
