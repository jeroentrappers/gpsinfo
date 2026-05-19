package com.appmire.gpsinfo.data.gpx

import com.appmire.gpsinfo.data.model.TrailPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.random.Random

class TrailSimplifierTest {

    @Test fun `empty input returns empty`() {
        assertEquals(emptyList<TrailPoint>(), TrailSimplifier.simplify(emptyList(), 5.0))
    }

    @Test fun `single point passes through`() {
        val p = point(0.0, 0.0, 0L)
        assertEquals(listOf(p), TrailSimplifier.simplify(listOf(p), 5.0))
    }

    @Test fun `two points pass through unchanged`() {
        val pts = trail(2)
        assertEquals(pts, TrailSimplifier.simplify(pts, 5.0))
    }

    @Test fun `straight line with small noise collapses to endpoints`() {
        // Fifty points stepping due east 10 m apart, plus ±2 m jitter
        // perpendicular. Everything fits inside ε=5 m so RDP should
        // keep only the first and last.
        val random = Random(42)
        val pts = (0 until 50).map { i ->
            point(latM = (random.nextDouble() - 0.5) * 4.0, lonM = i * 10.0, t = i * 1000L)
        }
        val out = TrailSimplifier.simplify(pts, epsilonMeters = 5.0)
        assertEquals(listOf(pts.first(), pts.last()), out)
    }

    @Test fun `stationary cluster collapses to entry and exit`() {
        // First two points 100 m apart heading east; then 50 noisy
        // points clustered around the second; then move 100 m further.
        // The cluster sits inside ε of the straight line through the
        // surrounding two anchors, so it should fully collapse.
        val random = Random(7)
        val approach = (0 until 2).map { i -> point(0.0, i * 100.0, i * 1000L) }
        val cluster = (0 until 50).map { i ->
            point(
                latM = (random.nextDouble() - 0.5) * 4.0,
                lonM = 100.0 + (random.nextDouble() - 0.5) * 4.0,
                t = 2000L + i * 100L,
            )
        }
        val departure = (1..2).map { i -> point(0.0, 100.0 + i * 100.0, 60_000L + i * 1000L) }
        val pts = approach + cluster + departure

        val out = TrailSimplifier.simplify(pts, epsilonMeters = 5.0)

        // The two pre-cluster anchors + the two post-cluster anchors —
        // everything in the cluster collapses since the line between the
        // anchors at lonM=100 and lonM=200 passes right through it.
        assertTrue("Expected ≤ 4 kept points, got ${out.size}", out.size <= 4)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
    }

    @Test fun `sharp turn point is preserved`() {
        // Walk east 100 m, then sharp 90° turn and walk north 100 m. The
        // corner point is ~70 m off the line between the endpoints —
        // RDP must keep it as an anchor.
        val east = (0..10).map { i -> point(0.0, i * 10.0, i * 1000L) }
        val north = (1..10).map { i -> point(i * 10.0, 100.0, 10_000L + i * 1000L) }
        val pts = east + north

        val out = TrailSimplifier.simplify(pts, epsilonMeters = 5.0)

        // The corner is `east.last()` — the eastmost point of the
        // L-shaped trail, right before the 90° turn. RDP must keep it.
        val corner = east.last()
        assertTrue("Corner point must be retained", corner in out)
    }

    @Test fun `outlier spike is preserved`() {
        // A mostly-straight east-west line with one point that's 50 m off
        // to the north. That spike is the most-distant point from the
        // overall line — RDP keeps it.
        val pts = (0..20).map { i ->
            val isSpike = i == 10
            point(
                latM = if (isSpike) 50.0 else 0.0,
                lonM = i * 10.0,
                t = i * 1000L,
            )
        }
        val out = TrailSimplifier.simplify(pts, epsilonMeters = 5.0)
        assertTrue("Spike point must be retained", pts[10] in out)
    }

    @Test fun `presets give progressively fewer points`() {
        // Realistic noisy walk: 200 points, 5 m steps east, ±1.5 m jitter
        // perpendicular plus a slight wander in elevation.
        val random = Random(1234)
        val pts = (0 until 200).map { i ->
            point(
                latM = (random.nextDouble() - 0.5) * 3.0,
                lonM = i * 5.0,
                eleM = 50.0 + (random.nextDouble() - 0.5) * 1.5,
                t = i * 1000L,
            )
        }
        val light = TrailSimplifier.simplify(pts, TrailSimplifier.Preset.Light).size
        val default = TrailSimplifier.simplify(pts, TrailSimplifier.Preset.Default).size
        val aggressive = TrailSimplifier.simplify(pts, TrailSimplifier.Preset.Aggressive).size

        assertTrue("Light should keep more points than Default ($light vs $default)", light >= default)
        assertTrue("Default should keep more points than Aggressive ($default vs $aggressive)", default >= aggressive)
        assertTrue("Aggressive should keep at least the endpoints", aggressive >= 2)
    }

    @Test fun `elevation difference forces a keep even when 2D shape is flat`() {
        // 2D shape: straight line. 3D shape: a tall bump in the middle
        // (10 m elevation change). The bump is well outside any
        // reasonable ε in elevation — a 3D-aware simplifier must keep it.
        val pts = listOf(
            point(0.0, 0.0, eleM = 0.0, t = 0),
            point(0.0, 10.0, eleM = 0.0, t = 1000),
            point(0.0, 20.0, eleM = 10.0, t = 2000),    // the bump
            point(0.0, 30.0, eleM = 0.0, t = 3000),
            point(0.0, 40.0, eleM = 0.0, t = 4000),
        )
        val out = TrailSimplifier.simplify(pts, epsilonMeters = 2.0)
        assertTrue("Elevation bump must be retained", pts[2] in out)
    }

    // --- helpers ---

    private val originLat = 51.0
    private val originLon = 4.0

    /** Build N random points, just for type fillers in trivial tests. */
    private fun trail(n: Int): List<TrailPoint> = (0 until n).map { point(0.0, it * 1.0, it * 1000L) }

    /** A point [latM] metres north and [lonM] metres east of the test origin. */
    private fun point(latM: Double, lonM: Double, t: Long, eleM: Double? = null) = TrailPoint(
        timeMillis = t,
        latDeg = originLat + latM / 111_000.0,
        lonDeg = originLon + lonM / (111_000.0 * cos(Math.toRadians(originLat))),
        eleMeters = eleM,
    )

    private fun toLon(lonM: Double): Double =
        originLon + lonM / (111_000.0 * cos(Math.toRadians(originLat)))

    private fun approxEq(a: Double, b: Double, eps: Double = 1e-9): Boolean =
        kotlin.math.abs(a - b) < eps
}
