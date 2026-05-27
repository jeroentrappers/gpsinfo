package be.appmire.gpsinfo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.SatelliteInfo

@Composable
fun SnrBarChart(
    satellites: List<SatelliteInfo>,
    modifier: Modifier = Modifier
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val waitingText = stringResource(R.string.sky_waiting)
    // Hoisted Paint for the "Waiting…" placeholder + the dB-Hz axis label.
    val labelPaintCenter = remember(onSurfaceVariant, labelPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = labelPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val labelPaintLeft = remember(onSurfaceVariant, labelPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = labelPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        val w = size.width
        val h = size.height
        val padTop = with(density) { 8.dp.toPx() }
        val padBottom = with(density) { 22.dp.toPx() }
        val padLeft = with(density) { 6.dp.toPx() }
        val padRight = with(density) { 6.dp.toPx() }
        val chartH = h - padTop - padBottom
        val chartW = w - padLeft - padRight
        val maxSnr = 60f

        // gridlines at 20 / 40 / 60
        for (g in listOf(20, 40, 60)) {
            val y = padTop + chartH * (1f - g / maxSnr)
            drawLine(
                color = outline.copy(alpha = 0.5f),
                start = Offset(padLeft, y),
                end = Offset(w - padRight, y),
                strokeWidth = 1f
            )
        }

        if (satellites.isEmpty()) {
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText(waitingText, w / 2f, h / 2f, labelPaintCenter)
            }
            return@Canvas
        }

        val n = satellites.size
        val slot = chartW / n
        val barW = (slot * 0.6f).coerceAtMost(with(density) { 16.dp.toPx() })

        satellites.forEachIndexed { i, sat ->
            val cx = padLeft + slot * (i + 0.5f)
            val snr = sat.cn0DbHz.coerceIn(0f, maxSnr)
            val barH = chartH * (snr / maxSnr)
            val top = padTop + chartH - barH
            val color = snrColor(sat.cn0DbHz)

            // bar
            drawRoundRect(
                color = color.copy(alpha = if (sat.usedInFix) 1f else 0.45f),
                topLeft = Offset(cx - barW / 2f, top),
                size = Size(barW, barH.coerceAtLeast(2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
            // baseline tick — filled circle if used, hollow if just visible
            val tickY = padTop + chartH + with(density) { 2.dp.toPx() }
            if (sat.usedInFix) {
                drawCircle(
                    color = onSurface,
                    radius = with(density) { 2.dp.toPx() },
                    center = Offset(cx, tickY)
                )
            } else {
                drawCircle(
                    color = onSurfaceVariant,
                    radius = with(density) { 2.dp.toPx() },
                    center = Offset(cx, tickY),
                    style = Stroke(width = 1f)
                )
            }
        }

        // label at far-left bottom showing max (Paint hoisted)
        drawIntoCanvas { c ->
            c.nativeCanvas.drawText("dB-Hz", padLeft, padTop + labelPx, labelPaintLeft)
        }
    }
}

@Composable
fun AverageSnrBar(avg: Float, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val axisTextPx = with(density) { 9.sp.toPx() }
    val axisPaintLeft = remember(onSurfaceVariant, axisTextPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = axisTextPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
    }
    val axisPaintRight = remember(onSurfaceVariant, axisTextPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = axisTextPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width
        val h = size.height
        val barH = with(density) { 6.dp.toPx() }
        val barY = h - barH - with(density) { 8.dp.toPx() }
        val segments = listOf(
            0f to 10f to Color(0xFFEF5350),
            10f to 20f to Color(0xFFFFA726),
            20f to 30f to Color(0xFFFFEE58),
            30f to 50f to Color(0xFF66BB6A),
            50f to 99f to Color(0xFF2E7D32)
        )
        val max = 99f
        segments.forEach { (range, color) ->
            val (from, to) = range
            val x1 = w * (from / max)
            val x2 = w * (to / max)
            drawRect(color, topLeft = Offset(x1, barY), size = Size(x2 - x1, barH))
        }
        // pointer
        val pointerX = w * (avg.coerceIn(0f, max) / max)
        drawLine(
            color = onSurface,
            start = Offset(pointerX, barY - with(density) { 4.dp.toPx() }),
            end = Offset(pointerX, barY + barH + with(density) { 4.dp.toPx() }),
            strokeWidth = with(density) { 1.5.dp.toPx() }
        )
        // axis labels (Paints hoisted out of draw block)
        drawIntoCanvas { c ->
            val labelY = barY - with(density) { 2.dp.toPx() }
            c.nativeCanvas.drawText("0", 0f, labelY, axisPaintLeft)
            c.nativeCanvas.drawText("99", w, labelY, axisPaintRight)
        }
        // also outline rect for tidiness
        drawRect(
            outline.copy(alpha = 0.4f),
            topLeft = Offset(0f, barY),
            size = Size(w, barH),
            style = Stroke(width = 1f)
        )
    }
}
