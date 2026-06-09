package be.appmire.gpsinfo.obd

/** One PID the probe examined on the connected vehicle. */
data class DetectedSensor(
    val pid: Int,
    val request: String,
    val label: String,
    val unit: String,
    /** Decoded value, when we recognise the PID and it replied. */
    val value: Double?,
    /** Raw payload hex (always logged, even for PIDs we don't model). */
    val rawHex: String?,
    /** True when [BY_PID] knows how to decode this PID. */
    val known: Boolean,
    /** The ECU advertised it AND it returned data on the probe poll. */
    val live: Boolean,
)

/** Result of one probe pass — what this adapter + vehicle can give us. */
data class ProbeReport(
    val adapterId: String?,
    val protocol: String?,
    val vin: String?,
    val supportedPids: List<Int>,
    val sensors: List<DetectedSensor>,
)

/**
 * The "smart probe": after ELM init it asks the ECU which mode-01 PIDs
 * it supports (the 0100/0120/… bitmask chain), reads the adapter id,
 * protocol and VIN, then polls every supported PID once — decoding the
 * ones we recognise and logging the raw bytes of the ones we don't.
 *
 * Read-only and side-effect-free on the vehicle (mode 01/09 are pure
 * reads). Everything goes through [log] so a session against a real car
 * yields a full transcript to build per-make profiles from.
 */
class SmartProbe(
    private val obd: ObdManager,
    private val log: (String) -> Unit = {},
) {

    suspend fun probe(): ProbeReport {
        log("— init —")
        obd.runInit()

        val adapterId = obd.rawReply("ATI")?.cleanLine()
        val protocol = obd.rawReply("ATDP")?.cleanLine()
        log("adapter: ${adapterId ?: "?"}  protocol: ${protocol ?: "?"}")

        log("— supported PIDs —")
        val supported = discoverSupportedPids()
        log("supported mode-01 PIDs: ${supported.joinToString(" ") { "%02X".format(it) }}")

        log("— VIN —")
        val vin = obd.rawReply(StandardPids.VIN_REQUEST)?.let { ObdResponse.parseVin(it) }
        log("VIN: ${vin ?: "unavailable"}")

        log("— polling ${supported.size} PIDs —")
        val sensors = supported.map { pid -> probePid(pid) }

        val live = sensors.count { it.live }
        log("— done: $live/${sensors.size} PIDs returned data —")
        return ProbeReport(adapterId, protocol, vin, supported, sensors)
    }

    /** Walk the 0100/0120/… supported-PID bitmask chain. */
    private suspend fun discoverSupportedPids(): List<Int> {
        val all = ArrayList<Int>()
        for (base in StandardPids.SUPPORTED_RANGES) {
            val reply = obd.rawReply(StandardPids.supportedRequest(base)) ?: break
            val payload = ObdResponse.extractPayload(reply, mode = 0x01, expectedPidBytes = 1)
            if (payload == null) {
                if (base == 0x00) log("✗ no supported-PID reply for ${StandardPids.supportedRequest(base)}")
                break
            }
            val ranged = StandardPids.parseSupported(payload, base)
            all.addAll(ranged)
            if (!StandardPids.hasNextRange(ranged, base)) break
        }
        // Drop the "next range available" marker PIDs (0x20/0x40/…) —
        // they're protocol bookkeeping, not real sensors.
        return all.filter { it !in StandardPids.SUPPORTED_RANGES.map { r -> r + 0x20 } }.sorted()
    }

    private suspend fun probePid(pid: Int): DetectedSensor {
        val known = StandardPids.BY_PID[pid]
        val request = StandardPids.pidRequest(pid)
        val reply = obd.rawReply(request)
        val payload = reply?.let { ObdResponse.extractPayload(it, mode = 0x01, expectedPidBytes = 1) }
        val rawHex = payload?.joinToString(" ") { "%02X".format(it) }
        val value = if (known != null && payload != null) known.decode(payload) else null
        return DetectedSensor(
            pid = pid,
            request = request,
            label = known?.label ?: "PID 0x%02X".format(pid),
            unit = known?.unit ?: "",
            value = value,
            rawHex = rawHex,
            known = known != null,
            live = payload != null,
        )
    }

    private fun String.cleanLine(): String =
        replace(">", "").replace("\r", " ").trim()
}
