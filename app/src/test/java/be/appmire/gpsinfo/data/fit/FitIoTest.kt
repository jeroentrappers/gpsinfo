package be.appmire.gpsinfo.data.fit

import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Smoke-tests for [FitIo] — the bits that don't need an Android
 * runtime. We check the wire-level invariants that downstream FIT
 * parsers (Strava, Garmin Connect) actually verify: file size header,
 * `.FIT` magic, and the final CRC.
 */
class FitIoTest {

    @Test fun writeActivity_produces_header_with_dot_fit_magic() {
        val bytes = encode(sampleTrail())
        // Bytes 8-11 must spell ".FIT".
        assertEquals('.'.code.toByte(), bytes[8])
        assertEquals('F'.code.toByte(), bytes[9])
        assertEquals('I'.code.toByte(), bytes[10])
        assertEquals('T'.code.toByte(), bytes[11])
        // Header byte 0 is the header length (14).
        assertEquals(14.toByte(), bytes[0])
        // Protocol version byte (0x20 = 2.0).
        assertEquals(0x20.toByte(), bytes[1])
    }

    @Test fun writeActivity_data_size_field_matches_payload() {
        val bytes = encode(sampleTrail())
        val dataSize = (bytes[4].toInt() and 0xFF) or
            ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 24)
        // File = 14-byte header + dataSize bytes of payload + 2-byte CRC.
        assertEquals(bytes.size, 14 + dataSize + 2)
    }

    @Test fun writeActivity_file_crc_is_self_consistent() {
        val bytes = encode(sampleTrail())
        val expected = (bytes[bytes.size - 2].toInt() and 0xFF) or
            ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        // Re-run the CRC over header + body — should match the stored
        // CRC at the tail.
        var crc = 0
        for (i in 0 until bytes.size - 2) {
            crc = crc16(crc, bytes[i].toInt() and 0xFF)
        }
        assertEquals(expected, crc)
    }

    @Test fun writeActivity_handles_empty_trail_gracefully() {
        val empty = Trail(id = "empty", name = "empty", points = emptyList())
        val bytes = encode(empty)
        assertTrue("File at least header + crc", bytes.size >= 16)
    }

    private fun encode(t: Trail): ByteArray {
        val sink = ByteArrayOutputStream()
        FitIo.writeActivity(t, sink)
        return sink.toByteArray()
    }

    private fun sampleTrail(): Trail {
        val t0 = 1_700_000_000_000L // arbitrary
        val points = (0 until 5).map { i ->
            TrailPoint(
                timeMillis = t0 + i * 1000L,
                latDeg = 51.13 + i * 0.0001,
                lonDeg = 4.37 + i * 0.0001,
                eleMeters = 30.0 + i,
                speedMps = 2.5f,
                heartRateBpm = 110 + i,
                powerWatts = 200 + i * 5,
            )
        }
        return Trail(id = "t", name = "sample", points = points)
    }

    // Copy of the FIT CRC algorithm so the test doesn't need
    // production-internal visibility. Kept in sync with FitIo.
    private val crcTable = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )
    private fun crc16(initial: Int, byte: Int): Int {
        var c = initial
        var tmp = crcTable[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor crcTable[byte and 0xF]
        tmp = crcTable[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor crcTable[(byte shr 4) and 0xF]
        return c and 0xFFFF
    }
}
