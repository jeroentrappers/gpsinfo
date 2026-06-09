package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.GForceSample
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * G-meter dashboard card. Plots the in-plane acceleration as a dot on
 * a circular gauge — horizontal axis = lateral G (cornering), vertical
 * axis = longitudinal G (braking/acceleration) — with a fading
 * trailing history of recent samples and the magnitude shown as a
 * number, racing-dash style.
 *
 * The history buffer lives in the composable: each new [sample] is
 * pushed into a small ring and the older points are drawn with
 * decreasing alpha so the trace fades behind the live dot.
 */
@Composable
fun GForceCard(sample: GForceSample, onClick: (() -> Unit)? = null) {
    // Fixed-size ring of recent points; index 0 = oldest.
    val history = remember { ArrayDeque<GForceSample>(HISTORY) }
    // Push the latest sample (dedupe identical repeats from the StateFlow).
    if (history.lastOrNull() != sample) {
        history.addLast(sample)
        while (history.size > HISTORY) history.removeFirst()
    }

    SectionCard(title = stringResource(R.string.section_gforce)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            // Snapshot the history into a stable list for this frame.
            val trail = history.toList()
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                // Reserve a slim strip on the right for the vertical-G
                // bar; the circular gauge centres in the remaining space.
                val barW = size.width * 0.07f
                val gaugeW = size.width - barW * 2f
                val cx = gaugeW / 2f
                val cy = size.height / 2f
                val radius = min(cx, cy) * 0.92f

                // Concentric rings at each G ring + crosshair. Placed on
                // the logarithmic scale so the rings bunch toward the rim
                // — small forces get the open centre, large ones taper.
                for (g in 1..RINGS) {
                    drawCircle(
                        color = RING_COLOR,
                        radius = radius * logFrac(MAX_G * g / RINGS),
                        center = Offset(cx, cy),
                        style = Stroke(width = if (g == RINGS) 3f else 1.5f),
                    )
                }
                drawLine(RING_COLOR, Offset(cx - radius, cy), Offset(cx + radius, cy), 1.5f)
                drawLine(RING_COLOR, Offset(cx, cy - radius), Offset(cx, cy + radius), 1.5f)

                // Trailing history — older points fainter and smaller.
                val n = trail.size
                trail.forEachIndexed { i, s ->
                    val frac = (i + 1f) / n // 0..1, newest = 1
                    // Gentle fade: sqrt keeps older points visible far
                    // longer than a linear ramp would.
                    val f = sqrt(frac)
                    // Log-scaled radius keeps the direction but exaggerates
                    // small magnitudes and compresses large ones.
                    val m = s.horizontalMagnitudeG
                    val rScreen = radius * logFrac(m)
                    val ux = if (m > 1e-4f) s.lateralG / m else 0f
                    val uy = if (m > 1e-4f) s.longitudinalG / m else 0f
                    val px = cx + ux * rScreen
                    // Screen Y grows downward; positive longitudinal G
                    // (acceleration) should plot toward the top.
                    val py = cy - uy * rScreen
                    if (i == n - 1) {
                        // Live dot: bright, with a white ring.
                        drawCircle(Color.White, 10f, Offset(px, py))
                        drawCircle(DOT_COLOR, 7.5f, Offset(px, py))
                    } else {
                        drawCircle(
                            DOT_COLOR.copy(alpha = 0.18f + 0.62f * f),
                            3.5f + 3f * f,
                            Offset(px, py),
                        )
                    }
                }

                // Vertical-G bar, in the reserved strip on the right.
                // Gravity is already removed, so the centre line is the
                // resting state; the fill reads the *extra* vertical load —
                // up (cresting a rise, rebound) above centre, down
                // (compression in a dip, hard landing) below. Scaled to
                // ±MAX_G like the rings.
                val live = trail.lastOrNull() ?: GForceSample(0f, 0f, 0f)
                val barCx = size.width - barW / 2f
                val barTop = cy - radius
                val barBot = cy + radius
                val barMid = (barTop + barBot) / 2f
                drawLine(
                    color = RING_COLOR,
                    start = Offset(barCx, barTop),
                    end = Offset(barCx, barBot),
                    strokeWidth = barW,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.55f),
                    start = Offset(barCx - barW * 0.7f, barMid),
                    end = Offset(barCx + barW * 0.7f, barMid),
                    strokeWidth = with(density) { 1.dp.toPx() },
                )
                // Same log scale as the dial, mirrored about the centre.
                val vFrac = logFrac(abs(live.verticalG)) * sign(live.verticalG)
                val vEnd = barMid - vFrac * radius
                drawLine(
                    color = DOT_COLOR,
                    start = Offset(barCx, barMid),
                    end = Offset(barCx, vEnd),
                    strokeWidth = barW * 0.66f,
                    cap = StrokeCap.Round,
                )

                // Magnitude readout, centred — monospace so it doesn't
                // jitter in width.
                val mag = live.horizontalMagnitudeG
                val paint = android.graphics.Paint().apply {
                    color = if (mag > MAX_G) 0xFFE53935.toInt() else 0xFF7FE3FF.toInt()
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.MONOSPACE,
                        android.graphics.Typeface.BOLD,
                    )
                    isAntiAlias = true
                    textSize = with(density) { 26.dp.toPx() }
                }
                val unitPaint = android.graphics.Paint(paint).apply {
                    color = 0xFF7FCCFF.toInt()
                    typeface = android.graphics.Typeface.DEFAULT
                    textSize = with(density) { 11.dp.toPx() }
                }
                drawContext.canvas.nativeCanvas.apply {
                    drawText("%.2f".format(Locale.ROOT, mag), cx, cy + paint.textSize * 0.35f, paint)
                    drawText("G", cx, cy + paint.textSize * 0.95f, unitPaint)
                }
            }
        }
    }
}

/**
 * Maps a G magnitude (0..[MAX_G]) onto a 0..1 radius fraction with a
 * logarithmic curve: f(0) = 0, f(MAX_G) = 1. Small forces are stretched
 * out across the open centre of the dial so a gentle lean is clearly
 * visible, while large forces compress toward the rim. [LOG_K] sets how
 * aggressively the low end is exaggerated. Values past MAX_G clamp to 1.
 */
private fun logFrac(magG: Float): Float {
    val m = magG.coerceIn(0f, MAX_G)
    return ln(1f + LOG_K * (m / MAX_G)) / ln(1f + LOG_K)
}

private const val MAX_G = 1.2f
private const val LOG_K = 12f
private const val RINGS = 3
private const val HISTORY = 40
private val RING_COLOR = Color(0xFF9A9A9A).copy(alpha = 0.45f)
private val DOT_COLOR = Color(0xFFE67635)
