package com.appmire.gpsinfo.ui.compass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.headingToCardinal
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Gimballed 3-D compass like a ship's binnacle. The **bowl rim** is fixed
 * to the device; the **card** stays horizontal in world space (gimbal
 * effect) while the phone tilts, and spins so that N always points to
 * magnetic north. The heading is read where the **lubber line** at the
 * top of the bowl meets the card.
 *
 * Hold the phone flat: card sits level, N points north.
 * Tilt the phone forward: card appears to lean back (because *it* stayed
 *   horizontal while your viewpoint tilted with the device).
 * Rotate the phone clockwise: card visibly counter-rotates so N stays
 *   put.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassDetailScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val reading by vm.compass.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_compass)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CompassBowl(reading = reading)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat(stringResource(R.string.metric_hdg), "%.0f° %s".format(
                    reading.magneticHeadingDeg,
                    headingToCardinal(reading.magneticHeadingDeg),
                ))
                Stat(stringResource(R.string.metric_pitch), "%+.0f°".format(reading.pitchDeg))
                Stat(stringResource(R.string.metric_roll), "%+.0f°".format(reading.rollDeg))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat(stringResource(R.string.metric_decl), "%+.1f°".format(Locale.ROOT, reading.declinationDeg))
                Stat(
                    stringResource(R.string.metric_field),
                    stringResource(R.string.unit_microtesla, reading.fieldStrengthNanoTesla),
                )
                Stat(stringResource(R.string.metric_mag_acc), stringResource(reading.accuracy.labelRes))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun CompassBowl(reading: CompassReading) {
    // Smooth the raw sensor — rotation-vector at 50 Hz can jitter a touch
    // around zero pitch. Spring takes the edge off without adding lag.
    // Use the *continuous* (unwrapped) heading so the spring animation never
    // sees a 0°→360° jump — that's what caused the rose to blip when crossing
    // north. The text readout still uses the wrapped value via reading.magneticHeadingDeg.
    val azimuth by animateFloatAsState(
        targetValue = reading.continuousMagneticHeadingDeg,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "az",
    )
    val pitch by animateFloatAsState(
        targetValue = reading.pitchDeg,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pitch",
    )
    val roll by animateFloatAsState(
        targetValue = reading.rollDeg,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "roll",
    )

    val rim = MaterialTheme.colorScheme.surface
    val rimEdge = MaterialTheme.colorScheme.outline
    val aperture = MaterialTheme.colorScheme.background
    val cardBgInner = MaterialTheme.colorScheme.surfaceVariant
    val cardBgOuter = MaterialTheme.colorScheme.surface
    val tickMajor = MaterialTheme.colorScheme.onSurface
    val tickMinor = MaterialTheme.colorScheme.onSurfaceVariant
    val degreeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardinalColor = MaterialTheme.colorScheme.onSurface
    val northColor = com.appmire.gpsinfo.ui.theme.SignalRed
    val lubberColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Bowl rim — fixed to the device.
        BowlRim(rim, rimEdge, aperture, modifier = Modifier.fillMaxSize())

        // Gimballed compass card.
        // rotationX = pitch → leans back as device leans forward.
        // rotationY = roll  → leans the right way as device tilts side-to-side.
        // rotationZ = -azimuth → spins so N always points to magnetic N.
        //
        // Pitch/roll are soft-clamped to ±MAX_TILT_DEG via tanh so the
        // card asymptotes toward (but never reaches) a 90° edge-on flip.
        // Without this, rolling the phone past sideways pushed rotationY
        // beyond 90°, the card flipped over, and the cardinal text read
        // mirrored. tanh keeps small angles ~1:1 (still feels like a
        // physical gimbal) and only kicks in near the limit.
        Box(
            modifier = Modifier
                .fillMaxSize(0.88f)
                .graphicsLayer {
                    rotationX = softClampDeg(pitch, MAX_TILT_DEG)
                    rotationY = softClampDeg(roll, MAX_TILT_DEG)
                    rotationZ = -azimuth
                    cameraDistance = 12f * density
                },
            contentAlignment = Alignment.Center,
        ) {
            CompassCard(
                bgInner = cardBgInner,
                bgOuter = cardBgOuter,
                tickMajor = tickMajor,
                tickMinor = tickMinor,
                degreeColor = degreeColor,
                cardinalColor = cardinalColor,
                northColor = northColor,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Lubber line — fixed at the top of the bowl.
        LubberLine(color = lubberColor, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BowlRim(
    rim: Color,
    rimEdge: Color,
    aperture: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = min(size) / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(rim, rim.copy(alpha = 0.7f)),
                center = Offset(cx, cy),
                radius = outerR,
            ),
            radius = outerR,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = rimEdge,
            radius = outerR * 0.96f,
            center = Offset(cx, cy),
            style = Stroke(width = 2f),
        )
        drawCircle(
            color = aperture,
            radius = outerR * 0.90f,
            center = Offset(cx, cy),
        )
    }
}

@Composable
private fun CompassCard(
    bgInner: Color,
    bgOuter: Color,
    tickMajor: Color,
    tickMinor: Color,
    degreeColor: Color,
    cardinalColor: Color,
    northColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val cardinalStyle = remember {
        TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = cardinalColor)
    }
    val northStyle = remember {
        TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = northColor)
    }
    val degreeStyle = remember {
        TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = degreeColor)
    }
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size) / 2f
        val center = Offset(cx, cy)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(bgInner, bgOuter),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
        drawCircle(
            color = tickMinor.copy(alpha = 0.4f),
            radius = r,
            center = center,
            style = Stroke(width = 1.5f),
        )

        var deg = 0
        while (deg < 360) {
            // 0° on the card = N → on canvas that's "up" (−Y direction).
            // canvasAngle = deg - 90 in degrees, increasing clockwise.
            val a = Math.toRadians((deg - 90).toDouble())
            val cosA = cos(a).toFloat()
            val sinA = sin(a).toFloat()
            val isCardinal = deg % 90 == 0
            val isMajor = deg % 30 == 0
            val outer = r * 0.95f
            val inner = when {
                isCardinal -> r * 0.78f
                isMajor -> r * 0.84f
                else -> r * 0.90f
            }
            val width = when {
                isCardinal -> r * 0.015f
                isMajor -> r * 0.010f
                else -> r * 0.005f
            }
            val color = when {
                deg == 0 -> northColor
                isCardinal -> tickMajor
                isMajor -> tickMajor.copy(alpha = 0.7f)
                else -> tickMinor
            }
            drawLine(
                color = color,
                start = Offset(cx + outer * cosA, cy + outer * sinA),
                end = Offset(cx + inner * cosA, cy + inner * sinA),
                strokeWidth = width,
            )
            deg += 5
        }

        val cardinals = mapOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")
        val degrees = listOf(30, 60, 120, 150, 210, 240, 300, 330)

        for ((d, txt) in cardinals) {
            val a = Math.toRadians((d - 90).toDouble())
            val labelR = r * 0.65f
            val tx = (cx + labelR * cos(a)).toFloat()
            val ty = (cy + labelR * sin(a)).toFloat()
            val style = if (d == 0) northStyle else cardinalStyle
            val layout = measurer.measure(txt, style = style)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    tx - layout.size.width / 2f,
                    ty - layout.size.height / 2f,
                ),
            )
        }
        for (d in degrees) {
            val a = Math.toRadians((d - 90).toDouble())
            val labelR = r * 0.70f
            val tx = (cx + labelR * cos(a)).toFloat()
            val ty = (cy + labelR * sin(a)).toFloat()
            val text = d.toString().padStart(3, '0')
            val layout = measurer.measure(text, style = degreeStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    tx - layout.size.width / 2f,
                    ty - layout.size.height / 2f,
                ),
            )
        }

        // North arrow on the card.
        val arrow = Path().apply {
            moveTo(cx, cy - r * 0.48f)
            lineTo(cx - r * 0.05f, cy - r * 0.30f)
            lineTo(cx + r * 0.05f, cy - r * 0.30f)
            close()
        }
        drawPath(arrow, color = northColor)
        drawCircle(tickMinor, radius = r * 0.04f, center = center)
    }
}

@Composable
private fun LubberLine(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val r = min(size) / 2f
        val tipY = r * 0.06f
        val baseY = r * 0.20f
        val halfW = r * 0.04f
        val path = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - halfW, baseY)
            lineTo(cx + halfW, baseY)
            close()
        }
        drawPath(path, color = color)
    }
}

private fun min(s: Size): Float = max(0f, kotlin.math.min(s.width, s.height))

/** Maximum visual tilt the bowl will apply, in degrees. 75° leaves a 15°
 *  safety margin to 90° so the card never crosses through edge-on, while
 *  still allowing the gimbal to lean dramatically for clear physical feedback. */
private const val MAX_TILT_DEG = 75f

/**
 * Soft-limits a signed angle to ±[limit] degrees using tanh, so:
 *   - small inputs pass through nearly 1:1 (the gimbal still feels physical),
 *   - large inputs asymptote smoothly toward ±limit without a hard stop,
 *   - the output magnitude never reaches `limit`, so we never approach
 *     90° in `rotationX` / `rotationY` and never flip the card.
 */
private fun softClampDeg(deg: Float, limit: Float): Float =
    (limit * kotlin.math.tanh(deg.toDouble() / limit)).toFloat()
