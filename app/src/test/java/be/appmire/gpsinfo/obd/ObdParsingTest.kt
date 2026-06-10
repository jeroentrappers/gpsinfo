package be.appmire.gpsinfo.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests for the ELM327 reply decoders ported from id.dash. The
 * port was untested upstream, so these pin the behaviour against known
 * SAE J1979 sample frames (spaces-off, headers-off, as ElmInit sets).
 */
class ObdParsingTest {

    @Test
    fun `extractPayload mode01 rpm`() {
        // 41 0C 1A F8 → payload 1A F8
        val p = ObdResponse.extractPayload("410C1AF8\r\r>", mode = 0x01)!!
        assertEquals(listOf(0x1A, 0xF8), p.toList())
        // (256*0x1A + 0xF8) / 4 = 1726
        assertEquals(1726.0, StandardPids.BY_PID[0x0C]!!.decode(p)!!, 0.001)
    }

    @Test
    fun `extractPayload tolerates spaces and multiline`() {
        val p = ObdResponse.extractPayload("41 0D 32\r\r>", mode = 0x01)!!
        assertEquals(listOf(0x32), p.toList())
        assertEquals(50.0, StandardPids.BY_PID[0x0D]!!.decode(p)!!, 0.001) // 0x32 km/h
    }

    @Test
    fun `coolant offset applied`() {
        val p = ObdResponse.extractPayload("41055A>", mode = 0x01)!!
        assertEquals(50.0, StandardPids.BY_PID[0x05]!!.decode(p)!!, 0.001) // 0x5A-40
    }

    @Test
    fun `control module voltage scales by 1000`() {
        // 41 42 32 1A → (0x321A)/1000 = 12.826 V
        val p = ObdResponse.extractPayload("4142321A>", mode = 0x01)!!
        assertEquals(12.826, StandardPids.BY_PID[0x42]!!.decode(p)!!, 0.001)
    }

    @Test
    fun `NO DATA yields null`() {
        assertNull(ObdResponse.extractPayload("NO DATA\r\r>", mode = 0x01))
    }

    @Test
    fun `mode22 uses two-byte DID`() {
        // 62 02 8C AA BB → DID 028C, payload AA BB
        val p = ObdResponse.extractPayload("62028CAABB>", mode = 0x22, expectedPidBytes = 2)!!
        assertEquals(listOf(0xAA, 0xBB), p.toList())
    }

    @Test
    fun `supported PID bitmask decodes and chains`() {
        // 0100 → 41 00 BE 1F A8 13 : classic "supported PIDs 01-20" mask
        val payload = ObdResponse.extractPayload("4100BE1FA813>", mode = 0x01)!!
        val pids = StandardPids.parseSupported(payload, base = 0x00)
        // 0xBE = 1011 1110 → PIDs 01,03,04,05,06,07 ; not 02 or 08
        assertTrue(0x01 in pids)
        assertTrue(0x03 in pids)
        assertTrue(0x05 in pids)
        assertTrue(0x02 !in pids)
        // Last byte 0x13 = ...0001 0011, bit0 set → PID 0x20 supported → chain.
        assertTrue(StandardPids.hasNextRange(pids, base = 0x00))
    }

    @Test
    fun `supported PID mask without next range stops`() {
        // last byte even (bit0 clear) → PID 0x20 not advertised
        val payload = ObdResponse.extractPayload("4100BE1FA812>", mode = 0x01)!!
        val pids = StandardPids.parseSupported(payload, base = 0x00)
        assertTrue(StandardPids.hasNextRange(pids, base = 0x00).not())
    }

    @Test
    fun `splitMode01 splits a multi-PID reply by known lengths`() {
        // request 010C0D46 → 41 0C 1A F8 | 0D 32 | 46 50
        val m = ObdResponse.splitMode01("410C1AF80D324650>") { StandardPids.dataLength(it) }
        assertEquals(listOf(0x1A, 0xF8), m[0x0C]!!.toList())
        assertEquals(listOf(0x32), m[0x0D]!!.toList())
        assertEquals(listOf(0x50), m[0x46]!!.toList())
        // decode spot-check: 0x46 → 0x50(80) - 40 = 40 °C
        assertEquals(40.0, StandardPids.BY_PID[0x46]!!.decode(m[0x46]!!)!!, 0.001)
    }

    @Test
    fun `splitMode01 stops at an unknown PID boundary`() {
        // 0xAB has no length → parsing can't continue past it
        val m = ObdResponse.splitMode01("410D32AB99") { StandardPids.dataLength(it) }
        assertEquals(listOf(0x32), m[0x0D]!!.toList())
        assertEquals(1, m.size)
    }

    @Test
    fun `splitMode01 returns empty on NO DATA`() {
        assertEquals(0, ObdResponse.splitMode01("NO DATA>") { StandardPids.dataLength(it) }.size)
    }

    @Test
    fun `parseVin extracts 17 chars with leading count byte`() {
        // 49 02 01 + ASCII "1HGCM82633A004352" (17 chars)
        val vinAscii = "1HGCM82633A004352"
        val hex = "490201" + vinAscii.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02X".format(it) }
        assertEquals(vinAscii, ObdResponse.parseVin("$hex>"))
    }
}
