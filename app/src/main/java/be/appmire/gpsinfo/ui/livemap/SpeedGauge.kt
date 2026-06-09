package be.appmire.gpsinfo.ui.livemap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.speedUnitLabel
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Glanceable speedometer for the live map: a dark dial whose segmented
 * ring fills with the current ground speed, and a smaller speed-limit
 * roundel pinned to the top-right.
 *
 * The ring is a 270°-arc of discrete blocks (gap at the top, where the
 * limit sign sits). Lit blocks run green → amber as the speed climbs
 * toward the posted limit and turn **red** once a block's threshold is
 * past the limit — so over-limit driving reads as a red gauge with a
 * red speed readout at a glance. With no limit known (not navigating)
 * the lit blocks stay green and the centre number stays neutral.
 *
 * The limit roundel is always shown — EU-style white disc, red ring,
 * black number — falling back to a dash when no limit is known.
 *
 * Speeds are displayed in the active [UnitSystem]; the over-limit test
 * is done in km/h (the source unit of the routing engine's maxspeed)
 * so the colour switch is unit-independent.
 */
@Composable
fun SpeedGauge(
    speedKmh: Float?,
    limitKmh: Int?,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val speed = speedKmh ?: 0f
    val over = limitKmh != null && speed > limitKmh + OVER_TOLERANCE_KMH

    // Scale: keep the limit at ~70% of the dial and let the top end grow
    // if the driver blows past the scale, so the ring never pegs.
    val gaugeMax = maxOf(
        limitKmh?.let { it * 1.4f } ?: 0f,
        speed * 1.1f,
        MIN_SCALE_KMH,
    )
    val frac = (speed / gaugeMax).coerceIn(0f, 1f)

    val diameter = 116.dp
    val signSize = 52.dp
    // Box leaves room at the top-right for the overlapping limit sign.
    Box(
        modifier = modifier.size(
            width = diameter + signSize * 0.45f,
            height = diameter + signSize * 0.45f,
        ),
    ) {
        Box(modifier = Modifier.size(diameter).align(Alignment.BottomStart)) {
            val unlit = SEG_UNLIT
            val green = SEG_GREEN
            val amber = SEG_AMBER
            val red = SEG_RED
            Canvas(modifier = Modifier.fillMaxSize()) {
                val d = size.minDimension
                val c = Offset(size.width / 2f, size.height / 2f)
                val radius = d / 2f
                // Dark dished face.
                drawCircle(
                    brush = Brush.verticalGradient(listOf(DISC_TOP, DISC_BOTTOM)),
                    radius = radius,
                    center = c,
                )
                val litCount = (frac * SEGMENTS).roundToInt()
                val innerR = radius * 0.78f
                val outerR = radius * 0.94f
                val strokeW = d * 0.024f
                for (i in 0 until SEGMENTS) {
                    // i = 0 at the upper-left end of the scale, sweeping
                    // clockwise across the bottom to the upper-right end.
                    val deg = ARC_END_DEG - (i + 0.5f) / SEGMENTS * ARC_SWEEP_DEG
                    val a = Math.toRadians(deg.toDouble())
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    val from = Offset(c.x + innerR * ca, c.y + innerR * sa)
                    val to = Offset(c.x + outerR * ca, c.y + outerR * sa)
                    val repSpeed = (i + 0.5f) / SEGMENTS * gaugeMax
                    val color = if (i >= litCount) {
                        unlit
                    } else if (limitKmh != null && repSpeed > limitKmh) {
                        red
                    } else if (limitKmh != null && repSpeed > limitKmh * 0.85f) {
                        amber
                    } else {
                        green
                    }
                    drawLine(color, from, to, strokeWidth = strokeW, cap = StrokeCap.Round)
                }
            }
            // Centre readout.
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val disp = speedKmh?.let {
                    UnitConverter.speedFromKmh(it, unit).roundToInt().toString()
                } ?: "—"
                Text(
                    text = disp,
                    color = if (over) READOUT_RED else Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                )
                Text(
                    text = speedUnitLabel(unit),
                    color = READOUT_MUTED,
                    fontSize = 11.sp,
                )
            }
        }

        SpeedLimitSign(
            limitKmh = limitKmh,
            unit = unit,
            modifier = Modifier.size(signSize).align(Alignment.TopEnd),
        )
    }
}

/** EU-style posted-limit roundel: white disc, red ring, black number.
 *  Shown even when the limit is unknown (a muted dash), so the slot is
 *  a stable part of the gauge rather than popping in and out. */
@Composable
private fun SpeedLimitSign(
    limitKmh: Int?,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val known = limitKmh != null
    val ring = if (known) SIGN_RING_RED else SIGN_RING_MUTED
    val value = limitKmh?.let {
        UnitConverter.speedFromKmh(it.toFloat(), unit).roundToInt().toString()
    } ?: "—"
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(width = 4.dp, color = ring, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // White face inside the ring.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        ) {
            Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {}
        }
        Text(
            text = value,
            color = if (known) Color.Black else SIGN_RING_MUTED,
            fontWeight = FontWeight.Bold,
            fontSize = if (value.length >= 3) 15.sp else 18.sp,
        )
    }
}

// Arc geometry — y-down screen angles (0° = east, clockwise). The dial
// is a 270° arc with a 90° gap centred on the top, where the sign sits.
private const val SEGMENTS = 36
private const val ARC_END_DEG = 225f
private const val ARC_SWEEP_DEG = 270f
private const val OVER_TOLERANCE_KMH = 2f
private const val MIN_SCALE_KMH = 60f

private val DISC_TOP = Color(0xFF3C4A54)
private val DISC_BOTTOM = Color(0xFF232C32)
private val SEG_UNLIT = Color(0xFF42505A)
private val SEG_GREEN = Color(0xFF43A047)
private val SEG_AMBER = Color(0xFFF9A825)
private val SEG_RED = Color(0xFFE53935)
private val READOUT_RED = Color(0xFFFF5247)
private val READOUT_MUTED = Color(0xFFB8C2CA)
private val SIGN_RING_RED = Color(0xFFD32F2F)
private val SIGN_RING_MUTED = Color(0xFF9AA4AD)
