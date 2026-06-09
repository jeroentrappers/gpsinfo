package be.appmire.gpsinfo.obd

/**
 * A single OBD2 read. Ported from the id.dash dashboard's OBD core
 * (same Appmire ownership) — the vehicle-agnostic half; the
 * VW-specific UDS commands are left behind to become a per-make
 * profile layer later.
 *
 * @param key     stable identifier used in state maps / persistence
 * @param label   human-readable label
 * @param unit    unit shown next to the value
 * @param request raw ELM327 command, e.g. "010C" for engine RPM,
 *                including any header overrides (ATSH…) that must
 *                precede it (separated by ';')
 * @param decode  parses the cleaned hex payload bytes → numeric value,
 *                or null when the ECU replies NO DATA / '?'
 */
data class ObdCommand(
    val key: String,
    val label: String,
    val unit: String,
    val request: String,
    val decode: (IntArray) -> Double?,
)
