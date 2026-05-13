package com.appmire.gpsinfo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Retro VW T4-style analog dial. Ported from the id.dash project — same
 * geometry and ticking behaviour. Self-contained: draws everything on a
 * Canvas, no theme dependencies, so it sits cleanly inside any dark
 * cluster-style screen.
 *
 * One coloured sector on the dial face (e.g. red zone, green band).
 * @param fromFraction 0..1 along the dial's full sweep
 * @param toFraction   0..1 along the dial's full sweep
 */
data class DialZone(val fromFraction: Float, val toFraction: Float, val color: Color)

/**
 * @param valueFraction       Needle position 0..1 along the sweep.
 * @param valueToFraction     Optional non-linear value → 0..1 mapping for ticks.
 * @param accentTickValues    Values rendered in [accentTickColor] with major-tick weight.
 */
@Composable
fun RetroDial(
    valueFraction: Float,
    minValue: Float,
    maxValue: Float,
    tickStep: Float,
    labelStep: Float,
    label: String,
    zones: List<DialZone> = emptyList(),
    startAngleDegrees: Float = 150f,
    sweepDegrees: Float = 240f,
    needleColor: Color = Color.White,
    valueToFraction: ((Float) -> Float)? = null,
    accentTickValues: List<Float> = emptyList(),
    accentTickColor: Color = Color(0xFFE67635),
    modifier: Modifier = Modifier,
) {
    val target = valueFraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "needle",
    )
    val measurer = rememberTextMeasurer()
    val tickStyle = remember {
        TextStyle(
            color = Color(0xFFEDEDED),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
    val centerLabelStyle = remember {
        TextStyle(
            color = Color(0xFF7FCCFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = shortestSide(size) / 2f * 0.92f
            val cornerRadius = shortestSide(size) * 0.10f

            // Coloured zones drawn as thick arcs at the outer rim of the
            // dial face, sitting under the inner end of the tick marks.
            for (zone in zones) {
                val startDeg = startAngleDegrees + sweepDegrees * zone.fromFraction
                val endDeg = startAngleDegrees + sweepDegrees * zone.toFraction
                drawArc(
                    color = zone.color,
                    startAngle = startDeg,
                    sweepAngle = endDeg - startDeg,
                    useCenter = false,
                    topLeft = Offset(cx - radius * 0.96f, cy - radius * 0.96f),
                    size = Size(radius * 1.92f, radius * 1.92f),
                    style = Stroke(width = radius * 0.02f),
                )
            }

            // Ticks + labels — inner endpoints share one ring, outer
            // endpoints follow the rounded-square bezel so corner ticks
            // are visibly longer than edge ticks.
            val range = maxValue - minValue
            val side = shortestSide(size)
            val sharedInnerT = radius * 0.94f
            val bezelInset = side * 0.025f
            val labelT = radius * 0.78f
            val tickFraction: (Float) -> Float = valueToFraction
                ?: { v -> (v - minValue) / range }
            var v = minValue
            while (v <= maxValue + 0.0001f) {
                val frac = tickFraction(v).coerceIn(0f, 1f)
                val angle = Math.toRadians((startAngleDegrees + sweepDegrees * frac).toDouble())
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()
                val isMajor = ((v - minValue) % labelStep).let {
                    it < 0.0001f || (labelStep - it) < 0.0001f
                }
                val isAccent = accentTickValues.any { abs(v - it) < 0.001f }
                val outerT = roundedRectRayDistance(
                    dx = cosA, dy = sinA,
                    halfW = w / 2f, halfH = h / 2f,
                    cornerR = cornerRadius,
                ) - bezelInset
                if (sharedInnerT >= outerT) {
                    v += tickStep
                    continue
                }
                val tickWidth = if (isMajor) side * 0.014f else side * 0.006f
                val tickColor = when {
                    isAccent -> accentTickColor
                    isMajor -> Color(0xFFEDEDED)
                    else -> Color(0xFF9A9A9A)
                }
                drawLine(
                    tickColor,
                    Offset(cx + outerT * cosA, cy + outerT * sinA),
                    Offset(cx + sharedInnerT * cosA, cy + sharedInnerT * sinA),
                    strokeWidth = tickWidth,
                )

                if (isMajor) {
                    val text = formatTick(v)
                    val tx = cx + labelT * cosA
                    val ty = cy + labelT * sinA
                    val layout = measurer.measure(text, style = tickStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            tx - layout.size.width / 2f,
                            ty - layout.size.height / 2f,
                        ),
                    )
                }
                v += tickStep
            }

            // Dial label sits just above the hub.
            val labelLayout = measurer.measure(label, style = centerLabelStyle)
            drawText(
                textLayoutResult = labelLayout,
                topLeft = Offset(cx - labelLayout.size.width / 2f, cy - radius * 0.42f),
            )

            // Needle — long tapered pointer with a tiny counterweight tail.
            val needleAngle = startAngleDegrees + sweepDegrees * animated
            translate(left = cx, top = cy) {
                rotate(needleAngle, pivot = Offset.Zero) {
                    val pointer = Path().apply {
                        moveTo(-radius * 0.26f, -radius * 0.026f)
                        lineTo(radius * 0.89f, -radius * 0.012f)
                        lineTo(radius * 0.93f, 0f)
                        lineTo(radius * 0.89f, radius * 0.012f)
                        lineTo(-radius * 0.26f, radius * 0.026f)
                        close()
                    }
                    drawPath(pointer, color = needleColor)
                    drawCircle(
                        color = Color(0xFF1A1A1A),
                        radius = radius * 0.010f,
                        center = Offset.Zero,
                    )
                }
            }
        }
    }
}

private fun formatTick(value: Float): String {
    val rounded = value.toInt()
    return if (rounded.toFloat() == value) rounded.toString()
    else String.format("%.0f", value)
}

private fun shortestSide(s: Size): Float = max(0f, kotlin.math.min(s.width, s.height))

/**
 * Distance from the origin along a unit ray (dx, dy) at which it exits a
 * rounded rectangle centred at the origin with half-width [halfW],
 * half-height [halfH] and corner radius [cornerR]. Used to make tick marks
 * extend out to the housing edge rather than stopping at the inscribed
 * circle.
 */
private fun roundedRectRayDistance(
    dx: Float,
    dy: Float,
    halfW: Float,
    halfH: Float,
    cornerR: Float,
): Float {
    val adx = abs(dx)
    val ady = abs(dy)
    if (adx < 1e-6f && ady < 1e-6f) return 0f

    val tx = if (adx > 1e-6f) halfW / adx else Float.POSITIVE_INFINITY
    val ty = if (ady > 1e-6f) halfH / ady else Float.POSITIVE_INFINITY
    val tStraight = kotlin.math.min(tx, ty)

    val px = tStraight * adx
    val py = tStraight * ady
    if (cornerR > 0f && px > halfW - cornerR && py > halfH - cornerR) {
        val p = halfW - cornerR
        val q = halfH - cornerR
        val qa = adx * adx + ady * ady
        val qb = -2f * (adx * p + ady * q)
        val qc = p * p + q * q - cornerR * cornerR
        val disc = qb * qb - 4f * qa * qc
        if (disc >= 0f) {
            return (-qb + sqrt(disc)) / (2f * qa)
        }
    }
    return tStraight
}
