package com.appmire.gpsinfo.data.calibration

import com.appmire.gpsinfo.data.model.MagnetometerSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pure-JVM calibration math. Takes a buffer of raw magnetometer samples
 * and produces:
 *   - a **hard-iron offset estimate** (the centroid of the sample cloud)
 *   - a **sphere radius** (mean distance from offset)
 *   - a **sphere-fit RMS error** (how round / how ellipsoidal the data is)
 *   - a **coverage fraction** (how many of a fixed orientation-bucket
 *     set have been hit at least once)
 *
 * Why this scope: full ellipsoid fitting (hard *and* soft iron) needs
 * a 9-parameter optimisation that's overkill for a phone's UI thread.
 * Hard-iron-only catches the dominant error mode — the steel back of
 * the case, the speaker magnet, the wallet's RFID-block sheet — and
 * stays fast and stable. Soft-iron remains; users with significant
 * soft-iron error will see it as a larger RMS.
 *
 * Coverage uses a coarse azimuth × elevation grid (16 × 8 = 128 bins).
 * Finer grids look better in screenshots but make 100 % impractical on
 * a real device.
 */
object CalibrationEstimator {

    /** Grid resolution for the coverage indicator. Total bins = AZ × EL. */
    const val AZIMUTH_BINS = 16
    const val ELEVATION_BINS = 8
    const val TOTAL_BINS = AZIMUTH_BINS * ELEVATION_BINS

    /** The healthy magnetometer-reading range at Earth's surface, in µT.
     *  Used by the UI to warn when the field magnitude is implausibly
     *  high (nearby magnet) or low (sensor saturated / on the moon). */
    const val EARTH_FIELD_MIN_UT = 22f
    const val EARTH_FIELD_MAX_UT = 70f

    /**
     * Estimate calibration state from a buffer of samples.
     *
     * Returns a degenerate-but-valid [CalibrationState] when the buffer
     * is small (<3 samples) so the UI can render zeros without special
     * casing.
     */
    fun estimate(samples: List<MagnetometerSample>): CalibrationState {
        if (samples.size < 3) {
            return CalibrationState(
                sampleCount = samples.size,
                hardIronOffset = Vec3.ZERO,
                sphereRadiusUt = 0f,
                rmsErrorUt = 0f,
                coveredBins = 0,
                totalBins = TOTAL_BINS,
                fieldMagnitudeUt = samples.lastOrNull()?.magnitude() ?: 0f,
            )
        }

        // Hard-iron offset = centroid. With a perfectly-spherical sample
        // cloud this is the sphere centre; for the typical noisy phone
        // dataset it's a robust enough estimate.
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (s in samples) {
            sx += s.xMicroTesla
            sy += s.yMicroTesla
            sz += s.zMicroTesla
        }
        val n = samples.size.toDouble()
        val offset = Vec3((sx / n).toFloat(), (sy / n).toFloat(), (sz / n).toFloat())

        // Sphere radius = mean distance from the centroid.
        // RMS error = stddev of those distances. A well-calibrated cloud
        // is a thin shell, so the RMS should be << radius.
        var sumR = 0.0
        var sumR2 = 0.0
        val bins = HashSet<Int>(TOTAL_BINS)
        for (s in samples) {
            val dx = (s.xMicroTesla - offset.x).toDouble()
            val dy = (s.yMicroTesla - offset.y).toDouble()
            val dz = (s.zMicroTesla - offset.z).toDouble()
            val r = sqrt(dx * dx + dy * dy + dz * dz)
            sumR += r
            sumR2 += r * r
            bins += orientationBin(dx, dy, dz)
        }
        val meanR = sumR / n
        val varianceR = (sumR2 / n) - (meanR * meanR)
        val rms = sqrt(maxOf(0.0, varianceR))

        return CalibrationState(
            sampleCount = samples.size,
            hardIronOffset = offset,
            sphereRadiusUt = meanR.toFloat(),
            rmsErrorUt = rms.toFloat(),
            coveredBins = bins.size,
            totalBins = TOTAL_BINS,
            fieldMagnitudeUt = samples.last().magnitude(),
        )
    }

    /**
     * Map a 3D direction to a flat bin index in [0, TOTAL_BINS).
     * Azimuth runs [-π, π) → [0, AZIMUTH_BINS); elevation runs
     * [-π/2, π/2] → [0, ELEVATION_BINS). The exact pole bins are
     * clamped, not unique cells.
     *
     * Pure function, exposed `internal` so the test can call it.
     */
    internal fun orientationBin(dx: Double, dy: Double, dz: Double): Int {
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-9) return 0
        val nx = dx / len
        val ny = dy / len
        val nz = dz / len
        val azimuth = atan2(ny, nx) // [-π, π)
        val elevation = kotlin.math.asin(nz.coerceIn(-1.0, 1.0)) // [-π/2, π/2]
        val az01 = ((azimuth + PI) / (2.0 * PI)).coerceIn(0.0, 0.99999)
        val el01 = ((elevation + PI / 2.0) / PI).coerceIn(0.0, 0.99999)
        val azIdx = floor(az01 * AZIMUTH_BINS).toInt().coerceIn(0, AZIMUTH_BINS - 1)
        val elIdx = floor(el01 * ELEVATION_BINS).toInt().coerceIn(0, ELEVATION_BINS - 1)
        return elIdx * AZIMUTH_BINS + azIdx
    }
}

/**
 * A tiny 3D vector. Compose-Immutable so it's stable as a state key.
 */
@androidx.compose.runtime.Immutable
data class Vec3(val x: Float, val y: Float, val z: Float) {
    companion object { val ZERO = Vec3(0f, 0f, 0f) }
}

/**
 * Calibration snapshot — derived purely from a sample buffer + the
 * estimator. UI consumes this; nothing in here mutates over time
 * except by re-running [CalibrationEstimator.estimate] on a new buffer.
 */
@androidx.compose.runtime.Immutable
data class CalibrationState(
    val sampleCount: Int,
    val hardIronOffset: Vec3,
    val sphereRadiusUt: Float,
    val rmsErrorUt: Float,
    val coveredBins: Int,
    val totalBins: Int,
    val fieldMagnitudeUt: Float,
) {
    /** [0, 1] — fraction of orientation buckets that have been sampled. */
    val coverage: Float
        get() = if (totalBins == 0) 0f else coveredBins.toFloat() / totalBins.toFloat()

    /** RMS / radius — closer to 0 means a tighter spherical shell. Stays
     *  bounded even when radius is near zero (avoids division blowups). */
    val sphericityError: Float
        get() = if (sphereRadiusUt < 1f) 0f else (rmsErrorUt / sphereRadiusUt).coerceIn(0f, 1f)

    /** True when the field magnitude is implausibly far from Earth's
     *  surface field — usually means a nearby magnet. UI surfaces a
     *  "move away from metal" hint when this fires. */
    val fieldMagnitudeAnomalous: Boolean
        get() = fieldMagnitudeUt > 0f && (
            fieldMagnitudeUt < CalibrationEstimator.EARTH_FIELD_MIN_UT ||
                fieldMagnitudeUt > CalibrationEstimator.EARTH_FIELD_MAX_UT
            )
}

internal fun MagnetometerSample.magnitude(): Float =
    sqrt(xMicroTesla * xMicroTesla + yMicroTesla * yMicroTesla + zMicroTesla * zMicroTesla)
