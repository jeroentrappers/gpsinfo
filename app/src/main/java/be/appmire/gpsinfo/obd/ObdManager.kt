package be.appmire.gpsinfo.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the ELM327 conversation: serialises every command on a single
 * mutex (the dongle is half-duplex and mixes replies if talked over),
 * runs the init sequence, and turns an [ObdCommand] into a decoded
 * [Double]. Ported from id.dash with a [log] sink added so the probe
 * records every TX/RX line.
 */
class ObdManager(
    private val conn: ObdConnection,
    /** Every request and reply is mirrored here for the session log. */
    private val log: (String) -> Unit = {},
    /** Per-read outcome for adaptive pacing: (ok, busy, timedOut,
     *  latencyMs). `busy` = an ELM overload marker in the reply. */
    private val onRead: (Boolean, Boolean, Boolean, Long) -> Unit = { _, _, _, _ -> },
) {

    private val ioLock = Mutex()

    /** The header/filter AT-prefix currently set on the adapter, so we
     *  only re-send ATSH/ATCRA when the target ECU changes — the key to
     *  hammering one DID (e.g. power) at high frequency. */
    private var currentPrefix: List<String>? = null

    /** Set the ECU header/filter prefix, skipping it if unchanged. An
     *  empty prefix restores standard functional OBD addressing. */
    private suspend fun applyPrefix(prefix: List<String>) {
        val target = prefix.ifEmpty { DEFAULT_PREFIX }
        if (target == currentPrefix) return
        for (c in target) raw(c)
        currentPrefix = target
    }

    /** Send an ELM327 command and read the reply up to the ">" prompt. */
    suspend fun raw(command: String, timeoutMs: Long = 2_000): String = ioLock.withLock {
        log("» $command")
        conn.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val t0 = System.currentTimeMillis()
        val reply = try {
            conn.readUntilPrompt(timeoutMs)
        } catch (e: Exception) {
            onRead(false, false, true, System.currentTimeMillis() - t0)
            throw e
        }
        val latency = System.currentTimeMillis() - t0
        val busy = BUSY_MARKERS.any { reply.uppercase().contains(it) }
        onRead(!busy, busy, false, latency)
        log("« ${reply.replace("\r", "⏎").replace(">", "").trim()}")
        reply
    }

    suspend fun runInit() {
        for (cmd in ElmInit.SEQUENCE) {
            val timeout = if (cmd == "ATZ") 5_000L else 2_000L
            raw(cmd, timeout)
            if (cmd == "ATZ") delay(500) // ELM needs a gap after reset
        }
    }

    /**
     * Execute a (possibly composite) [ObdCommand]. A request with
     * semicolons (e.g. "ATSH7E5;ATCRA7ED;22028C") runs each prefix but
     * only parses the final command's reply.
     */
    suspend fun poll(cmd: ObdCommand): Double? = readPayload(cmd)?.let { cmd.decode(it) }

    /** Run a (possibly composite) command and return its decoded payload
     *  bytes, or null on NO DATA / error. The leading ATSH/ATCRA parts
     *  are applied via [applyPrefix] (sent only when the ECU changes), so
     *  repeated polls of the same DID cost just the DID. Liveness =
     *  non-null payload. */
    suspend fun readPayload(cmd: ObdCommand): IntArray? {
        val parts = cmd.request.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return try {
            applyPrefix(parts.dropLast(1))
            val finalRequest = parts.last()
            val reply = raw(finalRequest)
            val mode = finalRequest.substring(0, 2).toInt(16)
            val pidBytes = when (mode) {
                0x22, 0x21, 0x2E -> 2 // UDS RDBI/WDBI use 2-byte DIDs
                else -> 1
            }
            ObdResponse.extractPayload(reply, mode, pidBytes)
        } catch (e: Exception) {
            log("✗ ${cmd.key}: ${e.message}")
            null
        }
    }

    /** Read several standard mode-01 PIDs in one request (CAN allows up
     *  to 6) → payload bytes per PID. Standard functional addressing. */
    suspend fun readMode01Batch(pids: List<Int>): Map<Int, IntArray> {
        if (pids.isEmpty()) return emptyMap()
        return try {
            applyPrefix(emptyList())
            val req = "01" + pids.joinToString("") { "%02X".format(it) }
            val reply = raw(req)
            ObdResponse.splitMode01(reply) { StandardPids.dataLength(it) }
        } catch (e: Exception) {
            log("✗ batch ${pids.joinToString { "%02X".format(it) }}: ${e.message}")
            emptyMap()
        }
    }

    private companion object {
        /** Restore standard functional OBD addressing after a UDS read. */
        val DEFAULT_PREFIX = listOf("ATSH7DF", "ATCRA")

        /** ELM/bus replies that signal the adapter is overwhelmed (vs
         *  NO DATA / '?', which are prompt, non-overload responses). */
        val BUSY_MARKERS = listOf(
            "BUFFER FULL", "BUS BUSY", "RX ERROR", "FB ERROR",
            "DATA ERROR", "CAN ERROR", "UNABLE TO CONNECT",
        )
    }

    /** Raw reply for an arbitrary request, for probing PIDs we don't yet
     *  model — the payload is logged, not decoded. */
    suspend fun rawReply(request: String): String? = try {
        raw(request)
    } catch (e: Exception) {
        log("✗ $request: ${e.message}")
        null
    }
}
