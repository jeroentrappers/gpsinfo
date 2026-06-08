package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.GForceSample
import java.util.Locale
import kotlin.math.min

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
fun GForceCard(sample: GForceSample) {
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
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            // Snapshot the history into a stable list for this frame.
            val trail = history.toList()
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = min(cx, cy) * 0.92f
                // Gauge spans ±MAX_G to the outer ring.
                val pxPerG = radius / MAX_G

                // Concentric rings at each G ring + crosshair.
                for (g in 1..RINGS) {
                    drawCircle(
                        color = RING_COLOR,
                        radius = pxPerG * g * (MAX_G / RINGS),
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
                    val px = cx + (s.lateralG * pxPerG).coerceIn(-radius, radius)
                    // Screen Y grows downward; positive longitudinal G
                    // (acceleration) should plot toward the top.
                    val py = cy - (s.longitudinalG * pxPerG).coerceIn(-radius, radius)
                    if (i == n - 1) {
                        // Live dot: bright, with a white ring.
                        drawCircle(Color.White, 9f, Offset(px, py))
                        drawCircle(DOT_COLOR, 6.5f, Offset(px, py))
                    } else {
                        drawCircle(
                            DOT_COLOR.copy(alpha = 0.05f + 0.5f * frac),
                            2f + 2.5f * frac,
                            Offset(px, py),
                        )
                    }
                }

                // Magnitude readout, centred — monospace so it doesn't
                // jitter in width.
                val live = trail.lastOrNull() ?: GForceSample(0f, 0f, 0f)
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

private const val MAX_G = 1.2f
private const val RINGS = 3
private const val HISTORY = 40
private val RING_COLOR = Color(0xFF9A9A9A).copy(alpha = 0.45f)
private val DOT_COLOR = Color(0xFFE67635)
