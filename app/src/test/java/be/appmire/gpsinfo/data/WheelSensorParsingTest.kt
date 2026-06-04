package be.appmire.gpsinfo.data

import be.appmire.gpsinfo.data.rally.RallyController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelSensorParsingTest {

    private fun payload(flags: Int, revs: Long, eventTime: Int): ByteArray = byteArrayOf(
        flags.toByte(),
        (revs and 0xFF).toByte(),
        ((revs shr 8) and 0xFF).toByte(),
        ((revs shr 16) and 0xFF).toByte(),
        ((revs shr 24) and 0xFF).toByte(),
        (eventTime and 0xFF).toByte(),
        ((eventTime shr 8) and 0xFF).toByte(),
    )

    // ── parseCscMeasurement ────────────────────────────────────────

    @Test
    fun `parses wheel revolution payload`() {
        val reading = WheelSensorRepository.parseCscMeasurement(
            payload(flags = 0x01, revs = 123_456L, eventTime = 51_200)
        )!!
        assertEquals(123_456L, reading.cumulativeRevs)
        assertEquals(51_200, reading.lastEventTime1024)
    }

    @Test
    fun `uint32 high bit revs decode unsigned`() {
        val reading = WheelSensorRepository.parseCscMeasurement(
            payload(flags = 0x01, revs = 0xFFFF_FFF0L, eventTime = 0)
        )!!
        assertEquals(0xFFFF_FFF0L, reading.cumulativeRevs)
    }

    @Test
    fun `cadence-only payload returns null`() {
        // bit0 clear: no wheel data (crank-only sensor).
        assertNull(WheelSensorRepository.parseCscMeasurement(byteArrayOf(0x02, 1, 0, 2, 0)))
    }

    @Test
    fun `truncated payload returns null`() {
        assertNull(WheelSensorRepository.parseCscMeasurement(byteArrayOf(0x01, 1, 2, 3)))
        assertNull(WheelSensorRepository.parseCscMeasurement(ByteArray(0)))
    }

    @Test
    fun `wheel and crank combined payload still parses wheel fields`() {
        // flags 0x03 = wheel + crank; crank bytes trail the wheel ones.
        val base = payload(flags = 0x03, revs = 42L, eventTime = 1024)
        val withCrank = base + byteArrayOf(0x10, 0x00, 0x00, 0x04)
        val reading = WheelSensorRepository.parseCscMeasurement(withCrank)!!
        assertEquals(42L, reading.cumulativeRevs)
    }

    // ── wheelRevsDelta (wrap + sanity) ─────────────────────────────

    @Test
    fun `normal forward delta`() {
        assertEquals(5L, RallyController.wheelRevsDelta(100L, 105L))
    }

    @Test
    fun `uint32 wraparound is a small forward delta`() {
        assertEquals(6L, RallyController.wheelRevsDelta(0xFFFF_FFFEL, 4L))
    }

    @Test
    fun `counter reset rebases instead of jumping`() {
        // Sensor battery swap mid-stage: counter restarts near zero;
        // the wrap math would yield a huge bogus delta — must be null.
        assertNull(RallyController.wheelRevsDelta(500_000L, 3L))
    }

    @Test
    fun `implausibly large delta rejected`() {
        assertNull(RallyController.wheelRevsDelta(0L, 100_000L))
    }
}
