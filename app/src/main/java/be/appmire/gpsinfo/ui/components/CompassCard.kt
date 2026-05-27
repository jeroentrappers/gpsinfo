package be.appmire.gpsinfo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.util.headingToCardinal
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CompassCard(
    reading: CompassReading,
    /** GPS course-over-ground in degrees. Pass null when the user is
     *  below the heading-mode threshold ([be.appmire.gpsinfo.util.HeadingModeTracker]) —
     *  the card then suppresses the course needle and shows "—" for
     *  the course readout. */
    courseHeadingDeg: Float? = null,
) {
    SectionCard(title = stringResource(R.string.section_compass)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricSmall(stringResource(R.string.metric_orientation), headingToCardinal(reading.magneticHeadingDeg))
            MetricSmall(
                stringResource(R.string.metric_declination),
                stringResource(R.string.degrees_east_format, reading.declinationDeg),
            )
            MetricSmall(stringResource(R.string.metric_inclination), "%.0f°".format(reading.inclinationDeg))
        }

        Spacer(Modifier.height(8.dp))
        val roseDesc = stringResource(
            R.string.a11y_compass_rose,
            reading.magneticHeadingDeg.toInt(),
            headingToCardinal(reading.magneticHeadingDeg),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp)
                .semantics(mergeDescendants = true) { contentDescription = roseDesc },
            contentAlignment = Alignment.Center
        ) {
            CompassRose(
                continuousHeadingDeg = reading.continuousMagneticHeadingDeg,
                magneticHeadingDeg = reading.magneticHeadingDeg,
                courseHeadingDeg = courseHeadingDeg,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.compass_card_heading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "%03d".format(reading.magneticHeadingDeg.toInt()),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.degrees_magnetic),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // Course readout. Cyan when active (≥ 3 km/h), dimmed
                // grey "—" otherwise — the user always sees the cell
                // so they know the field exists, but its source isn't
                // claiming a number when it isn't reliable.
                val courseColor = if (courseHeadingDeg != null) {
                    be.appmire.gpsinfo.ui.theme.AccentCyan
                } else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    stringResource(R.string.compass_card_course),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    courseHeadingDeg?.let { "%03d".format(it.toInt()) }
                        ?: stringResource(R.string.placeholder_dash),
                    style = MaterialTheme.typography.titleLarge,
                    color = courseColor,
                )
                Text(
                    stringResource(R.string.degrees_true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricSmall(
                stringResource(R.string.metric_reciprocal),
                "%03d°".format(reading.reciprocalHeadingDeg.toInt()),
            )
            MetricSmall(
                stringResource(R.string.metric_mag_acc),
                stringResource(reading.accuracy.labelRes),
                accentForAccuracy(reading.accuracy),
            )
            MetricSmall(
                stringResource(R.string.metric_field),
                stringResource(R.string.unit_microtesla, reading.fieldStrengthNanoTesla),
            )
        }
    }
}

@Composable
private fun MetricSmall(label: String, value: String, accent: Color? = null) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = accent ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun accentForAccuracy(acc: MagneticAccuracy): Color = when (acc) {
    MagneticAccuracy.HIGH -> be.appmire.gpsinfo.ui.theme.SignalGreen
    MagneticAccuracy.MEDIUM -> be.appmire.gpsinfo.ui.theme.SignalYellow
    MagneticAccuracy.LOW -> be.appmire.gpsinfo.ui.theme.SignalOrange
    MagneticAccuracy.UNRELIABLE -> be.appmire.gpsinfo.ui.theme.SignalRed
    MagneticAccuracy.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Compass rose with a forward-tilted perspective and an unambiguous needle.
 *
 *   - Driven by a *continuous* (unwrapped) heading from the repository so
 *     wobble across 0°/360° cannot reverse the rotation direction.
 *   - The dial card itself is tilted ~22° around X via [graphicsLayer] to
 *     give it a 3D "tabletop compass" look — the user immediately sees the
 *     near edge (front of the device) vs the far edge (back).
 *   - A real two-tone needle replaces the symmetrical twin arrows: a long
 *     red wedge points to the front (device direction), a shorter white
 *     wedge tails behind. That mirrors a physical compass needle and is
 *     what people are trained to read.
 */
@Composable
private fun CompassRose(
    continuousHeadingDeg: Float,
    magneticHeadingDeg: Float = continuousHeadingDeg,
    courseHeadingDeg: Float? = null,
) {
    val animated = remember { Animatable(continuousHeadingDeg) }
    LaunchedEffect(continuousHeadingDeg) {
        // Continuous heading means we can animateTo directly — no shortest-
        // path math needed, no direction reversals at the 0°/360° boundary.
        animated.animateTo(continuousHeadingDeg, tween(durationMillis = 90))
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val needleFront = Color(0xFFEF5350)   // red — the device direction
    val needleBack = Color(0xFFE0E0E0)    // light grey — the tail
    val density = LocalDensity.current

    // Pre-allocate the three text Paints once per (color, density) tuple
    // and reuse them on every draw. Previously each Paint was constructed
    // inside drawIntoCanvas at 50 Hz — three allocations per frame.
    val tickFontPx = with(density) { 11.sp.toPx() }
    val cardinalPx = with(density) { 22.sp.toPx() }
    val tickPaint = remember(onSurfaceVariant, tickFontPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = tickFontPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT
        }
    }
    val cardinalPaint = remember(onSurface, cardinalPx) {
        android.graphics.Paint().apply {
            color = onSurface.toArgb()
            textSize = cardinalPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val northPaint = remember(needleFront, cardinalPx) {
        android.graphics.Paint(cardinalPaint).apply { color = needleFront.toArgb() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                // Mild forward tilt — enough depth cue to distinguish the
                // near/far edges of the bezel, not so much that the lower
                // numbers foreshorten into illegibility. 22° was too steep;
                // 12° keeps the dial flat-ish and readable.
                rotationX = 12f
                // Higher camera distance softens the perspective foreshortening.
                cameraDistance = 20f * density.density
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cx = size.minDimension / 2f
            val cy = size.minDimension / 2f
            val outerR = size.minDimension / 2f - with(density) { 4.dp.toPx() }
            val innerR = outerR * 0.78f

            // Dial face — radial gradient centred to keep the dial looking
            // flat under the gentler tilt.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        surface,
                        outline.copy(alpha = 0.30f),
                    ),
                    center = Offset(cx, cy),
                    radius = outerR,
                ),
                radius = outerR,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = outline,
                radius = outerR,
                center = Offset(cx, cy),
                style = Stroke(width = with(density) { 1.5.dp.toPx() }),
            )

            rotate(degrees = -animated.value, pivot = Offset(cx, cy)) {
                // tick marks every 5°, longer every 30°
                for (i in 0 until 72) {
                    val angle = i * 5.0
                    val rad = Math.toRadians(angle - 90.0)
                    val long = i % 6 == 0
                    val tickInner = if (long) innerR else innerR + (outerR - innerR) * 0.55f
                    val color = if (long) onSurface else onSurfaceVariant
                    val width = if (long) 2f else 1f
                    drawLine(
                        color = color,
                        start = Offset(
                            cx + (cos(rad) * tickInner).toFloat(),
                            cy + (sin(rad) * tickInner).toFloat()
                        ),
                        end = Offset(
                            cx + (cos(rad) * outerR).toFloat(),
                            cy + (sin(rad) * outerR).toFloat()
                        ),
                        strokeWidth = with(density) { width.dp.toPx() },
                    )
                }

                // numeric labels + cardinal letters (Paints hoisted outside
                // the draw block — reused across frames).
                drawIntoCanvas { canvas ->
                    val labelRadius = innerR - with(density) { 6.dp.toPx() }
                    val cardinalRadius = innerR - with(density) { 18.dp.toPx() }
                    val cardinals = mapOf(
                        0 to "N", 90 to "E", 180 to "S", 270 to "W",
                        45 to "NE", 135 to "SE", 225 to "SW", 315 to "NW",
                    )
                    for (deg in 0 until 360 step 15) {
                        val rad = Math.toRadians(deg - 90.0)
                        if (cardinals.containsKey(deg)) {
                            val x = cx + (cos(rad) * cardinalRadius).toFloat()
                            val y = cy + (sin(rad) * cardinalRadius).toFloat() + cardinalPx / 3f
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.rotate(deg.toFloat(), x, y - cardinalPx / 3f)
                            val paint = if (deg == 0) northPaint else cardinalPaint
                            canvas.nativeCanvas.drawText(cardinals[deg]!!, x, y, paint)
                            canvas.nativeCanvas.restore()
                        } else {
                            val x = cx + (cos(rad) * labelRadius).toFloat()
                            val y = cy + (sin(rad) * labelRadius).toFloat() + tickFontPx / 3f
                            canvas.nativeCanvas.save()
                            canvas.nativeCanvas.rotate(deg.toFloat(), x, y - tickFontPx / 3f)
                            canvas.nativeCanvas.drawText(deg.toString(), x, y, tickPaint)
                            canvas.nativeCanvas.restore()
                        }
                    }
                }
            }

            // Stationary needle — points at the top (device direction) so the
            // user always knows "front of phone = pointy red end."
            drawNeedle(cx, cy, outerR, density, needleFront, needleBack, primary)

            // Course-over-ground needle. Drawn at the screen angle that
            // maps world-deg `course` onto the rotated rose. The rose
            // rotates by -magneticHeading, so world direction `D` lands
            // at screen angle (D - magneticHeading). A cyan arrow makes
            // the difference between phone-heading (red) and direction-
            // of-travel (cyan) immediately legible.
            if (courseHeadingDeg != null) {
                val deltaDeg = (courseHeadingDeg - magneticHeadingDeg).toFloat()
                rotate(degrees = deltaDeg, pivot = Offset(cx, cy)) {
                    drawCourseArrow(cx, cy, outerR, density, Color(0xFF00BCD4))
                }
            }

            // FRONT / BACK chevrons baked into the bezel make the orientation
            // legible even when the rose is moving fast.
            drawBezelMarker(
                cx = cx, cy = cy, outerR = outerR,
                isTop = true, density = density, color = needleFront,
            )
            drawBezelMarker(
                cx = cx, cy = cy, outerR = outerR,
                isTop = false, density = density,
                color = needleBack.copy(alpha = 0.55f),
            )
        }
    }
}

/**
 * Cyan stroke + arrowhead indicating GPS course-over-ground relative
 * to the rose. Drawn separately from the magnetic needle so the
 * user can compare phone-pointing vs direction-of-travel at a
 * glance — divergence is the diagnostic ("phone in pocket while
 * walking", "phone in cradle while driving").
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCourseArrow(
    cx: Float,
    cy: Float,
    outerR: Float,
    density: androidx.compose.ui.unit.Density,
    color: Color,
) {
    val len = outerR * 0.80f
    val headWidth = with(density) { 11.dp.toPx() }
    val headLength = with(density) { 14.dp.toPx() }
    val shaft = with(density) { 4.dp.toPx() }
    // Slim outlined arrow so it sits cleanly over the rose ticks
    // without obscuring the magnetic needle underneath.
    val arrowTipY = cy - len
    drawLine(
        color = color,
        start = Offset(cx, cy + outerR * 0.18f),
        end = Offset(cx, arrowTipY + headLength * 0.6f),
        strokeWidth = shaft,
    )
    val headPath = Path().apply {
        moveTo(cx, arrowTipY)
        lineTo(cx - headWidth, arrowTipY + headLength)
        lineTo(cx + headWidth, arrowTipY + headLength)
        close()
    }
    drawPath(path = headPath, color = color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNeedle(
    cx: Float,
    cy: Float,
    outerR: Float,
    density: androidx.compose.ui.unit.Density,
    front: Color,
    back: Color,
    primary: Color,
) {
    val needleLength = outerR * 0.92f
    val tailLength = outerR * 0.55f
    val halfWidth = with(density) { 9.dp.toPx() }
    val tailHalf = with(density) { 6.dp.toPx() }

    // Front (red) wedge — points up.
    val frontPath = Path().apply {
        moveTo(cx, cy - needleLength)
        lineTo(cx - halfWidth, cy)
        lineTo(cx + halfWidth, cy)
        close()
    }
    drawPath(
        path = frontPath,
        brush = Brush.verticalGradient(
            colors = listOf(front, front.copy(alpha = 0.75f)),
            startY = cy - needleLength,
            endY = cy,
        ),
    )
    drawPath(
        path = frontPath,
        color = Color.Black.copy(alpha = 0.35f),
        style = Stroke(width = with(density) { 0.5.dp.toPx() }),
    )

    // Tail (white) wedge — points down.
    val tailPath = Path().apply {
        moveTo(cx, cy + tailLength)
        lineTo(cx - tailHalf, cy)
        lineTo(cx + tailHalf, cy)
        close()
    }
    drawPath(
        path = tailPath,
        brush = Brush.verticalGradient(
            colors = listOf(back.copy(alpha = 0.75f), back.copy(alpha = 0.55f)),
            startY = cy,
            endY = cy + tailLength,
        ),
    )

    // Pivot dot — small ring at the centre to anchor the needle.
    drawCircle(
        color = primary,
        radius = with(density) { 5.dp.toPx() },
        center = Offset(cx, cy),
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.6f),
        radius = with(density) { 5.dp.toPx() },
        center = Offset(cx, cy),
        style = Stroke(width = with(density) { 1.dp.toPx() }),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBezelMarker(
    cx: Float,
    cy: Float,
    outerR: Float,
    isTop: Boolean,
    density: androidx.compose.ui.unit.Density,
    color: Color,
) {
    val arrowSize = with(density) { 9.dp.toPx() }
    val offset = with(density) { 2.dp.toPx() }
    val (tipY, baseY) = if (isTop) {
        (cy - outerR - offset) to (cy - outerR + arrowSize - offset)
    } else {
        (cy + outerR + offset) to (cy + outerR - arrowSize + offset)
    }
    val path = Path().apply {
        moveTo(cx, tipY)
        lineTo(cx - arrowSize * 0.7f, baseY)
        lineTo(cx + arrowSize * 0.7f, baseY)
        close()
    }
    drawPath(path, color = color)
}

