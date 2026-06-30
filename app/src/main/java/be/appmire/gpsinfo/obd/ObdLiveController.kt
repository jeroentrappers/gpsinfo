package be.appmire.gpsinfo.obd

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
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
    /** True while the link is down and the controller is actively retrying
     *  (between attempts). Lets the UI show a "reconnecting…" hint. */
    val reconnecting: Boolean = false,
    /** True once reconnection has failed for [RETRY_PROMPT_MS] without a
     *  link: retrying is paused and the UI should ask the user whether to
     *  keep trying ([continueReconnecting]) or give up ([giveUp]). */
    val awaitingDecision: Boolean = false,
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
        if (running) {
            // Already running — but if it's paused after the 5-minute prompt
            // (e.g. on a surface with no decision dialog), re-entry resumes it
            // rather than leaving the feed stuck.
            if (_state.value.awaitingDecision) continueReconnecting()
            return
        }
        val appContext = context.applicationContext
        val repo = ObdMappingRepository(appContext)
        val store = repo.load()
        val address = store.activeAddress ?: return
        val mapping = repo.activeMapping() ?: return
        start(appContext, address, mapping)
    }

    /** Set by [continueReconnecting] to release the post-5-minute pause. */
    @Volatile
    private var resumeRequested = false

    /** The mapping that last actually produced data. Reconnects prefer it, so
     *  if the configured profile stops delivering we fall back to the last one
     *  that worked rather than re-trying a dead profile. */
    @Volatile
    private var lastGoodMapping: ObdMapping? = null
    private var repo: ObdMappingRepository? = null

    fun start(context: Context, address: String, mapping: ObdMapping) {
        if (running) stop()
        running = true
        resumeRequested = false
        lastGoodMapping = null
        val appContext = context.applicationContext
        repo = ObdMappingRepository(appContext)
        job = scope.launch {
            // Continuous reconnection loop. ELM/Bluetooth links are flaky —
            // the adapter browns out, the socket drops, the car sleeps a bus.
            // Rather than die on the first failure, keep retrying with a
            // capped backoff. After RETRY_PROMPT_MS of failing without a
            // link we pause and surface awaitingDecision so the UI can ask
            // the driver whether to keep trying or give up (so we don't
            // hammer Bluetooth forever in a dead car).
            var firstFailureAtMs = 0L
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive && running) {
                // Reconnect with the last profile that actually delivered data;
                // until one does, use the configured mapping.
                val useMapping = lastGoodMapping ?: mapping
                val connectedAtLeastOnce = runSession(appContext, address, useMapping)
                if (!isActive || !running) break

                if (connectedAtLeastOnce) {
                    // A good session ended (drop / bus sleep). Reset the
                    // failure window and backoff, then reconnect promptly.
                    firstFailureAtMs = 0L
                    backoffMs = INITIAL_BACKOFF_MS
                }
                if (firstFailureAtMs == 0L) firstFailureAtMs = nowMs()

                if (nowMs() - firstFailureAtMs >= RETRY_PROMPT_MS) {
                    // Five minutes of failure — pause and ask the user.
                    _state.value = ObdLiveData(awaitingDecision = true)
                    awaitDecision()
                    if (!isActive || !running) break
                    // User chose to keep trying: reset the window and go again.
                    firstFailureAtMs = 0L
                    backoffMs = INITIAL_BACKOFF_MS
                    continue
                }

                _state.value = ObdLiveData(reconnecting = true)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Resume retrying after the 5-minute pause (UI "keep trying"). */
    fun continueReconnecting() {
        resumeRequested = true
    }

    /** Stop retrying for good (UI "give up"). */
    fun giveUp() = stop()

    /** Suspend until the user releases the pause (or the feed is stopped). */
    private suspend fun awaitDecision() {
        resumeRequested = false
        while (running && !resumeRequested) delay(DECISION_POLL_MS)
    }

    /** One connect→init→poll session. Returns true if the link came up at
     *  least once before it ended (so the outer loop can reset its backoff /
     *  failure window). Never throws — failures end the session, not the app. */
    private suspend fun runSession(appContext: Context, address: String, mapping: ObdMapping): Boolean {
        val conn = ObdConnection(appContext)
        var connectedAtLeastOnce = false
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
            connectedAtLeastOnce = true
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
            // No-data watchdog: a link can be up while the car answers nothing
            // useful (asleep bus, wrong profile, adapter wedged). If no mapped
            // role yields a value for NODATA_TIMEOUT_MS, end the session so the
            // outer loop disconnects and reconnects. Keyed on ANY role, so a
            // working profile that just lacks one sensor (e.g. VW_MEB with no
            // ambient temp) is never recycled for that alone.
            var lastDataMs = nowMs()
            while (currentCoroutineContext().isActive && running) {
                // Per-cycle response cache: roles that share one request
                // (e.g. Hyundai voltage+current+temp all from 220101)
                // trigger a single ELM read, decoded per role.
                val cache = HashMap<String, IntArray?>()
                // Track values read THIS cycle (not the retained acc): a car
                // that goes to sleep keeps stale acc values, so freshness, not
                // presence, is what tells us the link is still delivering.
                var fresh = false
                for ((role, request) in fast) {
                    val v = pollSingle(mgr, role, request, cache)
                    acc[role] = v
                    if (v != null) fresh = true
                }
                if (derivePower) {
                    val v = acc[ObdRole.HV_VOLTAGE]
                    val i = acc[ObdRole.HV_CURRENT]
                    // Sign so consumption (discharge, I<0) reads positive
                    // on the energy dial and regen reads negative.
                    if (v != null && i != null) acc[ObdRole.POWER_KW] = -(v * i) / 1000.0
                }
                if (slowUnits.isNotEmpty() && cycle % SLOW_EVERY == 0L) {
                    val read = slowUnits[rr % slowUnits.size](mgr, cache)
                    read.forEach { (r, v) -> acc[r] = v }
                    if (read.values.any { it != null }) fresh = true
                    rr++
                }
                // A dropped socket reads as not-connected — bail so the outer
                // loop reconnects instead of spinning on a dead link.
                if (!conn.isConnected) break
                // Watchdog: fresh data keeps the link; a live-but-silent link
                // (no fresh value for NODATA_TIMEOUT_MS) is recycled.
                if (fresh) {
                    lastDataMs = nowMs()
                    if (lastGoodMapping !== mapping) {
                        lastGoodMapping = mapping
                        runCatching { repo?.saveActive(mapping, address) }
                    }
                } else if (nowMs() - lastDataMs >= NODATA_TIMEOUT_MS) {
                    break
                }
                val now = System.currentTimeMillis()
                _state.value = ObdLiveData(
                    connected = true,
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
            // Link failed / dropped — the outer loop decides whether to retry.
        } finally {
            conn.close()
        }
        return connectedAtLeastOnce
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        resumeRequested = false
        _state.value = ObdLiveData(connected = false)
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

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

    /** Connected but no usable data for this long → recycle the link
     *  (disconnect + reconnect with the last known good profile). */
    private const val NODATA_TIMEOUT_MS = 12_000L

    /** Reconnect backoff: first retry quick, doubling up to the cap. */
    private const val INITIAL_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 20_000L
    /** Keep retrying for this long before pausing to ask the user. */
    private const val RETRY_PROMPT_MS = 5 * 60 * 1000L
    /** How often the paused loop checks for the user's keep-trying signal. */
    private const val DECISION_POLL_MS = 250L
}
