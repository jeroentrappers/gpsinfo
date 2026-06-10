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
    /** Measured period of the last poll cycle (ms) — the settled cadence
     *  the adaptive pacer converged to. 0 until the first cycle. */
    val pollIntervalMs: Long = 0,
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
                val monitor = ObdLoadMonitor()
                val mgr = ObdManager(
                    conn,
                    onRead = { ok, busy, timedOut, latencyMs ->
                        monitor.onOutcome(ok, busy, timedOut, latencyMs)
                    },
                )
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
                val slowUnits: List<suspend (ObdManager, MutableMap<String, IntArray?>) -> Map<ObdRole, Double?>> =
                    buildList {
                        if (slowStd.isNotEmpty()) add { m, _ -> pollBatch(m, slowStd) }
                        slowUds.forEach { e -> add { m, c -> mapOf(e.key to pollSingle(m, e.key, e.value, c)) } }
                    }

                // Power is a single DID on some makes, but on MEB it must
                // be computed V×I — derive it when no direct power role is
                // mapped but voltage + current are (both FAST so it tracks).
                val derivePower = ObdRole.POWER_KW !in mapping.roles &&
                    ObdRole.HV_VOLTAGE in mapping.roles && ObdRole.HV_CURRENT in mapping.roles

                val acc = LinkedHashMap<ObdRole, Double?>()
                var cycle = 0L
                var rr = 0
                var lastPublish = System.currentTimeMillis()
                while (isActive) {
                    // Per-cycle response cache: roles that share one request
                    // (e.g. Hyundai voltage+current+temp all from 220101)
                    // trigger a single ELM read, decoded per role.
                    val cache = HashMap<String, IntArray?>()
                    for ((role, request) in fast) acc[role] = pollSingle(mgr, role, request, cache)
                    if (derivePower) {
                        val v = acc[ObdRole.HV_VOLTAGE]
                        val i = acc[ObdRole.HV_CURRENT]
                        // Sign so consumption (discharge, I<0) reads positive
                        // on the energy dial and regen reads negative.
                        if (v != null && i != null) acc[ObdRole.POWER_KW] = -(v * i) / 1000.0
                    }
                    if (slowUnits.isNotEmpty() && cycle % SLOW_EVERY == 0L) {
                        slowUnits[rr % slowUnits.size](mgr, cache).forEach { (r, v) -> acc[r] = v }
                        rr++
                    }
                    val now = System.currentTimeMillis()
                    _state.value = ObdLiveData(
                        connected = conn.isConnected,
                        values = LinkedHashMap(acc),
                        pollIntervalMs = now - lastPublish,
                    )
                    lastPublish = now
                    // Adaptive pacing: back off when the bus/adapter is
                    // overloaded, speed up when it's keeping up.
                    delay(monitor.delayMs())
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

    /** Poll one mapped role, decoding with the command that fills THAT
     *  role for THIS request (so roles sharing a request — same payload,
     *  different offsets — decode correctly). The raw payload is cached
     *  per cycle so a shared request reads the ELM once. Header reuse in
     *  ObdManager keeps repeated FAST polls down to just the DID. */
    private suspend fun pollSingle(
        mgr: ObdManager,
        role: ObdRole,
        request: String,
        cache: MutableMap<String, IntArray?>,
    ): Double? {
        val cmd = commandFor(role, request) ?: return null
        val payload = if (cache.containsKey(request)) {
            cache[request]
        } else {
            mgr.readPayload(cmd).also { cache[request] = it }
        }
        return payload?.let { cmd.decode(it) }
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
            val cmd = commandFor(e.key, e.value)
            e.key to (if (payload != null && cmd != null) cmd.decode(payload) else null)
        }
    }

    /** The command that fills [role] via [request] — disambiguates roles
     *  that share a request (e.g. HV voltage vs current both from 220101). */
    private fun commandFor(role: ObdRole, request: String): ObdCommand? =
        ObdProfiles.all
            .flatMap { it.commands }
            .firstOrNull { it.role == role && it.command.request == request }
            ?.command

    private fun isStdPid(req: String): Boolean =
        !req.contains(';') && req.startsWith("01") && req.length == 4

    private fun pidOf(req: String): Int = req.substring(2, 4).toInt(16)

    /** A slow unit ticks every [SLOW_EVERY] cycles; the cycle delay itself
     *  is adaptive (see [ObdLoadMonitor]). */
    private const val SLOW_EVERY = 10L
}
