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
        val cleaned = raw
            .replace(">", "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (cleaned.any { it.uppercase() in ERROR_LINES }) return null

        val responseByteHex = "%02X".format((mode + 0x40) and 0xFF)
        val joined = cleaned
            .map { it.replace(" ", "") }
            .filter { HEX.matches(it) }
            .joinToString("")
            .uppercase()
        if (joined.isEmpty()) return null

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
