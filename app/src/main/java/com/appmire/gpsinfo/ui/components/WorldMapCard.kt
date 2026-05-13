package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.model.SunInfo
import com.appmire.gpsinfo.ui.theme.MapLand
import com.appmire.gpsinfo.ui.theme.MapLandLight
import com.appmire.gpsinfo.ui.theme.MapOcean
import com.appmire.gpsinfo.ui.theme.MapOceanLight
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun WorldMapCard(
    latDeg: Double?,
    lonDeg: Double?,
    sun: SunInfo?
) {
    SectionCard(title = stringResource(R.string.section_world)) {
        WorldMap(latDeg = latDeg, lonDeg = lonDeg, sun = sun)
    }
}

@Composable
private fun WorldMap(latDeg: Double?, lonDeg: Double?, sun: SunInfo?) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ocean = if (isDark) MapOcean else MapOceanLight
    val land = if (isDark) MapLand else MapLandLight
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val nightOverlay = Color(0x80000814)
    val nightStroke = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val frameColor = MaterialTheme.colorScheme.outline
    val sunColor = Color(0xFFFFD54F)
    val youColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val context = LocalContext.current
    val coastlines = remember { WorldCoastlines.load(context) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f)
    ) {
        // Resolve the px size once. The Path is then memoized against
        // that size — previously this Path of 5,109 vertices was rebuilt
        // every single draw frame (50 Hz during sensor activity).
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val landPath = remember(coastlines, wPx, hPx) {
            Path().apply {
                coastlines.forEach { ring ->
                    val n = ring.size
                    var i = 0
                    while (i < n) {
                        val lat = ring[i]; val lon = ring[i + 1]
                        val x = wPx * (lon + 180f) / 360f
                        val y = hPx * (1f - (lat + 90f) / 180f)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i += 2
                    }
                    close()
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // ocean
            drawRect(ocean, topLeft = Offset.Zero, size = Size(w, h))

            // grid: equator + prime meridian + 30° lines
            for (gLat in listOf(-60, -30, 30, 60)) {
                val y = h * (1f - (gLat + 90f) / 180f)
                drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
            }
            for (gLon in listOf(-120, -60, 60, 120)) {
                val x = w * (gLon + 180f) / 360f
                drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1f)
            }
            drawLine(gridColor.copy(alpha = 0.7f), Offset(0f, h / 2f), Offset(w, h / 2f), 1.5f)
            drawLine(gridColor.copy(alpha = 0.7f), Offset(w / 2f, 0f), Offset(w / 2f, h), 1.5f)

            // continents — Natural Earth 1:110m land, public domain
            drawPath(landPath, color = land)

            // night-side terminator (great circle 90° from subsolar)
            if (sun != null) {
                val nightPath = nightPolygon(sun, w, h)
                drawPath(nightPath, color = nightOverlay)
                drawPath(
                    nightPath,
                    color = nightStroke,
                    style = Stroke(
                        width = with(density) { 1.dp.toPx() },
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                )

                // sun glyph at subsolar point
                val sunX = w * (sun.subsolarLonDeg + 180.0).toFloat() / 360f
                val sunY = h * (1f - ((sun.subsolarLatDeg + 90.0).toFloat() / 180f))
                drawCircle(
                    color = sunColor,
                    radius = with(density) { 6.dp.toPx() },
                    center = Offset(sunX, sunY)
                )
                drawCircle(
                    color = sunColor.copy(alpha = 0.35f),
                    radius = with(density) { 14.dp.toPx() },
                    center = Offset(sunX, sunY)
                )
            }

            // user position pin
            if (latDeg != null && lonDeg != null) {
                val px = w * (lonDeg + 180.0).toFloat() / 360f
                val py = h * (1f - ((latDeg + 90.0).toFloat() / 180f))
                drawCircle(youColor, radius = with(density) { 5.dp.toPx() }, center = Offset(px, py))
                drawCircle(
                    youColor, radius = with(density) { 10.dp.toPx() },
                    center = Offset(px, py), style = Stroke(width = with(density) { 1.5.dp.toPx() })
                )
            }

            // frame
            drawRect(
                frameColor,
                topLeft = Offset.Zero, size = Size(w, h),
                style = Stroke(width = with(density) { 1.dp.toPx() })
            )
        }
    }
}

// Build a filled polygon covering all points where solar zenith > 90° (i.e., night).
// We sample longitude across the visible map and find the latitude where the zenith
// equals 90° for each lon; the polygon below that line (or above, depending on season)
// is the night side. Falls back to a full-night rect if it's polar night everywhere.
private fun nightPolygon(sun: SunInfo, mapW: Float, mapH: Float): Path {
    val path = Path()
    val declRad = (sun.subsolarLatDeg * PI / 180.0)
    val sumLat = mutableListOf<Pair<Float, Float>>() // (x, y) — twilight curve

    val samples = 360
    for (i in 0..samples) {
        val lon = -180.0 + i * (360.0 / samples)
        val lonRel = lon - sun.subsolarLonDeg
        // Lat where zenith = 90°: cos(lat)·cos(decl)·cos(H) + sin(lat)·sin(decl) = 0
        // → tan(lat) = -cos(decl)·cos(H) / sin(decl)
        val cosH = cos(lonRel * PI / 180.0)
        val latRad = if (kotlin.math.abs(sin(declRad)) < 1e-6) {
            // sun on equator → terminator runs along lon ± 90°; skip these (vertical)
            Double.NaN
        } else {
            atan(-cos(declRad) * cosH / sin(declRad))
        }
        val latDeg = if (latRad.isNaN()) Double.NaN else latRad * 180.0 / PI
        if (!latDeg.isNaN()) {
            val x = mapW * ((lon + 180.0).toFloat() / 360f)
            val y = mapH * (1f - (latDeg.toFloat() + 90f) / 180f)
            sumLat += x to y
        }
    }

    if (sumLat.isEmpty()) return path

    // Determine which side is night: pole opposite of the sun's hemisphere
    val nightAtSouthPole = sun.subsolarLatDeg > 0.0

    path.moveTo(sumLat.first().first, sumLat.first().second)
    sumLat.drop(1).forEach { (x, y) -> path.lineTo(x, y) }
    if (nightAtSouthPole) {
        path.lineTo(mapW, mapH)
        path.lineTo(0f, mapH)
    } else {
        path.lineTo(mapW, 0f)
        path.lineTo(0f, 0f)
    }
    path.close()
    return path
}

