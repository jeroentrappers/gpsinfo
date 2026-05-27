package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dual-axis line chart over a [Trail] — speed on the left axis, elevation
 * on the right, both plotted against time. Tap anywhere on the chart to
 * surface the trackpoint sitting at that x.
 *
 * Why Canvas and not a chart library: every chart lib worth its salt
 * adds 200-500 KB to the APK for line graphs we can draw in ~40 lines.
 * The trade-off is no built-in tooltips/legends — we provide our own.
 *
 * The series are precomputed off the main thread on construction and on
 * every trail change. For 30 k-point trails this matters; for shorter
 * ones it's a non-issue but the API stays the same.
 */
@Composable
fun TrailProfileChart(
    trail: Trail,
    speedColor: Color,
    elevationColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    onPointSelected: (TrailPoint) -> Unit = {},
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    // Precompute the normalised series. `series` stays null until the
    // first paint pass finishes — short trails complete in <1 frame, big
    // ones swap in once Default-dispatcher work finishes.
    var series by remember(trail.id, trail.points.size) {
        mutableStateOf<TrailSeries?>(null)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(trail.id, trail.points.size) {
        series = withContext(Dispatchers.Default) { buildSeries(trail) }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(height)) {
            val s = series ?: return@Box
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(s) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            val idx = (frac * (s.points.size - 1)).toInt()
                                .coerceIn(0, s.points.size - 1)
                            onPointSelected(s.points[idx])
                        }
                    }
            ) {
                drawChart(s, speedColor, elevationColor, gridColor)
            }
        }
    }
    // Suppress unused warning — `scope` is held for future use if we
    // ever want to recompute on demand from outside LaunchedEffect.
    @Suppress("UNUSED_EXPRESSION") scope
}

/** Pre-normalised series ready to draw — both axes pre-scaled to [0,1]. */
private class TrailSeries(
    val points: List<TrailPoint>,
    /** Per-point fraction across the time axis [0..1]. */
    val tNorm: FloatArray,
    /** Per-point speed value in m/s, or NaN where missing. */
    val speed: FloatArray,
    val speedMin: Float,
    val speedMax: Float,
    /** Per-point elevation in m, or NaN where missing. */
    val ele: FloatArray,
    val eleMin: Float,
    val eleMax: Float,
    val hasSpeed: Boolean,
    val hasElevation: Boolean,
)

private fun buildSeries(trail: Trail): TrailSeries {
    val n = trail.points.size
    val tNorm = FloatArray(n)
    val speed = FloatArray(n)
    val ele = FloatArray(n)
    if (n == 0) {
        return TrailSeries(
            trail.points, tNorm, speed, 0f, 0f, ele, 0f, 0f, false, false,
        )
    }
    val t0 = trail.points.first().timeMillis
    val tEnd = trail.points.last().timeMillis
    val tRange = (tEnd - t0).coerceAtLeast(1L).toDouble()
    var sMin = Float.POSITIVE_INFINITY
    var sMax = Float.NEGATIVE_INFINITY
    var eMin = Float.POSITIVE_INFINITY
    var eMax = Float.NEGATIVE_INFINITY
    var anySpeed = false
    var anyEle = false
    for (i in 0 until n) {
        val p = trail.points[i]
        tNorm[i] = ((p.timeMillis - t0).toDouble() / tRange).toFloat()
        val sp = p.speedMps
        if (sp != null) {
            speed[i] = sp
            if (sp < sMin) sMin = sp
            if (sp > sMax) sMax = sp
            anySpeed = true
        } else speed[i] = Float.NaN
        val el = p.eleMeters
        if (el != null) {
            ele[i] = el.toFloat()
            if (ele[i] < eMin) eMin = ele[i]
            if (ele[i] > eMax) eMax = ele[i]
            anyEle = true
        } else ele[i] = Float.NaN
    }
    if (!anySpeed) { sMin = 0f; sMax = 1f }
    if (!anyEle) { eMin = 0f; eMax = 1f }
    // Avoid zero-range axes — collapse a flat series to a thin band so
    // the line stays at the band's middle rather than slamming the
    // top/bottom of the canvas.
    if (sMax - sMin < 1e-3f) { sMax = sMin + 1f }
    if (eMax - eMin < 1e-3f) { eMax = eMin + 1f }
    return TrailSeries(
        trail.points, tNorm,
        speed, sMin, sMax,
        ele, eMin, eMax,
        anySpeed, anyEle,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChart(
    s: TrailSeries,
    speedColor: Color,
    elevationColor: Color,
    gridColor: Color,
) {
    val w = size.width
    val h = size.height
    val padTop = h * 0.08f
    val padBot = h * 0.12f
    val drawH = h - padTop - padBot

    // Three horizontal grid lines at 25/50/75 % — keeps the chart
    // legible without a lot of chrome.
    for (i in 1..3) {
        val y = padTop + drawH * (i / 4f)
        drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
    }

    // Elevation first so the speed line draws on top (speed is the
    // primary metric for most users).
    if (s.hasElevation) {
        drawSeries(
            s.tNorm, s.ele, s.eleMin, s.eleMax, w, padTop, drawH,
            color = elevationColor, strokeWidth = 2f,
        )
    }
    if (s.hasSpeed) {
        drawSeries(
            s.tNorm, s.speed, s.speedMin, s.speedMax, w, padTop, drawH,
            color = speedColor, strokeWidth = 2.5f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    tNorm: FloatArray,
    values: FloatArray,
    vMin: Float, vMax: Float,
    width: Float, padTop: Float, drawH: Float,
    color: Color,
    strokeWidth: Float,
) {
    val range = vMax - vMin
    if (range == 0f || values.isEmpty()) return
    val path = Path()
    var movedTo = false
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) {
            // Discontinuity — re-moveTo after the gap so we don't draw
            // a line through "missing" segments.
            movedTo = false
            continue
        }
        val x = tNorm[i] * width
        val y = padTop + drawH * (1f - (v - vMin) / range)
        if (!movedTo) {
            path.moveTo(x, y); movedTo = true
        } else {
            path.lineTo(x, y)
        }
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}
