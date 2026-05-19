package com.appmire.gpsinfo.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.appmire.gpsinfo.data.calibration.CalibrationEstimator
import com.appmire.gpsinfo.data.calibration.CalibrationState
import com.appmire.gpsinfo.data.calibration.Vec3
import com.appmire.gpsinfo.data.model.MagneticAccuracy
import com.appmire.gpsinfo.data.model.MagnetometerSample
import com.appmire.gpsinfo.ui.theme.GPSinfoTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Whole-screen preview is in `app/src/debug/.../ScreenPreviews.kt` (it
 * needs the debug-only fake sensor source). This file holds previews
 * for pieces that take pure data — used both to design and to spot
 * theme regressions.
 */

private object CalibrationPreviewFixtures {

    val midCalibration = CalibrationState(
        sampleCount = 240,
        hardIronOffset = Vec3(5.4f, -1.8f, 3.2f),
        sphereRadiusUt = 45.7f,
        rmsErrorUt = 2.1f,
        coveredBins = 74,
        totalBins = CalibrationEstimator.TOTAL_BINS,
        fieldMagnitudeUt = 46.2f,
    )

    val anomalousFieldCalibration = midCalibration.copy(
        fieldMagnitudeUt = 92f,
    )

    val emptyCalibration = CalibrationState(
        sampleCount = 0,
        hardIronOffset = Vec3.ZERO,
        sphereRadiusUt = 0f,
        rmsErrorUt = 0f,
        coveredBins = 0,
        totalBins = CalibrationEstimator.TOTAL_BINS,
        fieldMagnitudeUt = 0f,
    )

    val samples: List<MagnetometerSample> = run {
        val radius = 45f
        val offsetX = 5f
        val n = 80
        (0 until n).map { i ->
            val golden = PI * (3.0 - kotlin.math.sqrt(5.0))
            val y01 = 1.0 - (i.toDouble() / (n - 1).toDouble()) * 2.0
            val r = kotlin.math.sqrt(1.0 - y01 * y01)
            val theta = golden * i
            MagnetometerSample(
                xMicroTesla = offsetX + (radius * r * cos(theta)).toFloat(),
                yMicroTesla = (radius * y01).toFloat(),
                zMicroTesla = (radius * r * sin(theta)).toFloat(),
                timeNanos = i * 1_000_000L,
                accuracy = MagneticAccuracy.HIGH,
            )
        }
    }
}

@Preview(name = "Calibration — mid-progress", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewCalibrationScreenMidProgress() {
    GPSinfoTheme(forceDark = true) {
        CalibrationContent(
            state = CalibrationUiState(
                calibration = CalibrationPreviewFixtures.midCalibration,
                recentSamples = CalibrationPreviewFixtures.samples,
                latestAccuracy = MagneticAccuracy.MEDIUM,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "Calibration — magnetic interference", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewCalibrationScreenInterference() {
    GPSinfoTheme(forceDark = true) {
        CalibrationContent(
            state = CalibrationUiState(
                calibration = CalibrationPreviewFixtures.anomalousFieldCalibration,
                recentSamples = CalibrationPreviewFixtures.samples,
                latestAccuracy = MagneticAccuracy.UNRELIABLE,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}

@Preview(name = "Calibration — empty buffer", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewCalibrationScreenEmpty() {
    GPSinfoTheme(forceDark = false) {
        CalibrationContent(
            state = CalibrationUiState(
                calibration = CalibrationPreviewFixtures.emptyCalibration,
                recentSamples = emptyList(),
                latestAccuracy = MagneticAccuracy.UNKNOWN,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        )
    }
}
