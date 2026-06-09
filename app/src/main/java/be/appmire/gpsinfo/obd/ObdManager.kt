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
) {

    private val ioLock = Mutex()

    /** Send an ELM327 command and read the reply up to the ">" prompt. */
    suspend fun raw(command: String, timeoutMs: Long = 2_000): String = ioLock.withLock {
        log("» $command")
        conn.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val reply = conn.readUntilPrompt(timeoutMs)
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
     *  bytes, or null on NO DATA / error. Liveness = non-null payload. */
    suspend fun readPayload(cmd: ObdCommand): IntArray? {
        val parts = cmd.request.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return try {
            for (i in 0 until parts.lastIndex) raw(parts[i])
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

    /** Raw reply for an arbitrary request, for probing PIDs we don't yet
     *  model — the payload is logged, not decoded. */
    suspend fun rawReply(request: String): String? = try {
        raw(request)
    } catch (e: Exception) {
        log("✗ $request: ${e.message}")
        null
    }
}
