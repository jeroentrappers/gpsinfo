package be.appmire.gpsinfo.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdLoadMonitorTest {

    @Test
    fun `timeout backs off multiplicatively, capped at max`() {
        val m = ObdLoadMonitor(startDelayMs = 50, backoff = 2.0, maxDelayMs = 1_000)
        m.onOutcome(ok = false, busy = false, timedOut = true, latencyMs = 2_000)
        assertEquals(100, m.delayMs())
        repeat(20) { m.onOutcome(false, false, true, 2_000) }
        assertEquals(1_000, m.delayMs()) // capped
        assertTrue(m.consecutiveOverloads >= 20)
    }

    @Test
    fun `busy marker is treated as overload`() {
        val m = ObdLoadMonitor(startDelayMs = 50, backoff = 2.0)
        m.onOutcome(ok = false, busy = true, timedOut = false, latencyMs = 100)
        assertEquals(100, m.delayMs())
    }

    @Test
    fun `near-timeout latency eases off`() {
        val m = ObdLoadMonitor(startDelayMs = 50, backoff = 2.0, latencyCeilingMs = 800)
        m.onOutcome(ok = true, busy = false, timedOut = false, latencyMs = 900)
        assertEquals(100, m.delayMs())
    }

    @Test
    fun `clean reads creep the delay down to the floor`() {
        val m = ObdLoadMonitor(
            startDelayMs = 200, minDelayMs = 20,
            decreaseStepMs = 15, goodStreakToSpeedUp = 4,
        )
        repeat(4) { m.onOutcome(true, false, false, 30) }
        assertEquals(185, m.delayMs()) // one step after 4 good reads
        repeat(1_000) { m.onOutcome(true, false, false, 30) }
        assertEquals(20, m.delayMs()) // floored, never below min
    }

    @Test
    fun `NO DATA is a healthy read, not overload`() {
        // ObdManager reports NO DATA as ok=true, busy=false → should
        // count toward speeding up, never back off.
        val m = ObdLoadMonitor(startDelayMs = 100, decreaseStepMs = 10, goodStreakToSpeedUp = 2)
        repeat(2) { m.onOutcome(ok = true, busy = false, timedOut = false, latencyMs = 30) }
        assertEquals(90, m.delayMs())
        assertEquals(0, m.consecutiveOverloads)
    }

    @Test
    fun `recovers - a good streak after overload resets and speeds back up`() {
        val m = ObdLoadMonitor(
            startDelayMs = 50, backoff = 2.0, minDelayMs = 20,
            decreaseStepMs = 15, goodStreakToSpeedUp = 3,
        )
        m.onOutcome(false, false, true, 2_000) // → 100
        assertEquals(100, m.delayMs())
        repeat(3) { m.onOutcome(true, false, false, 40) } // → 85
        assertEquals(85, m.delayMs())
        assertEquals(0, m.consecutiveOverloads)
    }
}
