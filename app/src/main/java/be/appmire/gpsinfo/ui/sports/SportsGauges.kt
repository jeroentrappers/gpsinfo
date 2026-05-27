package be.appmire.gpsinfo.ui.sports

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Semicircular gauge for "current vs goal pace" feedback. The needle
 * sits dead-centre when the runner is on goal, swings LEFT when they
 * fall behind ("too slow"), RIGHT when ahead ("too fast"). Colour
 * bands: green ±5 s/unit (on target), amber ±15 s/unit (off but
 * recoverable), red beyond that (audible cues fire when the needle
 * enters the red bands — see #41).
 *
 * Full-scale is ±[GAUGE_HALF_RANGE_S] s/unit. Beyond that the needle
 * clamps to the end of its sweep; the runner has bigger problems than
 * a numerical readout at that point.
 */
@Composable
fun PaceDeviationGauge(
    currentPaceSecondsPerUnit: Float?,
    targetPaceSecondsPerUnit: Float,
    paceUnitLabel: String,
    modifier: Modifier = Modifier,
) {
    val delta: Float? = currentPaceSecondsPerUnit?.minus(targetPaceSecondsPerUnit)
    val normalised: Float = delta?.let { (it / GAUGE_HALF_RANGE_S).coerceIn(-1f, 1f) } ?: 0f

    // Needle rotation in degrees, clockwise from "up". Positive delta
    // (slower than goal) rotates the needle counter-clockwise (left);
    // negative delta (faster) rotates it clockwise (right).
    val needleDeg by animateFloatAsState(
        targetValue = -normalised * 90f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "paceNeedle",
    )

    val statusRes = when {
        delta == null -> R.string.nav_goal_acquiring
        kotlin.math.abs(delta) <= GAUGE_GREEN_S -> R.string.nav_goal_on_target
        delta > 0f -> R.string.nav_goal_too_slow
        else -> R.string.nav_goal_too_fast
    }
    val statusColor = when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        kotlin.math.abs(delta) <= GAUGE_GREEN_S -> SignalGreen
        kotlin.math.abs(delta) <= GAUGE_AMBER_S -> SignalYellow
        else -> SignalRed
    }
    val deltaText = delta?.let {
        // Sign-prefixed integer seconds. Negative = ahead of goal.
        "%+d s%s".format(Locale.ROOT, it.toInt(), paceUnitLabel)
    } ?: stringResource(R.string.placeholder_dash)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.gauge_pace_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val bandColors = paceBands()
            val needleColor = statusColor
            // Compact glanceable readout in the bowl of the arc. The full
            // "+5 s/km" appears below the dial — this is just the signed
            // seconds delta in big mono digits, for at-a-glance reading
            // while running.
            val bowlText = delta?.let { "%+d".format(Locale.ROOT, it.toInt()) }
                ?: stringResource(R.string.placeholder_dash)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
                    drawSemicircularGauge(
                        bands = bandColors,
                        tickFractions = PACE_TICK_FRACTIONS,
                        needleDeg = needleDeg,
                        needleColor = needleColor,
                    )
                }
                Text(
                    text = bowlText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                )
            }

            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
            Text(
                text = deltaText,
                style = MaterialTheme.typography.headlineMedium,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

/**
 * Semicircular HR zone gauge. The arc is divided into the five zones
 * (Z1..Z5) using the configured [HrZoneConfig] fractions, each painted
 * in its zone colour. A needle points at the current BPM along the
 * 0..maxBpm range. Below the dial, the precise BPM number is printed
 * in the zone's colour together with the zone label.
 */
@Composable
fun HrZoneGauge(
    bpm: Int?,
    zoneConfig: HrZoneConfig,
    modifier: Modifier = Modifier,
) {
    val maxBpm = zoneConfig.maxBpm.coerceAtLeast(1)
    val frac = bpm?.let { (it.toFloat() / maxBpm).coerceIn(0f, 1f) } ?: 0f
    // Needle rotation: 0 (left end, BPM=0) maps to -90°; 1.0 (right end,
    // BPM = max) maps to +90°. Linear in between.
    val needleDeg by animateFloatAsState(
        targetValue = (frac * 180f) - 90f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "hrNeedle",
    )

    val zone = bpm?.let(zoneConfig::zoneFor)
    val zoneColor = zone?.let(::zoneColor) ?: MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.gauge_hr_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val bands = hrZoneBands(zoneConfig)
            // Big BPM in the bowl — the runner's primary read; the
            // already-existing BPM block below the dial doubles as a
            // legend with the zone tag.
            val bowlText = bpm?.toString() ?: stringResource(R.string.placeholder_dash)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
                    drawSemicircularGauge(
                        bands = bands,
                        tickFractions = listOf(0.25f, 0.5f, 0.75f),
                        needleDeg = needleDeg,
                        needleColor = zoneColor,
                    )
                }
                Text(
                    text = bowlText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = zoneColor,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = bpm?.toString() ?: stringResource(R.string.placeholder_dash),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = zoneColor,
                    )
                    Text(
                        text = "BPM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (zone != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Z$zone",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = zoneColor,
                        )
                        Text(
                            text = stringResource(R.string.hr_card_zone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------- Shared drawing primitives ---------- //

/**
 * Generic semicircular gauge renderer:
 *   - Coloured arc bands ([bands]) for the dial face
 *   - Tick marks at the fractional positions in [tickFractions]
 *   - A triangular needle anchored at the bottom-centre, rotated by
 *     [needleDeg] degrees clockwise from straight up.
 *
 * Drawn inside the bottom half of the canvas (the dial opens downward,
 * needle pivots at the bottom-centre).
 */
private fun DrawScope.drawSemicircularGauge(
    bands: List<GaugeBand>,
    tickFractions: List<Float>,
    needleDeg: Float,
    needleColor: Color,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.95f                  // pivot near the bottom
    val rOuter = kotlin.math.min(cx, cy) * 0.95f
    val rInner = rOuter * 0.78f
    val rTickInner = rOuter * 0.86f
    val strokeWidth = (rOuter - rInner)

    // Bounding rect for drawArc. Semicircular arc lives in the upper
    // half of a circle centred at (cx, cy).
    val arcTopLeft = Offset(cx - (rOuter + rInner) / 2f, cy - (rOuter + rInner) / 2f)
    val arcSize = Size((rOuter + rInner), (rOuter + rInner))

    // Coloured bands. Each band's start angle is 180° + frac_start * 180°,
    // sweep = (frac_end - frac_start) * 180°.
    for (band in bands) {
        val start = 180f + band.fromFraction * 180f
        val sweep = (band.toFraction - band.fromFraction) * 180f
        if (sweep <= 0f) continue
        drawArc(
            color = band.color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth),
        )
    }

    // Tick marks.
    val tickColor = Color(0xCCFFFFFF)
    for (frac in tickFractions) {
        val angleRad = Math.toRadians((180.0 + frac * 180.0))
        val tickOuter = rOuter * 0.95f
        val xOuter = cx + (tickOuter * cos(angleRad)).toFloat()
        val yOuter = cy + (tickOuter * sin(angleRad)).toFloat()
        val xInner = cx + (rTickInner * cos(angleRad)).toFloat()
        val yInner = cy + (rTickInner * sin(angleRad)).toFloat()
        drawLine(
            color = tickColor.copy(alpha = 0.7f),
            start = Offset(xInner, yInner),
            end = Offset(xOuter, yOuter),
            strokeWidth = 2f,
        )
    }

    // Centre tick — heavier line at the top, used as the "neutral"
    // reference even when no explicit tickFraction = 0.5 was passed.
    if (0.5f !in tickFractions) {
        val xOuter = cx
        val yOuter = cy - rOuter * 0.95f
        val xInner = cx
        val yInner = cy - rTickInner
        drawLine(
            color = tickColor,
            start = Offset(xInner, yInner),
            end = Offset(xOuter, yOuter),
            strokeWidth = 3f,
        )
    }

    // Needle.
    rotate(degrees = needleDeg, pivot = Offset(cx, cy)) {
        val needleLen = rOuter * 0.92f
        val halfWidth = strokeWidth * 0.25f
        val needlePath = Path().apply {
            moveTo(cx, cy - needleLen)
            lineTo(cx - halfWidth, cy)
            lineTo(cx + halfWidth, cy)
            close()
        }
        drawPath(needlePath, color = needleColor)
    }
    // Pivot dot.
    drawCircle(
        color = needleColor,
        radius = strokeWidth * 0.30f,
        center = Offset(cx, cy),
    )
}

private data class GaugeBand(
    val fromFraction: Float,
    val toFraction: Float,
    val color: Color,
)

@Composable
private fun paceBands(): List<GaugeBand> = listOf(
    GaugeBand(0.00f, 0.375f, SignalRed),
    GaugeBand(0.375f, 0.45f, SignalOrange),
    GaugeBand(0.45f, 0.55f, SignalGreen),
    GaugeBand(0.55f, 0.625f, SignalOrange),
    GaugeBand(0.625f, 1.0f, SignalRed),
)

private fun hrZoneBands(cfg: HrZoneConfig): List<GaugeBand> {
    val z2 = cfg.z2Fraction
    val z3 = cfg.z3Fraction
    val z4 = cfg.z4Fraction
    val z5 = cfg.z5Fraction
    return listOf(
        GaugeBand(0f, z2, zoneColor(1)),
        GaugeBand(z2, z3, zoneColor(2)),
        GaugeBand(z3, z4, zoneColor(3)),
        GaugeBand(z4, z5, zoneColor(4)),
        GaugeBand(z5, 1f, zoneColor(5)),
    )
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> SignalGreen.copy(alpha = 0.55f)
    2 -> SignalGreen
    3 -> SignalYellow
    4 -> SignalOrange
    else -> SignalRed
}

/** Full-scale deviation, in seconds-per-unit. Needle clamps at ±this. */
private const val GAUGE_HALF_RANGE_S = 60f
/** "On target" band — the green wedge in the middle of the dial. */
private const val GAUGE_GREEN_S = 5f
/** "Off but recoverable" band — amber wedges around the green. */
private const val GAUGE_AMBER_S = 15f

private val PACE_TICK_FRACTIONS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

@Composable
private fun Row(
    modifier: Modifier,
    horizontalArrangement: Arrangement.Horizontal,
    verticalAlignment: Alignment.Vertical,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.foundation.layout.Row(
    modifier = modifier,
    horizontalArrangement = horizontalArrangement,
    verticalAlignment = verticalAlignment,
    content = content,
)
