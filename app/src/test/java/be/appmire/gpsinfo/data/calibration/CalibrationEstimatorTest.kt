package be.appmire.gpsinfo.data.calibration

import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.MagnetometerSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CalibrationEstimatorTest {

    @Test fun `empty buffer returns degenerate state without crashing`() {
        val s = CalibrationEstimator.estimate(emptyList())
        assertEquals(0, s.sampleCount)
        assertEquals(Vec3.ZERO, s.hardIronOffset)
        assertEquals(0f, s.sphereRadiusUt, 1e-6f)
        assertEquals(0f, s.coverage, 1e-6f)
    }

    @Test fun `centroid of zero-offset sphere is the origin`() {
        // 64 samples uniformly around a sphere of radius 45 µT, centred
        // at origin → centroid ≈ origin.
        val samples = uniformSphereSamples(radius = 45f, offset = Triple(0f, 0f, 0f), n = 64)
        val s = CalibrationEstimator.estimate(samples)
        assertEquals(0f, s.hardIronOffset.x, 0.5f)
        assertEquals(0f, s.hardIronOffset.y, 0.5f)
        assertEquals(0f, s.hardIronOffset.z, 0.5f)
        assertEquals(45f, s.sphereRadiusUt, 0.5f)
    }

    @Test fun `centroid recovers a known hard-iron offset`() {
        // Earth field magnitude ~45 µT, shifted by a +15 µT hard-iron
        // bias on each axis. Centroid should land near (15, 15, 15).
        val samples = uniformSphereSamples(radius = 45f, offset = Triple(15f, 15f, 15f), n = 64)
        val s = CalibrationEstimator.estimate(samples)
        assertEquals(15f, s.hardIronOffset.x, 0.5f)
        assertEquals(15f, s.hardIronOffset.y, 0.5f)
        assertEquals(15f, s.hardIronOffset.z, 0.5f)
        assertEquals(45f, s.sphereRadiusUt, 0.5f)
    }

    @Test fun `RMS is small for a clean sphere`() {
        val samples = uniformSphereSamples(radius = 50f, offset = Triple(0f, 0f, 0f), n = 64)
        val s = CalibrationEstimator.estimate(samples)
        // Uniform-sphere samples should land in a thin shell; RMS < 1 µT
        // is comfortable headroom.
        assertTrue("rms was ${s.rmsErrorUt}", s.rmsErrorUt < 1f)
        assertTrue("sphericity was ${s.sphericityError}", s.sphericityError < 0.05f)
    }

    @Test fun `coverage grows with orientation diversity`() {
        // 8 samples covering only a small patch around +X axis ≈ 1 bin.
        val patch = (0 until 8).map { i ->
            val a = i * 0.01
            sample(45f * cos(a).toFloat(), 45f * sin(a).toFloat(), 0f)
        }
        val patchCov = CalibrationEstimator.estimate(patch).coveredBins

        // 64 samples uniformly distributed should hit many bins.
        val uniform = uniformSphereSamples(radius = 45f, offset = Triple(0f, 0f, 0f), n = 64)
        val uniformCov = CalibrationEstimator.estimate(uniform).coveredBins

        assertTrue("expected more coverage from uniform than patch — patch=$patchCov uniform=$uniformCov",
            uniformCov > patchCov)
        // Sanity: the uniform set shouldn't hit *every* bin with only 64 samples.
        assertTrue("uniformCov $uniformCov exceeded total bins ${CalibrationEstimator.TOTAL_BINS}",
            uniformCov <= CalibrationEstimator.TOTAL_BINS)
    }

    @Test fun `field magnitude anomaly flips when above earth max`() {
        // ~80 µT magnitude → above the 70 µT cutoff → anomalous.
        val s = CalibrationEstimator.estimate(
            listOf(sample(80f, 0f, 0f), sample(0f, 80f, 0f), sample(0f, 0f, 80f)),
        )
        assertTrue("expected anomalous magnitude (last=${s.fieldMagnitudeUt})",
            s.fieldMagnitudeAnomalous)
    }

    @Test fun `field magnitude not flagged inside earth field band`() {
        val s = CalibrationEstimator.estimate(
            uniformSphereSamples(radius = 45f, offset = Triple(0f, 0f, 0f), n = 12),
        )
        assertFalse("magnitude was ${s.fieldMagnitudeUt}", s.fieldMagnitudeAnomalous)
    }

    @Test fun `orientation bins are in valid range`() {
        // Probe every octant; every bin must be < TOTAL_BINS.
        val directions = listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0),
            doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(-1.0, -1.0, -1.0),
        )
        for (d in directions) {
            val bin = CalibrationEstimator.orientationBin(d[0], d[1], d[2])
            assertTrue("bin $bin out of range", bin in 0 until CalibrationEstimator.TOTAL_BINS)
        }
    }

    // --- helpers ---

    private fun sample(x: Float, y: Float, z: Float) = MagnetometerSample(
        xMicroTesla = x, yMicroTesla = y, zMicroTesla = z,
        timeNanos = 0L, accuracy = MagneticAccuracy.HIGH,
    )

    /** Generate `n` samples on the surface of a sphere via Fibonacci
     *  lattice, offset to a known centre. Deterministic — no Random — so
     *  tests are stable. */
    private fun uniformSphereSamples(
        radius: Float,
        offset: Triple<Float, Float, Float>,
        n: Int,
    ): List<MagnetometerSample> {
        val golden = PI * (3.0 - kotlin.math.sqrt(5.0))
        return (0 until n).map { i ->
            val y01 = 1.0 - (i.toDouble() / (n - 1).toDouble()) * 2.0
            val r = kotlin.math.sqrt(1.0 - y01 * y01)
            val theta = golden * i
            val x = r * cos(theta)
            val z = r * sin(theta)
            sample(
                (offset.first + radius * x).toFloat(),
                (offset.second + radius * y01).toFloat(),
                (offset.third + radius * z).toFloat(),
            )
        }
    }
}
