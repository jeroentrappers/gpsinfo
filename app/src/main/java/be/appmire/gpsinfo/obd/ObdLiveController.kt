package be.appmire.gpsinfo.obd

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Latest live OBD values by role; null until/unless the live feed has
 *  read them. [connected] reflects the adapter link. */
data class ObdLiveData(
    val connected: Boolean = false,
    val values: Map<ObdRole, Double?> = emptyMap(),
) {
    val powerKw: Double? get() = values[ObdRole.POWER_KW]
    val socPercent: Double? get() = values[ObdRole.BATTERY_SOC]
    val rangeKm: Double? get() = values[ObdRole.RANGE_KM]
}

/**
 * Process-wide live OBD feed — the analogue of NavigationController for
 * the car's energy readouts. Given a saved adapter + [ObdMapping], it
 * connects, inits the ELM, and polls the mapped role requests on a loop,
 * publishing [state] for the car dashboard to consume.
 *
 * Best-effort and self-healing: connection failures back off and retry;
 * [stop] tears down. Auto-start only happens when the OBD Lab has saved
 * an active adapter ([ObdMappingRepository]), so launch never touches
 * Bluetooth uninvited.
 */
object ObdLiveController {

    private val _state = MutableStateFlow(ObdLiveData())
    val state: StateFlow<ObdLiveData> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var running = false

    /** Start the feed for the repository's active adapter+mapping, if any.
     *  No-op when nothing is configured or already running. */
    fun startIfConfigured(context: Context) {
        if (running) return
        val appContext = context.applicationContext
        val repo = ObdMappingRepository(appContext)
        val store = repo.load()
        val address = store.activeAddress ?: return
        val mapping = repo.activeMapping() ?: return
        start(appContext, address, mapping)
    }

    fun start(context: Context, address: String, mapping: ObdMapping) {
        if (running) stop()
        running = true
        val appContext = context.applicationContext
        job = scope.launch {
            val conn = ObdConnection(appContext)
            try {
                conn.connect(address)
                val mgr = ObdManager(conn)
                mgr.runInit()
                _state.value = ObdLiveData(connected = true, values = emptyMap())
                val requests = mapping.roles.entries.toList()
                while (isActive) {
                    val out = LinkedHashMap<ObdRole, Double?>()
                    for ((role, request) in requests) {
                        out[role] = pollRole(mgr, request)
                    }
                    _state.value = ObdLiveData(connected = conn.isConnected, values = out)
                    delay(POLL_INTERVAL_MS)
                }
            } catch (_: Exception) {
                // Surface as disconnected; a future start() retries.
                _state.value = ObdLiveData(connected = false)
            } finally {
                conn.close()
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        _state.value = ObdLiveData(connected = false)
    }

    /** Poll one mapped request and decode it with whatever command in any
     *  profile owns it (matching by request string). */
    private suspend fun pollRole(mgr: ObdManager, request: String): Double? {
        val cmd = commandForRequest(request) ?: return null
        return mgr.poll(cmd)
    }

    private fun commandForRequest(request: String): ObdCommand? =
        ObdProfiles.all
            .flatMap { it.commands }
            .firstOrNull { it.command.request == request }
            ?.command

    private const val POLL_INTERVAL_MS = 1_000L
}
