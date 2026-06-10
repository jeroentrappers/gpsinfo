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
    val ambientTempC: Double? get() = values[ObdRole.AMBIENT_TEMP]
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

                // Tier the mapped roles: FAST polled every cycle (the
                // power needle); the rest trickle round-robin so they
                // refresh on their own slow cadence without stalling power.
                val fast = mapping.roles.entries.filter { it.key.tier == PollTier.FAST }
                val slow = mapping.roles.entries.filter { it.key.tier != PollTier.FAST }
                val slowStd = slow.filter { isStdPid(it.value) }
                val slowUds = slow.filter { !isStdPid(it.value) }
                // One round-robin "unit" per slow signal; the standard PIDs
                // collapse into a single multi-PID batch unit.
                val slowUnits: List<suspend (ObdManager) -> Map<ObdRole, Double?>> = buildList {
                    if (slowStd.isNotEmpty()) add { m -> pollBatch(m, slowStd) }
                    slowUds.forEach { e -> add { m -> mapOf(e.key to pollSingle(m, e.value)) } }
                }

                val acc = LinkedHashMap<ObdRole, Double?>()
                var cycle = 0L
                var rr = 0
                while (isActive) {
                    for ((role, request) in fast) acc[role] = pollSingle(mgr, request)
                    if (slowUnits.isNotEmpty() && cycle % SLOW_EVERY == 0L) {
                        slowUnits[rr % slowUnits.size](mgr).forEach { (r, v) -> acc[r] = v }
                        rr++
                    }
                    _state.value = ObdLiveData(connected = conn.isConnected, values = LinkedHashMap(acc))
                    delay(CYCLE_MS)
                    cycle++
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

    /** Poll one mapped request, decoding with whatever profile command
     *  owns it (matched by request string). Header reuse is handled in
     *  ObdManager, so repeated FAST polls cost just the DID. */
    private suspend fun pollSingle(mgr: ObdManager, request: String): Double? {
        val cmd = commandForRequest(request) ?: return null
        return mgr.readPayload(cmd)?.let { cmd.decode(it) }
    }

    /** Poll several standard-PID roles in one (chunked-to-6) batch. */
    private suspend fun pollBatch(
        mgr: ObdManager,
        entries: List<Map.Entry<ObdRole, String>>,
    ): Map<ObdRole, Double?> {
        val merged = HashMap<Int, IntArray>()
        entries.map { pidOf(it.value) }.chunked(6).forEach { merged.putAll(mgr.readMode01Batch(it)) }
        return entries.associate { e ->
            val payload = merged[pidOf(e.value)]
            val cmd = commandForRequest(e.value)
            e.key to (if (payload != null && cmd != null) cmd.decode(payload) else null)
        }
    }

    private fun commandForRequest(request: String): ObdCommand? =
        ObdProfiles.all
            .flatMap { it.commands }
            .firstOrNull { it.command.request == request }
            ?.command

    private fun isStdPid(req: String): Boolean =
        !req.contains(';') && req.startsWith("01") && req.length == 4

    private fun pidOf(req: String): Int = req.substring(2, 4).toInt(16)

    /** ~50 ms pacing on top of the blocking reads → power lands ~5–10 Hz
     *  depending on adapter; a slow unit ticks every [SLOW_EVERY] cycles. */
    private const val CYCLE_MS = 50L
    private const val SLOW_EVERY = 10L
}
