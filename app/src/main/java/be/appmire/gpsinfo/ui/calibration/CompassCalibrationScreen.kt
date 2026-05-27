package be.appmire.gpsinfo.ui.calibration

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.calibration.CalibrationState
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.MagnetometerSample
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Magnetometer calibration screen.
 *
 * Reachable from:
 *   - The dashboard's calibration banner (tap → here)
 *   - The compass-detail screen's top-bar "Calibrate" action
 *
 * What it shows, top to bottom:
 *   1. Sensor accuracy chip (live)
 *   2. Coverage bar — how much of the orientation sphere has been sampled
 *   3. Three 2D scatter-plot projections (XY / XZ / YZ) of the recent
 *      magnetometer cloud, with the estimated hard-iron offset marked
 *   4. Field magnitude + a "move away from metal" warning if anomalous
 *   5. Numeric readouts (offset components, sphere radius, RMS)
 *   6. Step-by-step instructions
 *
 * What it does NOT do: write the estimated calibration back into the
 * sensor pipeline. Android's `TYPE_ROTATION_VECTOR` already
 * fuses magnetometer + accelerometer + gyro and applies its own runtime
 * calibration; the offset shown here is informational, not a correction
 * we can inject. The user moving the phone is what *actually* updates
 * the OS's calibration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassCalibrationScreen(
    vm: CalibrationViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibration_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::reset) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.calibration_reset),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        CalibrationContent(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

/**
 * Stateless content of the calibration screen — the cards stacked in a
 * scrollable column. Pulled out of [CompassCalibrationScreen] so previews
 * can render the exact same layout without instantiating a VM.
 */
@Composable
internal fun CalibrationContent(
    state: CalibrationUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccuracyChip(accuracy = state.latestAccuracy)
        CoverageCard(calibration = state.calibration)
        ScatterPlotCard(state = state)
        FieldMagnitudeCard(calibration = state.calibration)
        HardIronCard(calibration = state.calibration)
        InstructionsCard()
    }
}

@Composable
private fun AccuracyChip(accuracy: MagneticAccuracy) {
    val (label, bg, fg) = when (accuracy) {
        MagneticAccuracy.HIGH -> Triple(
            stringResource(R.string.mag_acc_high),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        MagneticAccuracy.MEDIUM -> Triple(
            stringResource(R.string.mag_acc_medium),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        MagneticAccuracy.LOW -> Triple(
            stringResource(R.string.mag_acc_low),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        MagneticAccuracy.UNRELIABLE -> Triple(
            stringResource(R.string.mag_acc_unreliable),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        MagneticAccuracy.UNKNOWN -> Triple(
            stringResource(R.string.mag_acc_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.calibration_accuracy_label).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = fg,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = fg,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CoverageCard(calibration: CalibrationState) {
    SectionCard(title = stringResource(R.string.calibration_coverage_label)) {
        Text(
            text = "%d%%".format(Locale.ROOT, (calibration.coverage * 100f).toInt()),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { calibration.coverage },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.calibration_coverage_detail,
                calibration.coveredBins,
                calibration.totalBins,
                calibration.sampleCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ScatterPlotCard(state: CalibrationUiState) {
    SectionCard(title = stringResource(R.string.calibration_scatter_label)) {
        // Three side-by-side projection plots. They share an auto-scale
        // so the offset shift is visually consistent across panels.
        val samples = state.recentSamples
        val scaleUt = remember(samples, state.calibration) {
            scaleForPlot(samples, state.calibration)
        }
        val dotColor = MaterialTheme.colorScheme.primary
        val offsetColor = MaterialTheme.colorScheme.tertiary
        val axisColor = MaterialTheme.colorScheme.outline
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectionPlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.calibration_axis_xy),
                horizontalAxisLabel = "X",
                verticalAxisLabel = "Y",
                horizontal = { it.xMicroTesla },
                vertical = { it.yMicroTesla },
                samples = samples,
                scaleUt = scaleUt,
                offsetH = state.calibration.hardIronOffset.x,
                offsetV = state.calibration.hardIronOffset.y,
                dotColor = dotColor,
                offsetColor = offsetColor,
                axisColor = axisColor,
                labelColor = labelColor,
            )
            ProjectionPlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.calibration_axis_xz),
                horizontalAxisLabel = "X",
                verticalAxisLabel = "Z",
                horizontal = { it.xMicroTesla },
                vertical = { it.zMicroTesla },
                samples = samples,
                scaleUt = scaleUt,
                offsetH = state.calibration.hardIronOffset.x,
                offsetV = state.calibration.hardIronOffset.z,
                dotColor = dotColor,
                offsetColor = offsetColor,
                axisColor = axisColor,
                labelColor = labelColor,
            )
            ProjectionPlot(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.calibration_axis_yz),
                horizontalAxisLabel = "Y",
                verticalAxisLabel = "Z",
                horizontal = { it.yMicroTesla },
                vertical = { it.zMicroTesla },
                samples = samples,
                scaleUt = scaleUt,
                offsetH = state.calibration.hardIronOffset.y,
                offsetV = state.calibration.hardIronOffset.z,
                dotColor = dotColor,
                offsetColor = offsetColor,
                axisColor = axisColor,
                labelColor = labelColor,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.calibration_scatter_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectionPlot(
    modifier: Modifier,
    label: String,
    horizontalAxisLabel: String,
    verticalAxisLabel: String,
    horizontal: (MagnetometerSample) -> Float,
    vertical: (MagnetometerSample) -> Float,
    samples: List<MagnetometerSample>,
    scaleUt: Float,
    offsetH: Float,
    offsetV: Float,
    dotColor: Color,
    offsetColor: Color,
    axisColor: Color,
    labelColor: Color,
) {
    val plotDesc = stringResource(R.string.a11y_calibration_plot, label, offsetH, offsetV)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics(mergeDescendants = true) { contentDescription = plotDesc },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawProjection(
                    samples = samples,
                    horizontal = horizontal,
                    vertical = vertical,
                    scaleUt = scaleUt,
                    offsetH = offsetH,
                    offsetV = offsetV,
                    dotColor = dotColor,
                    offsetColor = offsetColor,
                    axisColor = axisColor,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun DrawScope.drawProjection(
    samples: List<MagnetometerSample>,
    horizontal: (MagnetometerSample) -> Float,
    vertical: (MagnetometerSample) -> Float,
    scaleUt: Float,
    offsetH: Float,
    offsetV: Float,
    dotColor: Color,
    offsetColor: Color,
    axisColor: Color,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val r = min(w, h) / 2f * 0.92f
    // Background frame so the plot reads as a discrete card panel.
    drawRect(
        color = axisColor.copy(alpha = 0.18f),
        size = Size(w, h),
        style = Stroke(width = 1f),
    )
    // Axis cross at the geometric centre of the plot (corresponds to
    // µT=0 along each axis; the offset marker is drawn relative to this).
    drawLine(axisColor.copy(alpha = 0.45f), Offset(0f, cy), Offset(w, cy), strokeWidth = 1f)
    drawLine(axisColor.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f)
    if (scaleUt <= 0f) return
    val ut2px = r / scaleUt
    for (s in samples) {
        val px = cx + horizontal(s) * ut2px
        // Flip Y because canvas Y grows downward; positive µT should be "up".
        val py = cy - vertical(s) * ut2px
        if (px in 0f..w && py in 0f..h) {
            drawCircle(
                color = dotColor.copy(alpha = 0.65f),
                radius = 2.4f,
                center = Offset(px, py),
            )
        }
    }
    // Hard-iron offset marker.
    val ox = cx + offsetH * ut2px
    val oy = cy - offsetV * ut2px
    if (ox in 0f..w && oy in 0f..h) {
        drawCircle(color = offsetColor, radius = 6f, center = Offset(ox, oy))
        drawCircle(
            color = offsetColor,
            radius = 9f,
            center = Offset(ox, oy),
            style = Stroke(width = 2f),
        )
    }
}

@Composable
private fun FieldMagnitudeCard(calibration: CalibrationState) {
    SectionCard(title = stringResource(R.string.calibration_field_label)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "%.1f".format(Locale.ROOT, calibration.fieldMagnitudeUt),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "µT",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (calibration.fieldMagnitudeAnomalous) {
                stringResource(R.string.calibration_field_warning)
            } else {
                stringResource(R.string.calibration_field_normal)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (calibration.fieldMagnitudeAnomalous) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun HardIronCard(calibration: CalibrationState) {
    SectionCard(title = stringResource(R.string.calibration_hardiron_label)) {
        Text(
            text = "X = %+.1f µT\nY = %+.1f µT\nZ = %+.1f µT".format(
                Locale.ROOT,
                calibration.hardIronOffset.x,
                calibration.hardIronOffset.y,
                calibration.hardIronOffset.z,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.calibration_hardiron_detail,
                calibration.sphereRadiusUt,
                calibration.rmsErrorUt,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun InstructionsCard() {
    SectionCard(title = stringResource(R.string.calibration_how_label)) {
        Text(
            text = stringResource(R.string.calibration_how_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Pick a single µT scale for all three projection plots so the offset
 * shift reads consistently across them. We take the larger of:
 *   - the absolute max sample component, with a small padding
 *   - the offset magnitude (so the offset marker is always visible)
 *   - a 30 µT floor (so an empty buffer doesn't draw an empty plot)
 */
internal fun scaleForPlot(samples: List<MagnetometerSample>, c: CalibrationState): Float {
    var m = 30f
    for (s in samples) {
        m = max(m, max(kotlin.math.abs(s.xMicroTesla), max(kotlin.math.abs(s.yMicroTesla), kotlin.math.abs(s.zMicroTesla))))
    }
    val offsetMag = max(
        kotlin.math.abs(c.hardIronOffset.x),
        max(kotlin.math.abs(c.hardIronOffset.y), kotlin.math.abs(c.hardIronOffset.z)),
    )
    return max(m, offsetMag) * 1.15f
}
