package be.appmire.gpsinfo.ui.gforce

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.GForceSample
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dedicated full-screen G-force view: a "tilted bowl" rendered in 3D.
 *
 * The gauge surface is a paraboloid (a marble bowl) drawn as a
 * wireframe of concentric rings and radial spokes, viewed from a fixed
 * 3/4 perspective. The horizontal acceleration positions a marble in
 * the bowl — lateral on one axis, longitudinal on the other — and the
 * vertical G lifts it off (or presses it into) the bowl floor, with a
 * stalk dropping to the surface point below so the height reads clearly.
 * A fading 3D trail follows the marble.
 *
 * Two things shape the radius:
 *  - a **logarithmic** mapping so small forces are stretched across the
 *    open centre and large ones taper toward the rim, and
 *  - **live auto-ranging**: the rim represents the strongest pull seen in
 *    the last [WINDOW_MS] ms (plus headroom), smoothed so the bowl
 *    rescales gracefully. The current full-scale is printed at the rim.
 *
 * The whole scene shares one orthographic projection [project] in
 * normalised units (rim radius = 1), so the bowl, marble, stalk and
 * trail all sit in the same space regardless of the live scale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GForceScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val sample by vm.gForce.collectAsStateWithLifecycle()

    // Longer history than the card — this is the inspection view.
    val history = remember { ArrayDeque<GForceSample>(TRAIL_LEN) }
    // Time-stamped magnitudes for the rolling-max auto-range window.
    val window = remember { ArrayDeque<Pair<Long, Float>>() }
    // Session peak horizontal magnitude, held until the screen leaves.
    val peak = remember { mutableStateOf(0f) }

    if (history.lastOrNull() != sample) {
        history.addLast(sample)
        while (history.size > TRAIL_LEN) history.removeFirst()
        val now = SystemClock.elapsedRealtime()
        window.addLast(now to sample.horizontalMagnitudeG)
        while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) {
            window.removeFirst()
        }
        peak.value = max(peak.value, sample.horizontalMagnitudeG)
    }

    // Full-scale = strongest recent pull + headroom, never below a floor
    // so a gentle drive still has a sensibly-sized bowl. Animated so the
    // wireframe breathes instead of snapping when a new peak lands.
    val rollingMax = window.maxOfOrNull { it.second } ?: 0f
    val targetScale = max(SCALE_FLOOR, rollingMax * HEADROOM)
    val fullScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 600),
        label = "gforce-scale",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_gforce)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val density = LocalDensity.current
            val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
            val rimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val trail = history.toList()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    // Bias the centre downward so the bowl, which projects
                    // upward, sits visually centred.
                    val cy = size.height * 0.58f
                    val ppu = kotlin.math.min(size.width, size.height) * 0.38f

                    drawBowl(cx, cy, ppu, gridColor, rimColor, labelColor, fullScale, density)
                    drawTrailAndMarble(cx, cy, ppu, trail, fullScale)
                }
            }

            ReadoutRow(sample = sample, peak = peak.value)
        }
    }
}

/**
 * Orthographic 3/4 projection. Operates in normalised units: the bowl
 * rim sits at horizontal radius 1. World axes: x = lateral (right),
 * y = longitudinal (forward, recedes into the scene), z = height (up).
 * The view is rotated [AZIMUTH] about the vertical axis then tilted to
 * [ELEVATION] above the bowl plane; [ppu] is pixels per normalised unit.
 */
private fun project(
    nx: Float,
    ny: Float,
    nz: Float,
    cx: Float,
    cy: Float,
    ppu: Float,
): Offset {
    val xr = nx * COS_AZ - ny * SIN_AZ
    val yr = nx * SIN_AZ + ny * COS_AZ
    val sx = cx + xr * ppu
    // Longitudinal foreshortened by the tilt; height rises up-screen.
    val sy = cy - (yr * SIN_EL + nz * COS_EL) * ppu
    return Offset(sx, sy)
}

/**
 * Logarithmic radius fraction: maps a G magnitude to 0..1 relative to
 * the live [fullScale]. Small forces are exaggerated toward the centre,
 * large ones compressed toward the rim. f(0)=0, f(fullScale)=1.
 */
private fun radiusFrac(magG: Float, fullScale: Float): Float {
    if (fullScale <= 1e-3f) return 0f
    val x = (magG / fullScale).coerceIn(0f, 1f)
    return ln(1f + LOG_K * x) / ln(1f + LOG_K)
}

/** Concave paraboloid wall height for a normalised radius rf in 0..1. */
private fun bowlHeight(rf: Float): Float = BOWL_DEPTH * rf * rf

private fun DrawScope.drawBowl(
    cx: Float,
    cy: Float,
    ppu: Float,
    gridColor: Color,
    rimColor: Color,
    labelColor: Color,
    fullScale: Float,
    density: androidx.compose.ui.unit.Density,
) {
    val steps = 64
    // Concentric rings at evenly-spaced normalised radii, lifted onto
    // the paraboloid wall. (Spacing is in log space — the rings bunch
    // toward the rim because the wall steepens there.)
    for (ring in 1..RINGS) {
        val rf = ring.toFloat() / RINGS
        val z = bowlHeight(rf)
        val path = Path()
        for (i in 0..steps) {
            val a = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
            val p = project(rf * cos(a), rf * sin(a), z, cx, cy, ppu)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(
            path = path,
            color = if (ring == RINGS) rimColor else gridColor,
            style = Stroke(width = if (ring == RINGS) 3f else 1.5f),
        )
    }
    // Radial spokes from centre to rim, following the curve.
    val spokes = 12
    for (s in 0 until spokes) {
        val a = (s.toFloat() / spokes) * (2f * Math.PI.toFloat())
        val path = Path()
        val inner = 8
        for (i in 0..inner) {
            val rf = i.toFloat() / inner
            val p = project(rf * cos(a), rf * sin(a), bowlHeight(rf), cx, cy, ppu)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path = path, color = gridColor, style = Stroke(width = 1f))
    }
    // Centre marker (bottom of the bowl).
    drawCircle(rimColor, 3f, project(0f, 0f, 0f, cx, cy, ppu))

    // Live full-scale label at the far rim — what one rim-width means
    // right now, so the auto-ranging is legible.
    val rimPt = project(0f, 1f, bowlHeight(1f), cx, cy, ppu)
    val paint = android.graphics.Paint().apply {
        color = labelColor.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
        textSize = with(density) { 12.dp.toPx() }
    }
    drawContext.canvas.nativeCanvas.drawText(
        "%.1f G".format(Locale.ROOT, fullScale),
        rimPt.x,
        rimPt.y - with(density) { 6.dp.toPx() },
        paint,
    )
}

/** Project one sample into the normalised bowl space. Returns the point
 *  resting on the wall (no vertical lift) and the lifted marble point. */
private fun sampleWorld(
    s: GForceSample,
    fullScale: Float,
    cx: Float,
    cy: Float,
    ppu: Float,
): Pair<Offset, Offset> {
    val m = s.horizontalMagnitudeG
    val rf = radiusFrac(m, fullScale)
    val ux = if (m > 1e-4f) s.lateralG / m else 0f
    val uy = if (m > 1e-4f) s.longitudinalG / m else 0f
    val nx = ux * rf
    val ny = uy * rf
    val floor = bowlHeight(rf)
    val vLift = sign(s.verticalG) * radiusFrac(abs(s.verticalG), fullScale) * VERT_GAIN
    val onFloor = project(nx, ny, floor, cx, cy, ppu)
    val marble = project(nx, ny, floor + vLift, cx, cy, ppu)
    return onFloor to marble
}

private fun DrawScope.drawTrailAndMarble(
    cx: Float,
    cy: Float,
    ppu: Float,
    trail: List<GForceSample>,
    fullScale: Float,
) {
    val n = trail.size
    if (n == 0) return

    var prev: Offset? = null
    trail.forEachIndexed { i, s ->
        val frac = (i + 1f) / n
        // Gentle fade: sqrt keeps the tail of the trail visible far
        // longer than a linear ramp.
        val f = sqrt(frac)
        val (_, p) = sampleWorld(s, fullScale, cx, cy, ppu)
        if (i < n - 1) {
            prev?.let {
                drawLine(
                    color = MARBLE.copy(alpha = 0.12f + 0.55f * f),
                    start = it,
                    end = p,
                    strokeWidth = 2f + 3f * f,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(MARBLE.copy(alpha = 0.15f + 0.6f * f), 3f + 3f * f, p)
        }
        prev = p
    }

    // The live marble.
    val live = trail.last()
    val (onFloor, marble) = sampleWorld(live, fullScale, cx, cy, ppu)

    // Stalk from the bowl floor up (or down) to the marble — the
    // vertical-G height made visible. Shadow ellipse where it meets.
    drawCircle(Color.Black.copy(alpha = 0.35f), 6f, onFloor)
    drawLine(
        color = MARBLE.copy(alpha = 0.6f),
        start = onFloor,
        end = marble,
        strokeWidth = 2.5f,
        cap = StrokeCap.Round,
    )
    // Marble: white halo + orange core, sized so it reads as a ball.
    drawCircle(Color.White, 13f, marble)
    drawCircle(MARBLE, 10f, marble)
    drawCircle(Color.White.copy(alpha = 0.7f), 3.5f, marble.copy(x = marble.x - 3f, y = marble.y - 3f))
}

@Composable
private fun ReadoutRow(sample: GForceSample, peak: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Readout(stringResource(R.string.gforce_lateral), sample.lateralG)
        Readout(stringResource(R.string.gforce_longitudinal), sample.longitudinalG)
        Readout(stringResource(R.string.gforce_vertical), sample.verticalG)
        Readout(stringResource(R.string.gforce_peak), peak, signed = false)
    }
}

@Composable
private fun Readout(label: String, value: Float, signed: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (signed) {
                "%+.2f".format(Locale.ROOT, value)
            } else {
                "%.2f".format(Locale.ROOT, value)
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val RINGS = 3
private const val TRAIL_LEN = 80
private const val BOWL_DEPTH = 0.85f
private const val LOG_K = 12f

// Auto-range tuning.
private const val WINDOW_MS = 30_000L
private const val SCALE_FLOOR = 0.4f // smallest full-scale, in g
private const val HEADROOM = 1.15f // rim sits 15 % above the recent peak
private const val VERT_GAIN = 0.7f // how far vertical G lifts the marble

private val MARBLE = Color(0xFFE67635)

// Fixed 3/4 view: 28° azimuth, 52° elevation above the bowl plane.
private const val AZIMUTH = 0.488f // 28°
private const val ELEVATION = 0.908f // 52°
private val SIN_AZ = sin(AZIMUTH)
private val COS_AZ = cos(AZIMUTH)
private val SIN_EL = sin(ELEVATION)
private val COS_EL = cos(ELEVATION)
