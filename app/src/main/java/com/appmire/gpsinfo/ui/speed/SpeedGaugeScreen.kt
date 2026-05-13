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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appmire.gpsinfo.ui.components.DialZone
import com.appmire.gpsinfo.ui.components.RetroDial
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.headingToCardinal

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
    val loc = state.gnss.location
    val speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f) ?: 0f
    val altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude?.toInt()
    val headingDeg = state.compass.magneticHeadingDeg.toInt()
    val headingCardinal = headingToCardinal(state.compass.magneticHeadingDeg)
    val accuracyM = loc?.takeIf { it.hasSpeedAccuracy() && android.os.Build.VERSION.SDK_INT >= 26 }
        ?.speedAccuracyMetersPerSecond?.times(3.6f)
    val maxSpeedKmh = state.maxSpeedKmh

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
                title = { Text(if (hudMode) "Speed · HUD" else "Speed") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
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
                            contentDescription = if (hudMode)
                                "HUD mode on — tap to disable"
                            else
                                "Enable HUD (windshield-reflection) mode",
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
                    speedKmh = speedKmh,
                    maxSpeedKmh = maxSpeedKmh,
                    accuracyKmh = accuracyM,
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
                            label = "HEADING",
                            primary = "%03d°".format(headingDeg),
                            secondary = headingCardinal,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCell(
                            label = "ALTITUDE",
                            primary = altitudeM?.let { "$it" } ?: "—",
                            secondary = "m",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    GnssHealthStrip(
                        fix = state.gnss.fix.label,
                        inUse = state.gnss.satellitesInUse,
                        inView = state.gnss.satellitesInView,
                        accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
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
                    speedKmh = speedKmh,
                    maxSpeedKmh = maxSpeedKmh,
                    accuracyKmh = accuracyM,
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
                        label = "HEADING",
                        primary = "%03d°".format(headingDeg),
                        secondary = headingCardinal,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        label = "ALTITUDE",
                        primary = altitudeM?.let { "$it" } ?: "—",
                        secondary = "m",
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(12.dp))

                GnssHealthStrip(
                    fix = state.gnss.fix.label,
                    inUse = state.gnss.satellitesInUse,
                    inView = state.gnss.satellitesInView,
                    accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
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
    speedKmh: Float,
    maxSpeedKmh: Float,
    accuracyKmh: Float?,
    modifier: Modifier = Modifier,
) {
    val (tickStep, labelStep) = chooseTickSteps(maxSpeedKmh)
    Surface(
        modifier = modifier,
        color = Color(0xFF0B0B0B),
        shape = RoundedCornerShape(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            RetroDial(
                valueFraction = speedToFraction(speedKmh, maxSpeedKmh),
                minValue = 0f,
                maxValue = maxSpeedKmh,
                tickStep = tickStep,
                labelStep = labelStep,
                label = "km/h",
                valueToFraction = { v -> speedToFraction(v, maxSpeedKmh) },
                accentTickValues = listOf(30f, 50f, 70f, 90f, 120f),
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
                    text = "%.0f".format(speedKmh),
                    color = Color(0xFF7FE3FF),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                )
                if (accuracyKmh != null && accuracyKmh > 0f) {
                    Text(
                        text = "± %.1f km/h".format(accuracyKmh),
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
) {
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
            InlineStat("Fix", fix)
            InlineStat("Sats", "$inUse/$inView")
            InlineStat("Acc", accuracyM?.let { "±${it.toInt()} m" } ?: "—")
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
 * Piecewise speed → dial-fraction mapping. Lifted from id.dash:
 * dedicate 60 % of the sweep to 0–100 km/h (city / road speeds where
 * resolution matters most), and give the remaining 40 % to 100–[maxSpeed]
 * (motorway and beyond). The 100 km/h break is fixed regardless of where
 * the auto-expanding max lands, so low-speed resolution stays useful even
 * after the dial has grown to 300+ km/h.
 */
private fun speedToFraction(speed: Float, maxSpeed: Float): Float {
    if (maxSpeed <= 0f) return 0f
    val v = speed.coerceIn(0f, maxSpeed)
    return when {
        // If the ceiling has somehow ended up below 100 km/h, fall back to linear.
        maxSpeed <= 100f -> v / maxSpeed
        v <= 100f -> (v / 100f) * 0.6f
        else -> 0.6f + ((v - 100f) / (maxSpeed - 100f)) * 0.4f
    }
}

/** Pick a sensible tick/label step so the dial doesn't get crowded as
 *  the ceiling grows. The numbers were tuned to keep ~25 minor ticks and
 *  ~10 labelled majors across the full sweep. */
private fun chooseTickSteps(maxSpeed: Float): Pair<Float, Float> = when {
    maxSpeed <= 200f -> 10f to 20f
    maxSpeed <= 400f -> 20f to 40f
    maxSpeed <= 700f -> 25f to 50f
    else -> 50f to 100f
}
