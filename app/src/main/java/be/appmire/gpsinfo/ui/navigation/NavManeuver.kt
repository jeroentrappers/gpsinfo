package be.appmire.gpsinfo.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.nav.Lane
import be.appmire.gpsinfo.data.nav.TurnCommand
import be.appmire.gpsinfo.util.UnitConverter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turn-by-turn maneuver rendering for the phone navigation screen: a
 * crisp vector maneuver arrow drawn straight on a Compose [Canvas], a
 * lane-guidance row, and the distance/ETA formatters. Pure drawing — no
 * bundled icon assets, so it scales to any size and tints to the theme.
 */

/** A bold maneuver arrow for [command]. Drawn as a single-bend arrow
 *  (continue / slight / turn / sharp / keep, both sides) plus dedicated
 *  shapes for U-turn and roundabout. [color] is the arrow ink. */
@Composable
fun ManeuverIcon(
    command: TurnCommand,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val sw = size.minDimension * 0.14f
        when (command) {
            TurnCommand.U_TURN -> drawUTurn(color, sw)
            TurnCommand.ROUNDABOUT -> drawRoundabout(color, sw)
            else -> drawBendArrow(bendAngleDeg(command), color, sw)
        }
    }
}

/** Lane-guidance strip: one cell per lane, each showing its permitted
 *  directions; lanes that follow the route are inked solid, the rest are
 *  dimmed. Renders nothing when there's no lane data (BRouter routes). */
@Composable
fun LaneGuidance(
    lanes: List<Lane>?,
    modifier: Modifier = Modifier,
) {
    if (lanes.isNullOrEmpty()) return
    val active = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(
        modifier = modifier.height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lanes.forEach { lane ->
            val ink = if (lane.active) active else dim
            Box(modifier = Modifier.size(34.dp, 40.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.dp)) {
                    val sw = size.minDimension * (if (lane.active) 0.12f else 0.09f)
                    val dirs = lane.directions.ifEmpty { listOf(TurnCommand.STRAIGHT) }
                    dirs.forEach { d -> drawLaneStroke(bendAngleDeg(d), ink, sw) }
                }
            }
        }
    }
}

// ── Distance / ETA formatting (respects the unit system) ─────────────

/** Short driving distance, e.g. "240 m" / "1.2 km" (metric), "800 ft" /
 *  "0.7 mi" (imperial), "0.4 nm" (nautical). Rounds metres/feet to 10. */
fun formatNavDistance(meters: Double, unit: UnitSystem): String {
    return when (unit) {
        UnitSystem.Metric ->
            if (meters >= 1000) "%.1f km".format(java.util.Locale.ROOT, meters / 1000.0)
            else "${(meters / 10).toInt() * 10} m"
        UnitSystem.Imperial -> {
            val ft = UnitConverter.lengthFromMeters(meters, UnitSystem.Imperial)
            if (ft >= 1000) "%.1f mi".format(java.util.Locale.ROOT, meters / 1609.344)
            else "${(ft / 10).toInt() * 10} ft"
        }
        UnitSystem.Nautical -> {
            val ft = UnitConverter.lengthFromMeters(meters, UnitSystem.Imperial)
            if (ft >= 1200) "%.1f nm".format(java.util.Locale.ROOT, meters / 1852.0)
            else "${(ft / 10).toInt() * 10} ft"
        }
    }
}

/** Remaining travel time, e.g. "4 min" / "1 h 12 min". */
fun formatNavDuration(seconds: Int): String {
    val totalMin = (seconds / 60.0).toInt().coerceAtLeast(0)
    return if (totalMin >= 60) "%d h %02d min".format(java.util.Locale.ROOT, totalMin / 60, totalMin % 60)
    else "$totalMin min"
}

// ── Canvas drawing primitives ────────────────────────────────────────

/** Bend angle in degrees from straight-up: negative = left, positive =
 *  right. Continue / unknown read as straight. */
private fun bendAngleDeg(c: TurnCommand): Float = when (c) {
    TurnCommand.TURN_SLIGHT_LEFT -> -38f
    TurnCommand.TURN_LEFT -> -90f
    TurnCommand.TURN_SHARP_LEFT -> -132f
    TurnCommand.TURN_SLIGHT_RIGHT -> 38f
    TurnCommand.TURN_RIGHT -> 90f
    TurnCommand.TURN_SHARP_RIGHT -> 132f
    TurnCommand.KEEP_LEFT -> -22f
    TurnCommand.KEEP_RIGHT -> 22f
    else -> 0f
}

/** A shaft rising from the bottom centre that bends off at [angleDeg],
 *  capped with a filled arrowhead. angleDeg 0 = straight up. */
private fun DrawScope.drawBendArrow(angleDeg: Float, color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val baseY = h * 0.9f
    val headLen = w * 0.30f
    val headHalf = w * 0.20f

    if (angleDeg == 0f) {
        val tipY = h * 0.12f
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, baseY), androidx.compose.ui.geometry.Offset(cx, tipY), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawArrowHead(cx, tipY, 0f, -1f, headLen, headHalf, color)
        return
    }

    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val bendX = cx
    val bendY = h * 0.52f
    val seg = w * 0.32f
    val tipX = bendX + dx * seg
    val tipY = bendY + dy * seg

    val path = Path().apply {
        moveTo(cx, baseY)
        lineTo(bendX, bendY)
        lineTo(tipX, tipY)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawArrowHead(tipX, tipY, dx, dy, headLen, headHalf, color)
}

/** A thin lane stroke from the bottom centre bending off at [angleDeg],
 *  with a small head — the compact form used inside lane cells. */
private fun DrawScope.drawLaneStroke(angleDeg: Float, color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val baseY = h * 0.92f
    val headLen = w * 0.26f
    val headHalf = w * 0.16f
    if (angleDeg == 0f) {
        val tipY = h * 0.14f
        drawLine(color, androidx.compose.ui.geometry.Offset(cx, baseY), androidx.compose.ui.geometry.Offset(cx, tipY), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawArrowHead(cx, tipY, 0f, -1f, headLen, headHalf, color)
        return
    }
    val rad = Math.toRadians(angleDeg.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val bendY = h * 0.55f
    val seg = w * 0.30f
    val tipX = cx + dx * seg
    val tipY = bendY + dy * seg
    val path = Path().apply {
        moveTo(cx, baseY)
        lineTo(cx, bendY)
        lineTo(tipX, tipY)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawArrowHead(tipX, tipY, dx, dy, headLen, headHalf, color)
}

private fun DrawScope.drawUTurn(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val r = w * 0.20f
    val topY = h * 0.30f
    val tipY = h * 0.70f
    val path = Path().apply {
        moveTo(cx + r, h * 0.66f)
        lineTo(cx + r, topY)
        // Semicircle over the top from the right column to the left column.
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(cx - r, topY - r, cx + r, topY + r),
            startAngleDegrees = 0f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false,
        )
        lineTo(cx - r, tipY)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawArrowHead(cx - r, tipY, 0f, 1f, w * 0.28f, w * 0.18f, color)
}

private fun DrawScope.drawRoundabout(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.54f
    val r = w * 0.22f
    // Entry from the bottom, the ring, and an exit leaving up.
    drawLine(color, androidx.compose.ui.geometry.Offset(cx, h * 0.92f), androidx.compose.ui.geometry.Offset(cx, cy + r), stroke * 0.8f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawCircle(color, r, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(width = stroke * 0.8f))
    val exitX = cx + r * 0.7f
    drawLine(color, androidx.compose.ui.geometry.Offset(cx + r * 0.5f, cy - r * 0.5f), androidx.compose.ui.geometry.Offset(exitX, h * 0.12f), stroke * 0.8f, androidx.compose.ui.graphics.StrokeCap.Round)
    drawArrowHead(exitX, h * 0.12f, 0.35f, -1f, w * 0.24f, w * 0.16f, color)
}

/** Filled triangular arrowhead at ([tipX],[tipY]) pointing along the unit
 *  vector ([dirX],[dirY]). */
private fun DrawScope.drawArrowHead(
    tipX: Float, tipY: Float, dirX: Float, dirY: Float,
    len: Float, half: Float, color: Color,
) {
    // Normalise the direction.
    val mag = kotlin.math.hypot(dirX, dirY).coerceAtLeast(1e-3f)
    val ux = dirX / mag
    val uy = dirY / mag
    val perpX = -uy
    val perpY = ux
    val backX = tipX - ux * len
    val backY = tipY - uy * len
    val p = Path().apply {
        moveTo(tipX, tipY)
        lineTo(backX + perpX * half, backY + perpY * half)
        lineTo(backX - perpX * half, backY - perpY * half)
        close()
    }
    drawPath(p, color)
}
