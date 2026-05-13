package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.model.Constellation
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.SatelliteInfo
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SkyViewCard(snapshot: GnssSnapshot) {
    SectionCard(title = stringResource(R.string.section_sky)) {
        Column {
            SkyRadial(satellites = snapshot.satellites)
            Spacer(Modifier.height(10.dp))
            SnrBarChart(satellites = snapshot.satellites)
            Spacer(Modifier.height(6.dp))
            AverageSnrBar(avg = snapshot.averageSnr)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.sky_summary,
                        snapshot.satellitesInView,
                        snapshot.satellitesInUse,
                        snapshot.averageSnr,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            ConstellationLegend(snapshot.satellites)
        }
    }
}

@Composable
private fun SkyRadial(satellites: List<SatelliteInfo>) {
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val labelPx = with(density) { 9.sp.toPx() }
    // Hoisted Paints — previously constructed inside drawIntoCanvas on
    // every frame.
    val cardinalPaint = remember(onSurfaceVariant, labelPx) {
        android.graphics.Paint().apply {
            color = onSurfaceVariant.toArgb()
            textSize = labelPx + 2f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val svidPaint = remember(onSurface, labelPx) {
        android.graphics.Paint().apply {
            color = onSurface.toArgb()
            textSize = labelPx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val rMax = size.minDimension / 2f - with(density) { 8.dp.toPx() }
            // concentric rings at 0°/30°/60°/90° elevation
            for (elev in listOf(0, 30, 60, 90)) {
                val r = rMax * (90 - elev) / 90f
                drawCircle(
                    color = outline.copy(alpha = if (elev == 0) 0.9f else 0.4f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = if (elev == 0) 1.5f else 1f)
                )
            }
            // cardinal cross
            drawLine(outline.copy(alpha = 0.4f), Offset(cx - rMax, cy), Offset(cx + rMax, cy), 1f)
            drawLine(outline.copy(alpha = 0.4f), Offset(cx, cy - rMax), Offset(cx, cy + rMax), 1f)
            // cardinal letters (Paint hoisted out of draw block)
            drawIntoCanvas { c ->
                c.nativeCanvas.drawText("N", cx, cy - rMax - 4f, cardinalPaint)
                c.nativeCanvas.drawText("S", cx, cy + rMax + labelPx + 2f, cardinalPaint)
                c.nativeCanvas.drawText("E", cx + rMax + 8f, cy + labelPx / 2f, cardinalPaint)
                c.nativeCanvas.drawText("W", cx - rMax - 8f, cy + labelPx / 2f, cardinalPaint)
            }
            // satellites — Paint hoisted out of draw block.
            val labelPaint = svidPaint
            satellites.forEach { sat ->
                if (sat.elevationDeg < 0f || sat.elevationDeg > 90f) return@forEach
                val r = rMax * (90f - sat.elevationDeg) / 90f
                val az = Math.toRadians((sat.azimuthDeg - 90.0))
                val x = cx + (cos(az) * r).toFloat()
                val y = cy + (sin(az) * r).toFloat()
                val color = Color(sat.constellation.color)
                val dotR = with(density) { (if (sat.usedInFix) 6.dp else 5.dp).toPx() }
                drawCircle(color = color.copy(alpha = if (sat.usedInFix) 1f else 0.45f),
                    radius = dotR, center = Offset(x, y))
                if (sat.usedInFix) {
                    drawCircle(
                        color = onSurface,
                        radius = dotR + with(density) { 1.5.dp.toPx() },
                        center = Offset(x, y),
                        style = Stroke(width = with(density) { 1.dp.toPx() })
                    )
                }
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawText(sat.svid.toString(), x, y + dotR + labelPx + 2f, labelPaint)
                }
            }
        }
    }
}

@Composable
private fun ConstellationLegend(satellites: List<SatelliteInfo>) {
    val present = satellites.map { it.constellation }.distinct().sortedBy { it.ordinal }
    if (present.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        present.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(c.color))
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    c.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

