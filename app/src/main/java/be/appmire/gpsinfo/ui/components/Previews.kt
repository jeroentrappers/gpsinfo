package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.Constellation
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.SatelliteInfo
import be.appmire.gpsinfo.data.model.SunInfo
import be.appmire.gpsinfo.ui.theme.GPSinfoTheme

/**
 * Single source of truth for canned preview fixtures so every component
 * preview tells the same coherent story (Antwerp, midday, decent fix).
 */
internal object PreviewFixtures {

    val satellites: List<SatelliteInfo> = listOf(
        SatelliteInfo(1,  Constellation.GPS,     az(40),  el(60), 42f, true,  true, true,  1575_420_000f),
        SatelliteInfo(10, Constellation.GPS,     az(120), el(35), 38f, true,  true, true,  1575_420_000f),
        SatelliteInfo(32, Constellation.GPS,     az(250), el(20), 28f, true,  true, false, 1575_420_000f),
        SatelliteInfo(7,  Constellation.GALILEO, az(80),  el(45), 36f, true,  true, true,  1575_420_000f),
        SatelliteInfo(11, Constellation.GLONASS, az(170), el(15), 18f, false, true, true,  1602_000_000f),
        SatelliteInfo(22, Constellation.BEIDOU,  az(310), el(30), 24f, false, false, false, 1561_000_000f),
        SatelliteInfo(5,  Constellation.QZSS,    az(110), el(55), 32f, true,  true, true,  1575_420_000f),
    )

    val gnss = GnssSnapshot(
        location = null, // Compose previews can't construct Location; the cards handle null gracefully.
        fix = FixStatus.THREE_D,
        satellites = satellites,
        firstFixMillis = System.currentTimeMillis() - 12_000L,
        lastUpdateElapsedRealtime = 0L,
    )

    val compass = CompassReading(
        magneticHeadingDeg = 58f,
        continuousMagneticHeadingDeg = 58f,
        trueHeadingDeg = 60.7f,
        pitchDeg = 4f,
        rollDeg = -2f,
        declinationDeg = 2.71f,
        inclinationDeg = 65.4f,
        fieldStrengthNanoTesla = 49_000f,
        accuracy = MagneticAccuracy.HIGH,
    )

    val sun = SunInfo(
        sunAzimuthDeg = 178.0,
        sunElevationDeg = 42.0,
        subsolarLatDeg = 5.5,
        subsolarLonDeg = -10.0,
        sunriseEpochMillis = midday() - 6 * 3600_000L,
        sunsetEpochMillis = midday() + 6 * 3600_000L,
        solarNoonEpochMillis = midday(),
        dayLengthMillis = 12 * 3600_000L,
        isDaytime = true,
    )

    private fun az(deg: Int): Float = deg.toFloat()
    private fun el(deg: Int): Float = deg.toFloat()
    private fun midday(): Long = (System.currentTimeMillis() / 86_400_000L) * 86_400_000L + 12 * 3600_000L
}

@Preview(name = "StatusBar — 3D Fix")
@Composable
private fun PreviewStatusBar() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
        ) {
            StatusBar(
                fix = FixStatus.THREE_D,
                accuracyMeters = 7f,
            )
        }
    }
}

@Preview(name = "StatusBar — No Fix")
@Composable
private fun PreviewStatusBarNoFix() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
        ) {
            StatusBar(
                fix = FixStatus.NO_FIX,
                accuracyMeters = null,
            )
        }
    }
}

@Preview(name = "PositionCard")
@Composable
private fun PreviewPositionCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            PositionCard(
                latDeg = 51.1302028,
                lonDeg = 4.3777386,
                altMeters = 45.0,
                hAccuracyMeters = 4f,
                vAccuracyMeters = 6f,
                format = be.appmire.gpsinfo.util.CoordinateFormat.DMS,
                onToggleFormat = {},
            )
        }
    }
}

@Preview(name = "SpeedCard")
@Composable
private fun PreviewSpeedCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SpeedCard(
                speedKmh = 87f,
                headingDegMagnetic = 58f,
                altMeters = 45.0,
            )
        }
    }
}

@Preview(name = "CompassCard")
@Composable
private fun PreviewCompassCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            CompassCard(reading = PreviewFixtures.compass)
        }
    }
}

@Preview(name = "SkyViewCard")
@Composable
private fun PreviewSkyViewCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SkyViewCard(snapshot = PreviewFixtures.gnss)
        }
    }
}

@Preview(name = "WorldMapCard")
@Composable
private fun PreviewWorldMapCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            // Logo loader needs a real context; the @Preview AssetManager
            // can't read assets/world_110m.bin → an empty land path is
            // rendered. The grid + terminator still preview fine.
            WorldMapCard(
                latDeg = 51.13,
                lonDeg = 4.38,
                sun = PreviewFixtures.sun,
            )
        }
    }
}

@Preview(name = "TimeSunCard")
@Composable
private fun PreviewTimeSunCard() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TimeSunCard(
                nowMillis = System.currentTimeMillis(),
                sun = PreviewFixtures.sun,
            )
        }
    }
}

@Preview(name = "SnrBarChart")
@Composable
private fun PreviewSnrBarChart() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SnrBarChart(satellites = PreviewFixtures.satellites)
        }
    }
}

@Preview(name = "RetroDial — 75 km/h")
@Composable
private fun PreviewRetroDial() {
    GPSinfoTheme(forceDark = true) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .fillMaxWidth()
        ) {
            RetroDial(
                valueFraction = 75f / 180f,
                minValue = 0f,
                maxValue = 180f,
                tickStep = 10f,
                labelStep = 20f,
                label = "km/h",
                accentTickValues = listOf(30f, 50f, 70f, 90f, 120f),
            )
        }
    }
}

// ---------- Light-theme variants -------------------------------------
//
// Each card already has a dark preview above. The light variants below
// catch contrast/colour-token regressions that only surface when the
// system theme is light — common when designers tweak surface tonals.

@Preview(name = "StatusBar — light")
@Composable
private fun PreviewStatusBarLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
        ) {
            StatusBar(
                fix = FixStatus.THREE_D,
                accuracyMeters = 7f,
            )
        }
    }
}

@Preview(name = "PositionCard — light")
@Composable
private fun PreviewPositionCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            PositionCard(
                latDeg = 51.1302028,
                lonDeg = 4.3777386,
                altMeters = 45.0,
                hAccuracyMeters = 4f,
                vAccuracyMeters = 6f,
                format = be.appmire.gpsinfo.util.CoordinateFormat.DMS,
                onToggleFormat = {},
            )
        }
    }
}

@Preview(name = "SpeedCard — light")
@Composable
private fun PreviewSpeedCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SpeedCard(
                speedKmh = 87f,
                headingDegMagnetic = 58f,
                altMeters = 45.0,
            )
        }
    }
}

@Preview(name = "CompassCard — light")
@Composable
private fun PreviewCompassCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            CompassCard(reading = PreviewFixtures.compass)
        }
    }
}

@Preview(name = "SkyViewCard — light")
@Composable
private fun PreviewSkyViewCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SkyViewCard(snapshot = PreviewFixtures.gnss)
        }
    }
}

@Preview(name = "WorldMapCard — light")
@Composable
private fun PreviewWorldMapCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            WorldMapCard(
                latDeg = 51.13,
                lonDeg = 4.38,
                sun = PreviewFixtures.sun,
            )
        }
    }
}

@Preview(name = "TimeSunCard — light")
@Composable
private fun PreviewTimeSunCardLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            TimeSunCard(
                nowMillis = System.currentTimeMillis(),
                sun = PreviewFixtures.sun,
            )
        }
    }
}

@Preview(name = "SnrBarChart — light")
@Composable
private fun PreviewSnrBarChartLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .fillMaxWidth()
        ) {
            SnrBarChart(satellites = PreviewFixtures.satellites)
        }
    }
}

@Preview(name = "RetroDial — light")
@Composable
private fun PreviewRetroDialLight() {
    GPSinfoTheme(forceDark = false) {
        Column(modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .fillMaxWidth()
        ) {
            RetroDial(
                valueFraction = 75f / 180f,
                minValue = 0f,
                maxValue = 180f,
                tickStep = 10f,
                labelStep = 20f,
                label = "km/h",
                accentTickValues = listOf(30f, 50f, 70f, 90f, 120f),
            )
        }
    }
}
