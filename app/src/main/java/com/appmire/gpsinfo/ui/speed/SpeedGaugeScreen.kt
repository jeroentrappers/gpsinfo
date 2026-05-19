package com.appmire.gpsinfo.ui.speed

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.ui.components.DialZone
import com.appmire.gpsinfo.ui.components.RetroDial
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.UnitConverter
import com.appmire.gpsinfo.util.headingToCardinal
import com.appmire.gpsinfo.util.lengthUnitLabel
import com.appmire.gpsinfo.util.speedUnitLabel

/**
 * Full-screen speed gauge using the retro VW-T4-style dial ported from
 * the id.dash project. Reached by tapping the Movement card on the
 * dashboard. Shows GPS-derived speed prominently with heading and altitude
 * read-outs underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedGaugeScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val compass by vm.compass.collectAsStateWithLifecycle()
    val loc = state.gnss.location
    val unit = state.unitSystem
    val speedKmhRaw = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f) ?: 0f
    val altitudeMRaw = loc?.takeIf { it.hasAltitude() }?.altitude
    val headingDeg = compass.magneticHeadingDeg.toInt()
    val headingCardinal = headingToCardinal(compass.magneticHeadingDeg)
    val accuracyKmhRaw = if (android.os.Build.VERSION.SDK_INT >= 26) {
        loc?.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond?.times(3.6f)
    } else null

    val speed = UnitConverter.speedFromKmh(speedKmhRaw, unit)
    val maxSpeed = UnitConverter.speedFromKmh(state.maxSpeedKmh, unit)
    val accuracy = accuracyKmhRaw?.let { UnitConverter.speedFromKmh(it, unit) }
    val altitudeDisplay = altitudeMRaw?.let { UnitConverter.lengthFromMeters(it, unit).toInt() }
    val dialConfig = dialConfigFor(unit)
    val speedUnit = speedUnitLabel(unit)
    val lengthUnit = lengthUnitLabel(unit)

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // HUD ("heads-up display") mode horizontally mirrors the gauge content
    // so that, when the phone is placed face-up on a dashboard, the
    // reflection of the screen seen in the windshield reads correctly.
    // Survives orientation changes via rememberSaveable.
    var hudMode by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(
                        if (hudMode) R.string.screen_speed_hud else R.string.screen_speed
                    ))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    FilledIconToggleButton(
                        checked = hudMode,
                        onCheckedChange = { hudMode = it },
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = if (hudMode)
                                Icons.Outlined.DirectionsCar
                            else
                                Icons.Outlined.FlipToBack,
                            contentDescription = stringResource(
                                if (hudMode) R.string.hud_toggle_on else R.string.hud_toggle_off
                            ),
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
        // HUD modifier: horizontal mirror so the windshield reflection
        // reads correctly. The top app bar is intentionally NOT mirrored
        // so the back arrow and HUD toggle stay tappable in their natural
        // positions when the user picks the phone back up.
        val hudMirror: Modifier =
            if (hudMode) Modifier.graphicsLayer { scaleX = -1f } else Modifier

        if (isLandscape) {
            // In landscape the dial is sized against the available height —
            // without this it would `fillMaxWidth` and overflow the screen.
            // Companion data sits in a column to its right.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(hudMirror),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialHousing(
                    speed = speed,
                    maxSpeed = maxSpeed,
                    accuracy = accuracy,
                    speedUnit = speedUnit,
                    dialConfig = dialConfig,
                    unit = unit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .wrapContentWidth(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCell(
                            label = stringResource(R.string.metric_heading),
                            primary = "%03d°".format(headingDeg),
                            secondary = headingCardinal,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCell(
                            label = stringResource(R.string.metric_altitude),
                            primary = altitudeDisplay?.let { "$it" } ?: stringResource(R.string.placeholder_dash),
                            secondary = lengthUnit,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    GnssHealthStrip(
                        fix = stringResource(state.gnss.fix.labelRes),
                        inUse = state.gnss.satellitesInUse,
                        inView = state.gnss.satellitesInView,
                        accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                        unit = unit,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .then(hudMirror),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DialHousing(
                    speed = speed,
                    maxSpeed = maxSpeed,
                    accuracy = accuracy,
                    speedUnit = speedUnit,
                    dialConfig = dialConfig,
                    unit = unit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCell(
                        label = stringResource(R.string.metric_heading),
                        primary = "%03d°".format(headingDeg),
                        secondary = headingCardinal,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        label = stringResource(R.string.metric_altitude),
                        primary = altitudeDisplay?.let { "$it" } ?: stringResource(R.string.placeholder_dash),
                        secondary = lengthUnit,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))

                GnssHealthStrip(
                    fix = stringResource(state.gnss.fix.labelRes),
                    inUse = state.gnss.satellitesInUse,
                    inView = state.gnss.satellitesInView,
                    accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    unit = unit,
                )
            }
        }
    }
}

/**
 * The dial + its dark rounded-square housing + the digital read-out inside.
 * Pulled out into its own composable so the portrait and landscape layouts
 * can size it differently (fillMaxWidth vs fillMaxHeight) without
 * duplicating the dial configuration.
 */
@Composable
private fun DialHousing(
    speed: Float,
    maxSpeed: Float,
    accuracy: Float?,
    speedUnit: String,
    dialConfig: DialConfig,
    unit: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val (tickStep, labelStep) = chooseTickSteps(maxSpeed, unit)
    val pivot = dialConfig.pivotValue
    Surface(
        modifier = modifier,
        color = Color(0xFF0B0B0B),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            RetroDial(
                valueFraction = speedToFraction(speed, maxSpeed, pivot),
                minValue = 0f,
                maxValue = maxSpeed,
                tickStep = tickStep,
                labelStep = labelStep,
                label = speedUnit,
                valueToFraction = { v -> speedToFraction(v, maxSpeed, pivot) },
                accentTickValues = dialConfig.accentTicks,
                accentTickColor = Color(0xFFE67635),
                needleColor = Color(0xDDFFFFFF),
                modifier = Modifier.fillMaxSize(),
                // Red zone intentionally removed — there's no universally
                // "red" speed (the user could be on a track day or in a
                // 30 km/h zone) and the auto-expanding scale would push it
                // around anyway. Keep the face clean.
                zones = emptyList(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "%.0f".format(speed),
                    color = Color(0xFF7FE3FF),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                )
                if (accuracy != null && accuracy > 0f) {
                    Text(
                        text = "± %.1f %s".format(Locale.ROOT, accuracy, speedUnit),
                        color = Color(0xFF8AA0AA),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun GnssHealthStrip(
    fix: String,
    inUse: Int,
    inView: Int,
    accuracyM: Float?,
    unit: UnitSystem,
) {
    val lengthLabel = lengthUnitLabel(unit)
    val accuracyDisplay = accuracyM?.let { UnitConverter.lengthFromMeters(it, unit) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            InlineStat(stringResource(R.string.metric_fix), fix)
            InlineStat(stringResource(R.string.metric_sats), "$inUse/$inView")
            InlineStat(
                stringResource(R.string.metric_accuracy),
                accuracyDisplay?.let { "±${it.toInt()} $lengthLabel" } ?: stringResource(R.string.placeholder_dash),
            )
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B0B0B))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = label,
                color = Color(0xFF7FCCFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = primary,
                color = Color(0xFFEDEDED),
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = secondary,
                color = Color(0xFF8AA0AA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun InlineStat(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Per-unit dial appearance: the [pivotValue] is the breakpoint where the
 * dial's 60 %/40 % piecewise mapping switches from "low-speed resolution"
 * to "high-speed compression"; [accentTicks] are the orange highlight
 * values rendered onto the face (city / residential / highway-ish
 * speeds in the unit the user picked).
 */
internal data class DialConfig(
    val pivotValue: Float,
    val accentTicks: List<Float>,
)

internal fun dialConfigFor(unit: UnitSystem): DialConfig = when (unit) {
    UnitSystem.Metric -> DialConfig(100f, listOf(30f, 50f, 70f, 90f, 120f))
    UnitSystem.Imperial -> DialConfig(60f, listOf(20f, 35f, 45f, 55f, 75f))
    UnitSystem.Nautical -> DialConfig(40f, listOf(6f, 10f, 20f, 30f, 45f))
}

/**
 * Piecewise speed → dial-fraction mapping. Lifted from id.dash:
 * dedicate 60 % of the sweep to 0–[pivot] (city / road speeds where
 * resolution matters most), and give the remaining 40 % to [pivot]–[maxSpeed]
 * (motorway and beyond). The pivot is fixed regardless of where the
 * auto-expanding max lands, so low-speed resolution stays useful even
 * after the dial has grown well past it.
 *
 * Internal (not private) so unit tests can call it directly.
 */
internal fun speedToFraction(speed: Float, maxSpeed: Float, pivot: Float): Float {
    if (maxSpeed <= 0f) return 0f
    val v = speed.coerceIn(0f, maxSpeed)
    return when {
        // If the ceiling has somehow ended up below the pivot, fall back to linear.
        maxSpeed <= pivot -> v / maxSpeed
        v <= pivot -> (v / pivot) * 0.6f
        else -> 0.6f + ((v - pivot) / (maxSpeed - pivot)) * 0.4f
    }
}

/** Metric-pivot (100 km/h) overload preserved for existing call sites and tests. */
internal fun speedToFraction(speed: Float, maxSpeed: Float): Float =
    speedToFraction(speed, maxSpeed, 100f)

/** Pick a sensible tick/label step so the dial doesn't get crowded as
 *  the ceiling grows. Per-unit-system because the absolute numbers differ
 *  by ~1.6× (imperial) and ~1.85× (nautical) vs metric. Tuned to keep
 *  ~25 minor ticks and ~10 labelled majors across the full sweep. */
internal fun chooseTickSteps(maxSpeed: Float, unit: UnitSystem): Pair<Float, Float> = when (unit) {
    UnitSystem.Metric -> when {
        maxSpeed <= 200f -> 10f to 20f
        maxSpeed <= 400f -> 20f to 40f
        maxSpeed <= 700f -> 25f to 50f
        else -> 50f to 100f
    }
    UnitSystem.Imperial -> when {
        maxSpeed <= 125f -> 5f to 10f
        maxSpeed <= 250f -> 10f to 25f
        maxSpeed <= 450f -> 25f to 50f
        else -> 50f to 100f
    }
    UnitSystem.Nautical -> when {
        maxSpeed <= 110f -> 5f to 10f
        maxSpeed <= 220f -> 10f to 25f
        maxSpeed <= 380f -> 25f to 50f
        else -> 50f to 100f
    }
}

/** Metric overload preserved for existing tests. */
internal fun chooseTickSteps(maxSpeed: Float): Pair<Float, Float> =
    chooseTickSteps(maxSpeed, UnitSystem.Metric)
