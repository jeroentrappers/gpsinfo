package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic trail used by every preview in this file — a 600 m loop in
 * Antwerp with a 24 m elevation hump in the middle, captured at 1 Hz.
 * Long enough that the simplifier dialog can show "kept 12/120" and
 * short enough that the chart series builds inside a single preview
 * frame.
 */
internal object TrailPreviewFixtures {

    val trail: Trail = run {
        val originLat = 51.2200
        val originLon = 4.4000
        val mPerDegLat = 111_000.0
        val mPerDegLon = 111_000.0 * cos(Math.toRadians(originLat))
        val start = 1_716_120_000_000L // 2024-05-19 12:00:00 UTC
        val points = (0 until 120).map { i ->
            // Walk roughly in a loop: 60 m east, 60 m north, 60 m west, 60 m south.
            val phase = (i.toDouble() / 120.0) * 2.0 * Math.PI
            val east = 60.0 * sin(phase)
            val north = 60.0 * (1.0 - cos(phase)) / 2.0
            // Elevation hump peaks at i=60.
            val ele = 5.0 + 24.0 * sin((i.toDouble() / 120.0) * Math.PI)
            // Plausible walking speed around 1.3 m/s with a touch of noise.
            val speed = (1.3f + 0.15f * sin(phase * 3.0).toFloat()).coerceAtLeast(0.3f)
            TrailPoint(
                timeMillis = start + i * 1000L,
                latDeg = originLat + north / mPerDegLat,
                lonDeg = originLon + east / mPerDegLon,
                eleMeters = ele,
                speedMps = speed,
                courseDeg = ((Math.toDegrees(phase) + 360.0) % 360.0).toFloat(),
                hAccuracyM = 4f,
                vAccuracyM = 6f,
                satellitesInFix = 11,
            )
        }
        Trail(id = "preview-trail", name = "Antwerp loop", points = points)
    }

    val midpoint: TrailPoint = trail.points[60]
}

@Preview(name = "TrailStatsCard")
@Composable
private fun PreviewTrailStatsCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TrailStatsCard(
                trail = TrailPreviewFixtures.trail,
                unitSystem = UnitSystem.Metric,
            )
        }
    }
}

@Preview(name = "TrailStatsCard — imperial")
@Composable
private fun PreviewTrailStatsCardImperial() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TrailStatsCard(
                trail = TrailPreviewFixtures.trail,
                unitSystem = UnitSystem.Imperial,
            )
        }
    }
}

@Preview(name = "TrailProfileChart", widthDp = 360, heightDp = 200)
@Composable
private fun PreviewTrailProfileChart() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TrailProfileChart(
                trail = TrailPreviewFixtures.trail,
                speedColor = Color(0xFF40C4FF),
                elevationColor = Color(0xFFFFB74D),
            )
        }
    }
}

@Preview(name = "TrailSunCompass")
@Composable
private fun PreviewTrailSunCompass() {
    GPSinfoTheme(forceDark = true) {
        Box(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
        ) {
            TrailSunCompass(trail = TrailPreviewFixtures.trail)
        }
    }
}

@Preview(name = "TrailScrubberPanel", widthDp = 360, heightDp = 280)
@Composable
private fun PreviewTrailScrubberPanel() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TrailScrubberPanel(
                trail = TrailPreviewFixtures.trail,
                unitSystem = UnitSystem.Metric,
                speedColor = Color(0xFF40C4FF),
                elevationColor = Color(0xFFFFB74D),
                onPointSelected = {},
            )
        }
    }
}

@Preview(name = "TrailPointDetailsSheet")
@Composable
private fun PreviewTrailPointDetailsSheet() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.surface)
            .fillMaxWidth()
        ) {
            TrailPointDetailsSheet(
                point = TrailPreviewFixtures.midpoint,
                unitSystem = UnitSystem.Metric,
            )
        }
    }
}

@Preview(name = "SimplifyTrailDialog")
@Composable
private fun PreviewSimplifyTrailDialog() {
    GPSinfoTheme(forceDark = true) {
        SimplifyTrailDialog(
            originalPoints = TrailPreviewFixtures.trail.points,
            onDismiss = {},
            onConfirm = { _, _ -> },
        )
    }
}
