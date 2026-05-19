package com.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.appmire.gpsinfo.data.model.Trail
import com.appmire.gpsinfo.data.sun.SunPositionCalculator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small (72dp) compass-rose overlay showing where the sun rose and set
 * on the day the trail was recorded, plus the arc through its zenith.
 * Anchored to the trail's start position + start time, so it shows what
 * the user actually walked in.
 *
 * Why not a "sun-position right now" indicator: that requires re-drawing
 * on the clock tick and changes constantly even when nothing else does.
 * The recorded-day arc is a static artefact of the trail itself, which
 * is what the user is examining on this screen.
 */
@Composable
fun TrailSunCompass(
    trail: Trail,
    modifier: Modifier = Modifier,
) {
    val first = trail.points.firstOrNull() ?: return
    val sun = remember(first.timeMillis, first.latDeg, first.lonDeg) {
        SunPositionCalculator.compute(first.timeMillis, first.latDeg, first.lonDeg)
    }
    // Compute sunrise/sunset azimuths by sampling the calculator at
    // those moments — saves us writing yet another solar-geometry pass.
    val sunriseAz = remember(sun) {
        sun.sunriseEpochMillis?.let {
            SunPositionCalculator.compute(it, first.latDeg, first.lonDeg).sunAzimuthDeg
        }
    }
    val sunsetAz = remember(sun) {
        sun.sunsetEpochMillis?.let {
            SunPositionCalculator.compute(it, first.latDeg, first.lonDeg).sunAzimuthDeg
        }
    }
    val noonAz = remember(sun) {
        sun.solarNoonEpochMillis?.let {
            SunPositionCalculator.compute(it, first.latDeg, first.lonDeg).sunAzimuthDeg
        }
    }
    val northColor = MaterialTheme.colorScheme.onSurface
    val arcColor = MaterialTheme.colorScheme.outline
    val sunColor = Color(0xFFFFD54F)

    Surface(
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Canvas(modifier = Modifier.size(72.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension / 2f * 0.82f

            // North tick + label position
            drawLine(
                color = northColor,
                start = Offset(cx, cy - r * 1.05f),
                end = Offset(cx, cy - r * 0.85f),
                strokeWidth = 2f,
            )
            // Cardinal cross at low alpha
            drawCircle(
                color = arcColor.copy(alpha = 0.4f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1f),
            )

            // Sun arc from sunrise azimuth → noon → sunset azimuth.
            // We approximate as a circular arc on the compass face —
            // it's a navigator's-eye view, not a sky projection.
            if (sunriseAz != null && sunsetAz != null) {
                val path = Path()
                path.moveTo(cx + r * cosA(sunriseAz), cy + r * sinA(sunriseAz))
                val steps = 24
                // Sweep counterclockwise on a compass face: 0° is N, 90° E.
                // Interpolate the shorter way around — sweep through south
                // (where the sun crosses) by going via noonAz when it's
                // available, otherwise via the mean of rise/set.
                val mid = noonAz ?: ((sunriseAz + sunsetAz) / 2.0)
                for (i in 1..steps) {
                    val t = i.toDouble() / steps
                    val az = lerpAz(sunriseAz, mid, sunsetAz, t)
                    path.lineTo(cx + r * cosA(az), cy + r * sinA(az))
                }
                drawPath(path, color = sunColor.copy(alpha = 0.85f), style = Stroke(width = 2f))

                // Sunrise and sunset glyphs
                drawCircle(
                    color = sunColor,
                    radius = r * 0.10f,
                    center = Offset(cx + r * cosA(sunriseAz), cy + r * sinA(sunriseAz)),
                )
                drawCircle(
                    color = sunColor,
                    radius = r * 0.10f,
                    center = Offset(cx + r * cosA(sunsetAz), cy + r * sinA(sunsetAz)),
                )
            }
        }
    }
}

/** Compass-bearing → x-component: 0° = north = up, 90° = east = right. */
private fun cosA(bearingDeg: Double): Float =
    sin(bearingDeg * PI / 180.0).toFloat()

/** Compass-bearing → y-component: 0° = up (negative y in screen space). */
private fun sinA(bearingDeg: Double): Float =
    -cos(bearingDeg * PI / 180.0).toFloat()

/** Quadratic interpolation through three azimuths so the arc passes
 *  through the noon point exactly (otherwise the arc would short-cut
 *  through the wrong half of the compass for trails far from the equator). */
private fun lerpAz(a: Double, mid: Double, b: Double, t: Double): Double {
    val ab = (1 - t) * (1 - t) * a + 2 * (1 - t) * t * mid + t * t * b
    return ab
}
