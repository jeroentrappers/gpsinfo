package be.appmire.gpsinfo.obd

/**
 * Parses raw ELM327 reply text into payload bytes.
 *
 * ELM327 replies look like (spaces present unless ATS0):
 *   010C\r41 0C 1A F8\r\r>
 *
 * For mode 01 the reply starts with 0x41 (mode + 0x40) then the PID;
 * for mode 09 with 0x49; for UDS mode 22 with 0x62 then the 2-byte DID.
 * With CAN auto-formatting (ATCAF1) the dongle drops PCI bytes and
 * concatenates multi-frame ISO-TP payloads for us.
 *
 * NOTE: ported from id.dash and treated as *unverified* — covered by
 * ObdResponseTest, and the smart probe logs every raw reply so real-car
 * quirks surface in the session log rather than silently mis-decoding.
 */
object ObdResponse {

    private val HEX = Regex("[0-9A-Fa-f]+")

    private val ERROR_LINES = setOf(
        "NO DATA", "?", "UNABLE TO CONNECT", "STOPPED",
        "BUS INIT: ERROR", "CAN ERROR", "ERROR", "SEARCHING...",
    )

    /**
     * Extracts the data payload from a raw ELM327 reply.
     *
     * @param raw              full text including command echo + prompt
     * @param mode             requested mode byte, e.g. 0x01, 0x09, 0x22
     * @param expectedPidBytes PID/DID bytes after the mode echo (1 for
     *                         mode 01/05/09, 2 for mode 22)
     */
    fun extractPayload(raw: String, mode: Int, expectedPidBytes: Int = 1): IntArray? {
        val joined = joinedHex(raw) ?: return null
        val responseByteHex = "%02X".format((mode + 0x40) and 0xFF)

        // The positive-response byte (mode+0x40) marks where the payload
        // header begins; everything before it is the echoed command.
        val idx = joined.indexOf(responseByteHex)
        if (idx < 0) return null

        val payloadStart = idx + 2 + (expectedPidBytes * 2)
        if (payloadStart > joined.length) return null
        val payloadHex = joined.substring(payloadStart)
        if (payloadHex.isEmpty() || payloadHex.length % 2 != 0) return null

        return IntArray(payloadHex.length / 2) { i ->
            payloadHex.substring(i * 2, i * 2 + 2).toInt(16)
        }
    }

    /** Clean an ELM reply to one uppercase hex string (echo/space/prompt
     *  stripped), or null on an error line / empty reply.
     *
     *  Handles multi-frame ISO-TP output: with headers off + CAN
     *  auto-format on, a long response prints a length line then
     *  sequence-numbered frames `0:..`, `1:..`, `2:..`. We strip the
     *  `N:` prefixes, concatenate the frames in order, and drop the
     *  standalone length line. Single-frame replies are unaffected. */
    private fun joinedHex(raw: String): String? {
        val lines = raw.replace(">", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.uppercase() in ERROR_LINES }) return null
        val framed = lines.filter { it.length > 2 && it[1] == ':' && it[0].isHexDigit() }
        val parts = if (framed.isNotEmpty()) {
            framed.map { it.substring(2).replace(" ", "") }
        } else {
            lines.map { it.replace(" ", "") }.filter { HEX.matches(it) }
        }
        return parts.joinToString("").uppercase().ifEmpty { null }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    /**
     * Split a multi-PID mode-01 reply into per-PID payloads. A request
     * like "010C0D46" returns "41 0C 1A F8 0D 32 46 50" — one 0x41, then
     * (PID, data…) repeated. The response carries no lengths, so [lengthFor]
     * supplies each PID's data-byte count; parsing stops at the first PID
     * it doesn't know (can't find the next boundary).
     */
    fun splitMode01(raw: String, lengthFor: (Int) -> Int?): Map<Int, IntArray> {
        val joined = joinedHex(raw) ?: return emptyMap()
        val idx = joined.indexOf("41")
        if (idx < 0) return emptyMap()
        val out = LinkedHashMap<Int, IntArray>()
        var pos = idx + 2
        while (pos + 2 <= joined.length) {
            val pid = joined.substring(pos, pos + 2).toInt(16)
            pos += 2
            val len = lengthFor(pid) ?: break
            if (pos + len * 2 > joined.length) break
            out[pid] = IntArray(len) { joined.substring(pos + it * 2, pos + it * 2 + 2).toInt(16) }
            pos += len * 2
        }
        return out
    }

    /** Raw payload string of a mode-09 VIN reply (0902) → the 17-char
     *  VIN, or null. The reply is 49 02 [01] <17 ASCII bytes>; some
     *  ECUs prepend a frame/message count byte (0x01) we skip when the
     *  remaining length is 18. */
    fun parseVin(raw: String): String? {
        val payload = extractPayload(raw, mode = 0x09, expectedPidBytes = 1) ?: return null
        val bytes = when {
            payload.size >= 18 -> payload.drop(payload.size - 17)
            payload.size == 17 -> payload.toList()
            else -> return null
        }
        val vin = bytes.map { it.toChar() }.joinToString("")
            .filter { it.isLetterOrDigit() }
        return vin.takeIf { it.length in 11..17 }
    }
}
