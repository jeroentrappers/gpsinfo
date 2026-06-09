package be.appmire.gpsinfo.obd

/**
 * SAE J1979 mode-01 PIDs supported across essentially every OBD2 car.
 * On BEVs some map to motor analogues (010C "RPM" → drive-motor RPM,
 * 0105 "coolant" → inverter loop) or simply return NO DATA — which is
 * exactly why the probe confirms each one against the live ECU rather
 * than assuming.
 *
 * [BY_PID] keys every command by its PID number so the smart probe can
 * decode any PID the ECU's supported-PID bitmask advertises. PIDs the
 * ECU supports but we don't recognise are still polled and logged raw.
 */
object StandardPids {

    /** Mode-01 "supported PIDs" query ranges. 0100 reports 01–20, and
     *  its bit for PID 0x20 says whether 0120 (21–40) exists, and so on. */
    val SUPPORTED_RANGES = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** Mode-09 PID 02 — vehicle identification number. */
    const val VIN_REQUEST = "0902"

    fun supportedRequest(base: Int): String = "01%02X".format(base)

    fun pidRequest(pid: Int): String = "01%02X".format(pid)

    /**
     * Decode a "supported PIDs" bitmask reply for the given [base] range
     * into the list of supported PID numbers. The 4 payload bytes are a
     * 32-bit big-endian mask; bit 31 = first PID in the range.
     */
    fun parseSupported(payload: IntArray, base: Int): List<Int> {
        if (payload.size < 4) return emptyList()
        val pids = ArrayList<Int>()
        var offset = 0
        for (byteIdx in 0 until 4) {
            for (bit in 7 downTo 0) {
                offset++ // 1..32 within this range
                if ((payload[byteIdx] shr bit) and 1 == 1) pids.add(base + offset)
            }
        }
        return pids
    }

    /** True if the range's "next range available" PID (base + 0x20) is
     *  set, meaning the following supported-PID query is worth sending. */
    fun hasNextRange(supported: List<Int>, base: Int): Boolean = (base + 0x20) in supported

    private fun cmd(pid: Int, key: String, label: String, unit: String, decode: (IntArray) -> Double?) =
        pid to ObdCommand(key, label, unit, pidRequest(pid), decode)

    private fun u8(b: IntArray, i: Int): Int? = b.getOrNull(i)
    private fun u16(b: IntArray, i: Int): Int? {
        val hi = b.getOrNull(i) ?: return null
        val lo = b.getOrNull(i + 1) ?: return null
        return hi * 256 + lo
    }

    /** Known mode-01 PIDs keyed by PID number. */
    val BY_PID: Map<Int, ObdCommand> = mapOf(
        cmd(0x04, "engine_load", "Engine load", "%") { b -> u8(b, 0)?.let { it * 100.0 / 255.0 } },
        cmd(0x05, "coolant", "Coolant", "°C") { b -> u8(b, 0)?.let { it - 40.0 } },
        cmd(0x0A, "fuel_pressure", "Fuel pressure", "kPa") { b -> u8(b, 0)?.let { it * 3.0 } },
        cmd(0x0B, "map", "Intake MAP", "kPa") { b -> u8(b, 0)?.toDouble() },
        cmd(0x0C, "rpm", "Engine / motor RPM", "rpm") { b -> u16(b, 0)?.let { it / 4.0 } },
        cmd(0x0D, "speed", "Vehicle speed", "km/h") { b -> u8(b, 0)?.toDouble() },
        cmd(0x0E, "timing", "Timing advance", "°") { b -> u8(b, 0)?.let { it / 2.0 - 64.0 } },
        cmd(0x0F, "intake_temp", "Intake air temp", "°C") { b -> u8(b, 0)?.let { it - 40.0 } },
        cmd(0x10, "maf", "Mass air flow", "g/s") { b -> u16(b, 0)?.let { it / 100.0 } },
        cmd(0x11, "throttle", "Throttle", "%") { b -> u8(b, 0)?.let { it * 100.0 / 255.0 } },
        cmd(0x1F, "run_time", "Run time", "s") { b -> u16(b, 0)?.toDouble() },
        cmd(0x21, "dist_mil", "Distance w/ MIL", "km") { b -> u16(b, 0)?.toDouble() },
        cmd(0x2F, "fuel_level", "Fuel level", "%") { b -> u8(b, 0)?.let { it * 100.0 / 255.0 } },
        cmd(0x33, "baro", "Barometric pressure", "kPa") { b -> u8(b, 0)?.toDouble() },
        cmd(0x42, "v12", "Control-module voltage", "V") { b -> u16(b, 0)?.let { it / 1000.0 } },
        cmd(0x43, "abs_load", "Absolute load", "%") { b -> u16(b, 0)?.let { it * 100.0 / 255.0 } },
        cmd(0x46, "ambient", "Ambient air temp", "°C") { b -> u8(b, 0)?.let { it - 40.0 } },
        cmd(0x5C, "oil_temp", "Engine oil temp", "°C") { b -> u8(b, 0)?.let { it - 40.0 } },
        cmd(0x5E, "fuel_rate", "Engine fuel rate", "L/h") { b -> u16(b, 0)?.let { it / 20.0 } },
    )
}
