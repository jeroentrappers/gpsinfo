package be.appmire.gpsinfo.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.North
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.DashboardProfile
import be.appmire.gpsinfo.data.model.HeartRateState
import be.appmire.gpsinfo.data.model.NavigationTarget
import be.appmire.gpsinfo.data.model.SunInfo
import be.appmire.gpsinfo.ui.components.AutoSizingText
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import be.appmire.gpsinfo.util.CoordinateFormat
import be.appmire.gpsinfo.util.CoordinateFormatter
import be.appmire.gpsinfo.util.FormattedCoord
import be.appmire.gpsinfo.util.HeadingMode
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.headingToCardinal
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Per-persona bespoke dashboards rendered like high-end watch faces:
 *
 *  - One outer card per persona (no nested chrome).
 *  - A clearly-prioritised hero metric in display typography, drawn
 *    in the profile's [DashboardProfile.accentArgb] colour.
 *  - Secondary metrics arranged in a row separated by hairline
 *    dividers, in subdued onSurface / onSurfaceVariant.
 *  - Tertiary detail in small monospace at the foot.
 *
 * The dispatch in [DashboardScreen] picks one of these per
 * `DashboardProfile.id`. `Default` and `Custom` still go through the
 * legacy scrolling LazyColumn.
 */

fun isBespokeLayout(profileId: String): Boolean = profileId in BESPOKE_IDS

private val BESPOKE_IDS = setOf(
    "runner", "cyclist", "hiker", "sailor",
    "motorcyclist", "geocacher", "ham",
)

@Composable
fun RenderBespokeLayout(
    profile: DashboardProfile,
    vm: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val compass by vm.compass.collectAsStateWithLifecycle()
    val hrState by vm.hrState.collectAsStateWithLifecycle()
    val cpState by vm.cpState.collectAsStateWithLifecycle()
    val recording by vm.recordingState.collectAsStateWithLifecycle()
    val navigationTarget by vm.navigationTarget.collectAsStateWithLifecycle()
    val ghostGap by vm.ghostGap.collectAsStateWithLifecycle()
    val headingMode by vm.headingMode.collectAsStateWithLifecycle()

    val loc = state.gnss.location
    val speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
    val course = if (headingMode == HeadingMode.DualWithCourse) {
        loc?.takeIf { it.hasBearing() }?.bearing
    } else null

    val accent = profile.accentArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = modifier) {
        // Pick the layout shape from the actual area we were handed
        // rather than a global window class — that keeps the face
        // correct inside a foldable's split pane or a multi-window
        // tile, not just full-screen.
        val ratio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
        val minSideDp = minOf(maxWidth.value, maxHeight.value)
        val shape = BespokeShape(
            // Landscape-ish: clearly wider than tall. The two-panel
            // hero-left / detail-right layout reads far better here than
            // a vertically-squished stack.
            wide = ratio >= 1.25f,
            // Tablet-scale: give the hero and stats room to breathe.
            large = minSideDp >= 600f,
        )

        WatchFaceFrame(shape = shape) {
            when (profile.id) {
                "runner" -> RunnerFace(
                    shape = shape,
                    recording = recording,
                    speedKmh = speedKmh,
                    hrState = hrState,
                    navigationTarget = navigationTarget,
                    ghostGap = ghostGap,
                    position = loc?.let { it.latitude to it.longitude },
                    course = course,
                    magneticHeadingDeg = compass.magneticHeadingDeg,
                    unit = state.unitSystem,
                    accent = accent,
                )
                "cyclist" -> CyclistFace(
                    shape = shape,
                    speedKmh = speedKmh,
                    course = course,
                    magneticHeadingDeg = compass.magneticHeadingDeg,
                    hrState = hrState,
                    cpState = cpState,
                    altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude,
                    unit = state.unitSystem,
                    accent = accent,
                )
                "hiker" -> HikerFace(
                    shape = shape,
                    position = loc?.let { it.latitude to it.longitude },
                    altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude,
                    accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    compass = compass,
                    course = course,
                    sun = state.sun,
                    nowMillis = state.nowMillis,
                    satsInUse = state.gnss.satellitesInUse,
                    unit = state.unitSystem,
                    accent = accent,
                )
                "sailor" -> SailorFace(
                    shape = shape,
                    speedKmh = speedKmh,
                    course = course,
                    compass = compass,
                    position = loc?.let { it.latitude to it.longitude },
                    nowMillis = state.nowMillis,
                    unit = state.unitSystem,
                    accent = accent,
                )
                "motorcyclist" -> MotorcyclistFace(
                    shape = shape,
                    speedKmh = speedKmh,
                    course = course,
                    magneticHeadingDeg = compass.magneticHeadingDeg,
                    altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude,
                    position = loc?.let { it.latitude to it.longitude },
                    unit = state.unitSystem,
                    accent = accent,
                )
                "geocacher" -> GeocacherFace(
                    shape = shape,
                    position = loc?.let { it.latitude to it.longitude },
                    navigationTarget = navigationTarget,
                    magneticHeadingDeg = compass.magneticHeadingDeg,
                    course = course,
                    satsInUse = state.gnss.satellitesInUse,
                    accuracyM = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    unit = state.unitSystem,
                    accent = accent,
                )
                "ham" -> HamFace(
                    shape = shape,
                    position = loc?.let { it.latitude to it.longitude },
                    altitudeM = loc?.takeIf { it.hasAltitude() }?.altitude,
                    nowMillis = state.nowMillis,
                    declinationDeg = compass.declinationDeg,
                    satsInUse = state.gnss.satellitesInUse,
                    unit = state.unitSystem,
                    accent = accent,
                )
            }
        }
    }
}

// ---------- Frame + typography vocabulary ---------- //

/** One outer card per persona — clean rounded canvas, no inner chrome.
 *  Subtle vertical gradient gives the surface a little visual depth
 *  without the boxed "settings card" feel. */
@Composable
private fun WatchFaceFrame(
    shape: BespokeShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val baseColor = MaterialTheme.colorScheme.surface
    val brush = Brush.verticalGradient(
        colors = listOf(
            baseColor,
            baseColor.copy(alpha = 0.96f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    )
    // A touch more inset + a softer corner on large canvases so the
    // face reads as a composed panel, not a stretched phone card.
    val outerPadding = if (shape.large) 20.dp else 12.dp
    val corner = RoundedCornerShape(if (shape.large) 36.dp else 28.dp)
    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(outerPadding),
        color = Color.Transparent,
        shape = corner,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(1.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                shape = corner,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(brush = brush, shape = corner),
                ) { content() }
            }
        }
    }
}

/** Tiny uppercase label, tracked out. Watch-face caption style. */
@Composable
private fun HeroLabel(
    text: String,
    modifier: Modifier = Modifier,
    colorOverride: Color? = null,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = colorOverride ?: MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The display-typography hero number — single line, autosized so the
 *  value never wraps regardless of width. */
@Composable
private fun HeroValue(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier,
    maxSp: TextUnit = 96.sp,
    minSp: TextUnit = 28.sp,
) {
    AutoSizingText(
        text = text,
        modifier = modifier,
        maxFontSize = maxSp,
        minFontSize = minSp,
        color = accent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Animated integer hero value — spring-transitions between updates so
 * the number ticks the way a high-end watch face does instead of
 * teleporting on every recompose. [target] is null when there's no
 * fix; that renders as the placeholder string [emptyText] without
 * animation (no spring from 0 → live value when the GNSS first
 * acquires).
 */
@Composable
private fun AnimatedHeroInt(
    target: Float?,
    accent: Color,
    suffix: String = "",
    emptyText: String = "—",
    modifier: Modifier = Modifier,
    maxSp: TextUnit = 96.sp,
    minSp: TextUnit = 28.sp,
) {
    if (target == null) {
        HeroValue(emptyText, accent, modifier, maxSp, minSp)
        return
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "heroInt",
    )
    HeroValue(
        text = "%.0f%s".format(Locale.ROOT, animated, suffix),
        accent = accent,
        modifier = modifier,
        maxSp = maxSp,
        minSp = minSp,
    )
}

/** Same as [AnimatedHeroInt] but one decimal place — used by Sailor's
 *  SOG ("12.4 kn"). */
@Composable
private fun AnimatedHeroDecimal(
    target: Float?,
    accent: Color,
    emptyText: String = "—",
    modifier: Modifier = Modifier,
    maxSp: TextUnit = 96.sp,
    minSp: TextUnit = 28.sp,
) {
    if (target == null) {
        HeroValue(emptyText, accent, modifier, maxSp, minSp)
        return
    }
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "heroDecimal",
    )
    HeroValue(
        text = "%.1f".format(Locale.ROOT, animated),
        accent = accent,
        modifier = modifier,
        maxSp = maxSp,
        minSp = minSp,
    )
}

/** Small footer-row stat: label up, value below, both centred. */
@Composable
private fun SecondaryStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueSp: TextUnit = 22.sp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroLabel(label)
        AutoSizingText(
            text = value,
            maxFontSize = valueSp,
            minFontSize = 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Hairline horizontal rule used as a watch-face divider between
 *  zones. Inset from the edges to keep the border quiet. */
@Composable
private fun WatchDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 0.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}

@Composable
private fun WatchVerticalDivider(modifier: Modifier = Modifier) {
    VerticalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    )
}

/** Row of [SecondaryStat]s separated by vertical hairlines. */
@Composable
private fun SecondaryRow(
    stats: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    accentForFirst: Color? = null,
    valueSp: TextUnit = 22.sp,
    dividerHeight: androidx.compose.ui.unit.Dp = 34.dp,
    verticalPadding: androidx.compose.ui.unit.Dp = 16.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { idx, (label, value) ->
            SecondaryStat(
                label = label,
                value = value,
                modifier = Modifier.weight(1f),
                valueColor = if (idx == 0 && accentForFirst != null) accentForFirst
                else MaterialTheme.colorScheme.onSurface,
                valueSp = valueSp,
            )
            if (idx < stats.size - 1) {
                WatchVerticalDivider(modifier = Modifier.height(dividerHeight))
            }
        }
    }
}

// ---------- Responsive shape + scaffold ---------- //

/**
 * How the current viewport wants a persona laid out. Computed once per
 * frame in [RenderBespokeLayout] from the actual area we're given.
 *
 *  - [wide]  : viewport is clearly landscape (ratio ≥ 1.25). We split
 *              into a hero-left / detail-right two-panel layout instead
 *              of a vertically-squished stack.
 *  - [large] : viewport's short side ≥ 600 dp — a tablet or an unfolded
 *              foldable. We scale the hero typography, dials and stat
 *              text up so the face feels composed for the canvas rather
 *              than a blown-up phone screen.
 */
private data class BespokeShape(
    val wide: Boolean,
    val large: Boolean,
) {
    /** Multiplier applied to hero / dial / stat sizing on large
     *  screens. Phones stay at 1.0. */
    val scale: Float get() = if (large) 1.35f else 1f
}

/** Scale a sp size by the shape's [BespokeShape.scale]. */
private fun TextUnit.scaled(shape: BespokeShape): TextUnit = (this.value * shape.scale).sp

/** Scale a dp size by the shape's [BespokeShape.scale]. */
private fun androidx.compose.ui.unit.Dp.scaled(shape: BespokeShape): androidx.compose.ui.unit.Dp =
    (this.value * shape.scale).dp

/**
 * The shared two-zone watch-face scaffold every persona builds on.
 *
 * Portrait / tall: a centred hero takes the upper region (weighted by
 * [heroWeight]) and the detail [zones] stack beneath, each separated
 * from the previous by a hairline rule — the original watch-face look.
 *
 * Landscape / wide: the hero occupies the left panel, vertically
 * centred, with the detail zones stacked in a right panel of roughly
 * equal width, divided by a vertical hairline. This keeps the hero
 * large and legible instead of letting a short landscape height crush
 * it, and uses the extra horizontal room productively.
 */
@Composable
private fun PersonaScaffold(
    shape: BespokeShape,
    hero: @Composable () -> Unit,
    zones: List<@Composable () -> Unit>,
    heroWeight: Float = 1f,
) {
    if (shape.wide) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1.05f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) { hero() }
            WatchVerticalDivider(modifier = Modifier.fillMaxHeight(0.72f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                zones.forEachIndexed { idx, zone ->
                    if (idx > 0) WatchDivider()
                    zone()
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(heroWeight),
                contentAlignment = Alignment.Center,
            ) { hero() }
            zones.forEach { zone ->
                WatchDivider()
                zone()
            }
        }
    }
}

/** Standard hero block: tracked-out label above an autosizing value,
 *  optionally with a small unit subtitle below. Centred. */
@Composable
private fun HeroBlock(
    label: String,
    accent: Color,
    shape: BespokeShape,
    value: @Composable () -> Unit,
    subtitle: String? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        if (label.isNotBlank()) {
            HeroLabel(label)
            Spacer(Modifier.height(if (shape.large) 6.dp else 2.dp))
        }
        value()
        if (subtitle != null) {
            Text(
                subtitle,
                style = if (shape.large) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Runner ---------- //

private enum class RunnerHero { Ghost, Steer, Pace, Elapsed }

@Composable
private fun RunnerFace(
    shape: BespokeShape,
    recording: be.appmire.gpsinfo.data.RecordingState,
    speedKmh: Float?,
    hrState: HeartRateState,
    navigationTarget: NavigationTarget?,
    ghostGap: be.appmire.gpsinfo.data.model.GhostGap?,
    position: Pair<Double, Double>?,
    course: Float?,
    magneticHeadingDeg: Float,
    unit: UnitSystem,
    accent: Color,
) {
    val rec = recording as? be.appmire.gpsinfo.data.RecordingState.Recording
    val elapsedSec = rec?.let { (System.currentTimeMillis() - it.startedAtMillis) / 1000L } ?: 0L
    val distanceM = rec?.distanceMetres ?: 0.0
    val currentPace = be.appmire.gpsinfo.util.paceSecondsPerUnit(speedKmh, unit)
    val conn = hrState as? HeartRateState.Connected
    val targetPace = navigationTarget?.effectiveTargetPaceSecondsPerUnit(unit)

    val onTrack = be.appmire.gpsinfo.ui.theme.SignalGreen
    val behind = be.appmire.gpsinfo.ui.theme.SignalRed
    val paceColor = when {
        currentPace == null || targetPace == null -> MaterialTheme.colorScheme.onSurface
        currentPace <= targetPace + 3f -> onTrack
        else -> behind
    }
    val paceText = currentPace?.let { formatPaceMmSs(it) } ?: "—"

    // Steering data — only when a destination is set.
    val hasNav = navigationTarget != null && position != null
    val relBearing: Float
    val distToTargetM: Double
    if (hasNav) {
        val abs = be.appmire.gpsinfo.util.NavigationMath.bearingDegrees(
            position!!.first, position.second,
            navigationTarget!!.targetLatDeg, navigationTarget.targetLonDeg,
        )
        relBearing = be.appmire.gpsinfo.util.NavigationMath
            .relativeBearingDegrees(abs, course ?: magneticHeadingDeg).toFloat()
        distToTargetM = be.appmire.gpsinfo.util.NavigationMath.distanceMetres(
            position.first, position.second,
            navigationTarget.targetLatDeg, navigationTarget.targetLonDeg,
        )
    } else {
        relBearing = 0f
        distToTargetM = 0.0
    }

    // Hero priority: race the ghost when one is live; else steer to a
    // destination; else show the pace-vs-target gap; else elapsed.
    val heroKind = when {
        ghostGap != null -> RunnerHero.Ghost
        hasNav -> RunnerHero.Steer
        targetPace != null -> RunnerHero.Pace
        else -> RunnerHero.Elapsed
    }
    val ghostColor = if ((ghostGap?.aheadSeconds ?: 0.0) >= 0.0) onTrack else behind

    val hero: @Composable () -> Unit = when (heroKind) {
        RunnerHero.Ghost -> {
            {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HeroLabel(stringResource(R.string.runner_ghost_label))
                    Spacer(Modifier.height(if (shape.large) 6.dp else 2.dp))
                    HeroValue(
                        formatSignedGapTime(ghostGap!!.aheadSeconds),
                        ghostColor,
                        maxSp = 96.sp.scaled(shape),
                    )
                    Text(
                        text = formatSignedGapDistance(ghostGap.aheadMeters, unit),
                        style = if (shape.large) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        color = ghostColor,
                    )
                    if (ghostGap.ghostFinished) {
                        Text(
                            text = stringResource(R.string.runner_ghost_finished),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        RunnerHero.Steer -> {
            {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HeroLabel(navigationTarget!!.displayName)
                    Spacer(Modifier.height(10.dp))
                    BearingDial(headingDeg = relBearing, accent = accent, size = 120.dp.scaled(shape))
                    Spacer(Modifier.height(10.dp))
                    HeroValue(formatDistanceCompact(distToTargetM, unit), accent, maxSp = 52.sp.scaled(shape))
                }
            }
        }
        RunnerHero.Pace -> {
            {
                HeroBlock(
                    label = stringResource(R.string.metric_pace),
                    accent = paceColor,
                    shape = shape,
                    value = { HeroValue(paceText, paceColor, maxSp = 96.sp.scaled(shape)) },
                    subtitle = stringResource(
                        R.string.persona_runner_target_pace,
                        formatPaceMmSs(targetPace!!),
                    ),
                )
            }
        }
        RunnerHero.Elapsed -> {
            {
                HeroBlock(
                    label = stringResource(R.string.metric_elapsed),
                    accent = accent,
                    shape = shape,
                    value = { HeroValue(formatHms(elapsedSec), accent, maxSp = 80.sp.scaled(shape)) },
                )
            }
        }
    }

    // Secondary row, chosen so it never just repeats the hero metric.
    val secondaryStats: List<Pair<String, String>> = when (heroKind) {
        RunnerHero.Steer -> listOf(
            stringResource(R.string.metric_pace) to paceText,
            stringResource(R.string.metric_elapsed) to formatHms(elapsedSec),
            stringResource(R.string.metric_heart_rate) to (conn?.lastBpm?.toString() ?: "—"),
        )
        RunnerHero.Pace -> listOf(
            stringResource(R.string.metric_distance) to formatDistanceCompact(distanceM, unit),
            stringResource(R.string.metric_elapsed) to formatHms(elapsedSec),
            stringResource(R.string.metric_heart_rate) to (conn?.lastBpm?.toString() ?: "—"),
        )
        else -> listOf(
            stringResource(R.string.metric_distance) to formatDistanceCompact(distanceM, unit),
            stringResource(R.string.metric_pace) to paceText,
            stringResource(R.string.metric_heart_rate) to (conn?.lastBpm?.toString() ?: "—"),
        )
    }

    PersonaScaffold(
        shape = shape,
        hero = hero,
        zones = buildList {
            // When racing a ghost AND steering to a waypoint, surface
            // the waypoint as its own strip so both stay visible.
            if (heroKind == RunnerHero.Ghost && hasNav) {
                add {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        BearingDial(headingDeg = relBearing, accent = accent, size = 52.dp.scaled(shape))
                        Spacer(Modifier.size(12.dp))
                        Column {
                            HeroLabel(navigationTarget!!.displayName)
                            Text(
                                text = formatDistanceCompact(distToTargetM, unit),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            add {
                SecondaryRow(
                    stats = secondaryStats,
                    valueSp = 22.sp.scaled(shape),
                )
            }
            add {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TertiaryChip(
                        stringResource(R.string.metric_cadence),
                        rec?.cadenceSpm?.let { "%.0f".format(it) } ?: "—",
                    )
                    TertiaryChip(
                        stringResource(R.string.metric_stride),
                        rec?.strideMetres?.let { "%.2f m".format(Locale.ROOT, it) } ?: "—",
                    )
                }
            }
        },
    )
}

/** Signed time gap to the ghost: "+0:18" ahead, "−1:23" behind. */
private fun formatSignedGapTime(aheadSeconds: Double): String {
    val sign = if (aheadSeconds >= 0.0) "+" else "−"
    val abs = kotlin.math.abs(aheadSeconds).toLong()
    val m = abs / 60
    val s = abs % 60
    return "%s%d:%02d".format(Locale.ROOT, sign, m, s)
}

/** Signed distance gap to the ghost in the user's unit. */
private fun formatSignedGapDistance(aheadMeters: Double, unit: UnitSystem): String {
    val sign = if (aheadMeters >= 0.0) "+" else "−"
    val absM = kotlin.math.abs(aheadMeters)
    return if (absM < 1_000.0) {
        "%s%d m".format(Locale.ROOT, sign, absM.toInt())
    } else {
        "%s%.2f km".format(Locale.ROOT, sign, absM / 1_000.0)
    }
}

// ---------- Cyclist ---------- //

@Composable
private fun CyclistFace(
    shape: BespokeShape,
    speedKmh: Float?,
    course: Float?,
    magneticHeadingDeg: Float,
    hrState: HeartRateState,
    cpState: be.appmire.gpsinfo.data.model.CyclingPowerState,
    altitudeM: Double?,
    unit: UnitSystem,
    accent: Color,
) {
    val hrConn = hrState as? HeartRateState.Connected
    val cpConn = cpState as? be.appmire.gpsinfo.data.model.CyclingPowerState.Connected
    // Power belongs alongside HR on a cyclist's dashboard — both come
    // from BLE peripherals, both are "now" metrics. We show power only
    // when a meter is connected; the row collapses back to three
    // columns otherwise so the un-paired user doesn't stare at a dash.
    val stats = buildList {
        add(stringResource(R.string.metric_heart_rate) to (hrConn?.lastBpm?.toString() ?: "—"))
        if (cpConn != null) {
            add(stringResource(R.string.metric_power) to "${cpConn.lastWatts ?: 0} W")
        }
        add(
            stringResource(R.string.metric_heading) to "%03d°".format(
                Locale.ROOT, (course ?: magneticHeadingDeg).toInt(),
            ),
        )
        add(
            stringResource(R.string.metric_altitude) to (
                altitudeM?.let {
                    "%d %s".format(
                        Locale.ROOT,
                        UnitConverter.lengthFromMeters(it, unit).toInt(),
                        lengthUnitLabel(unit),
                    )
                } ?: "—"
            ),
        )
    }
    PersonaScaffold(
        shape = shape,
        hero = {
            HeroBlock(
                label = stringResource(R.string.metric_speed),
                accent = accent,
                shape = shape,
                value = {
                    AnimatedHeroInt(
                        target = speedKmh?.let { UnitConverter.speedFromKmh(it, unit) },
                        accent = accent,
                        maxSp = 140.sp.scaled(shape),
                    )
                },
                subtitle = speedUnitLabel(unit),
            )
        },
        zones = listOf(
            { SecondaryRow(stats = stats, valueSp = 22.sp.scaled(shape)) },
        ),
    )
}

// ---------- Hiker ---------- //

@Composable
private fun HikerFace(
    shape: BespokeShape,
    position: Pair<Double, Double>?,
    altitudeM: Double?,
    accuracyM: Float?,
    compass: CompassReading,
    course: Float?,
    sun: SunInfo?,
    nowMillis: Long,
    satsInUse: Int,
    unit: UnitSystem,
    accent: Color,
) {
    val sunsetMs = sun?.sunsetEpochMillis
    val countdown = if (sunsetMs != null && sunsetMs > nowMillis) {
        val secs = (sunsetMs - nowMillis) / 1000L
        val h = secs / 3600L
        val m = (secs % 3600L) / 60L
        "%dh %02dm".format(Locale.ROOT, h, m)
    } else "—"
    val sunsetAtStr = sunsetMs?.let {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
    }

    PersonaScaffold(
        shape = shape,
        hero = {
            HeroBlock(
                label = stringResource(R.string.persona_hiker_until_sunset),
                accent = accent,
                shape = shape,
                value = { HeroValue(countdown, accent, maxSp = 84.sp.scaled(shape)) },
                subtitle = sunsetAtStr?.let {
                    stringResource(R.string.persona_hiker_sunset_at, it)
                },
            )
        },
        zones = listOf(
            {
                SecondaryRow(
                    stats = listOf(
                        stringResource(R.string.metric_altitude) to (
                            altitudeM?.let {
                                "%d %s".format(
                                    Locale.ROOT,
                                    UnitConverter.lengthFromMeters(it, unit).toInt(),
                                    lengthUnitLabel(unit),
                                )
                            } ?: "—"
                        ),
                        stringResource(R.string.compass_card_heading)
                            to "%03d°".format(Locale.ROOT, compass.magneticHeadingDeg.toInt()),
                        stringResource(R.string.compass_card_course)
                            to (course?.let { "%03d°".format(Locale.ROOT, it.toInt()) } ?: "—"),
                    ),
                    valueSp = 22.sp.scaled(shape),
                )
            },
            {
                PositionFooter(
                    position = position,
                    extra = "±%.0f m · %d sats".format(
                        Locale.ROOT,
                        accuracyM ?: 0f,
                        satsInUse,
                    ),
                )
            },
        ),
    )
}

// ---------- Sailor ---------- //

@Composable
private fun SailorFace(
    shape: BespokeShape,
    speedKmh: Float?,
    course: Float?,
    compass: CompassReading,
    position: Pair<Double, Double>?,
    nowMillis: Long,
    unit: UnitSystem,
    accent: Color,
) {
    // SOG is always knots — the universal marine speed unit — with the
    // user's configured unit (km/h or mph) shown beneath as a cross-
    // check. When the user already picked Nautical, the secondary line
    // falls back to km/h so it isn't a duplicate knots readout.
    val knots = speedKmh?.let { UnitConverter.speedFromKmh(it, UnitSystem.Nautical) }
    val secondaryUnit = if (unit == UnitSystem.Nautical) UnitSystem.Metric else unit
    val secondaryVal = speedKmh?.let { UnitConverter.speedFromKmh(it, secondaryUnit) }
    val secondaryLabel = speedUnitLabel(secondaryUnit)
    val cogValue = course ?: compass.magneticHeadingDeg
    PersonaScaffold(
        shape = shape,
        heroWeight = 1.1f,
        hero = {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HeroLabel(stringResource(R.string.persona_sailor_sog))
                Spacer(Modifier.height(if (shape.large) 6.dp else 2.dp))
                AnimatedHeroDecimal(
                    target = knots,
                    accent = accent,
                    maxSp = 120.sp.scaled(shape),
                )
                Text(
                    stringResource(R.string.unit_knots),
                    style = if (shape.large) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (secondaryVal != null) {
                    Text(
                        text = "%.1f %s".format(Locale.ROOT, secondaryVal, secondaryLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        zones = listOf(
            // COG on a gimballed (self-levelling) marine compass card.
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    HeroLabel(stringResource(R.string.persona_sailor_cog))
                    Spacer(Modifier.height(6.dp))
                    GimbalCompass(
                        headingDeg = cogValue,
                        pitchDeg = compass.pitchDeg,
                        rollDeg = compass.rollDeg,
                        accent = accent,
                        size = 132.dp.scaled(shape),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "%03d°  %s  %s".format(
                            Locale.ROOT,
                            cogValue.toInt(),
                            headingToCardinal(cogValue),
                            if (course != null) stringResource(R.string.degrees_true)
                            else stringResource(R.string.degrees_magnetic),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            {
                PositionFooter(
                    position = position,
                    extra = "UTC %s · LOCAL %s".format(
                        SimpleDateFormat("HH:mm:ss", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .format(Date(nowMillis)),
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMillis)),
                    ),
                )
            },
        ),
    )
}

// ---------- Motorcyclist ---------- //

@Composable
private fun MotorcyclistFace(
    shape: BespokeShape,
    speedKmh: Float?,
    course: Float?,
    magneticHeadingDeg: Float,
    altitudeM: Double?,
    position: Pair<Double, Double>?,
    unit: UnitSystem,
    accent: Color,
) {
    val heading = course ?: magneticHeadingDeg
    PersonaScaffold(
        shape = shape,
        heroWeight = 1.5f,
        hero = {
            HeroBlock(
                label = "",
                accent = accent,
                shape = shape,
                value = {
                    AnimatedHeroInt(
                        target = speedKmh?.let { UnitConverter.speedFromKmh(it, unit) },
                        accent = accent,
                        maxSp = 200.sp.scaled(shape),
                    )
                },
                subtitle = speedUnitLabel(unit),
            )
        },
        zones = listOf(
            {
                SecondaryRow(
                    stats = listOf(
                        stringResource(R.string.metric_heading)
                            to "%03d°".format(Locale.ROOT, heading.toInt()),
                        stringResource(R.string.metric_altitude) to (
                            altitudeM?.let {
                                "%d %s".format(
                                    Locale.ROOT,
                                    UnitConverter.lengthFromMeters(it, unit).toInt(),
                                    lengthUnitLabel(unit),
                                )
                            } ?: "—"
                        ),
                        stringResource(R.string.metric_orientation)
                            to headingToCardinal(heading),
                    ),
                    valueSp = 22.sp.scaled(shape),
                )
            },
        ),
    )
}

// ---------- Geocacher ---------- //

@Composable
private fun GeocacherFace(
    shape: BespokeShape,
    position: Pair<Double, Double>?,
    navigationTarget: NavigationTarget?,
    magneticHeadingDeg: Float,
    course: Float?,
    satsInUse: Int,
    accuracyM: Float?,
    unit: UnitSystem,
    accent: Color,
) {
    PersonaScaffold(
        shape = shape,
        heroWeight = 1.4f,
        hero = {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (navigationTarget != null && position != null) {
                    val absBearing = be.appmire.gpsinfo.util.NavigationMath.bearingDegrees(
                        position.first, position.second,
                        navigationTarget.targetLatDeg, navigationTarget.targetLonDeg,
                    )
                    val ref = course ?: magneticHeadingDeg
                    val relative = be.appmire.gpsinfo.util.NavigationMath
                        .relativeBearingDegrees(absBearing, ref).toFloat()
                    val distM = be.appmire.gpsinfo.util.NavigationMath.distanceMetres(
                        position.first, position.second,
                        navigationTarget.targetLatDeg, navigationTarget.targetLonDeg,
                    )
                    HeroLabel(navigationTarget.displayName)
                    Spacer(Modifier.height(12.dp))
                    BearingDial(headingDeg = relative, accent = accent, size = 140.dp.scaled(shape))
                    Spacer(Modifier.height(12.dp))
                    HeroValue(formatDistanceCompact(distM, unit), accent, maxSp = 56.sp.scaled(shape))
                } else {
                    Text(
                        text = stringResource(R.string.persona_geocacher_no_target),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        },
        zones = listOf(
            // All position formats stacked — anti-spoiler reads.
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (position != null) {
                        val (lat, lon) = position
                        val dms = CoordinateFormatter.format(lat, lon, CoordinateFormat.DMS)
                            as FormattedCoord.Pair
                        val plus = CoordinateFormatter.format(lat, lon, CoordinateFormat.PLUS_CODE)
                            as FormattedCoord.Single
                        val mgrs = CoordinateFormatter.format(lat, lon, CoordinateFormat.MGRS)
                            as FormattedCoord.Single
                        MonoLine("${dms.lat}  ${dms.lon}")
                        MonoLine(plus.text)
                        MonoLine(mgrs.text)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$satsInUse sats · ±${accuracyM?.toInt() ?: 0} m",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        ),
    )
}

// ---------- Ham / SOTA ---------- //

@Composable
private fun HamFace(
    shape: BespokeShape,
    position: Pair<Double, Double>?,
    altitudeM: Double?,
    nowMillis: Long,
    declinationDeg: Float,
    satsInUse: Int,
    unit: UnitSystem,
    accent: Color,
) {
    val maiden = position?.let { (lat, lon) ->
        (CoordinateFormatter.format(lat, lon, CoordinateFormat.MAIDENHEAD) as FormattedCoord.Single).text
    } ?: "—"

    PersonaScaffold(
        shape = shape,
        heroWeight = 1.2f,
        hero = {
            HeroBlock(
                label = stringResource(R.string.coord_format_maidenhead),
                accent = accent,
                shape = shape,
                value = { HeroValue(maiden, accent, maxSp = 120.sp.scaled(shape)) },
            )
        },
        zones = listOf(
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SecondaryStat(
                        label = stringResource(R.string.metric_utc),
                        value = SimpleDateFormat("HH:mm:ss", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .format(Date(nowMillis)),
                        modifier = Modifier.weight(1f),
                        valueSp = 28.sp.scaled(shape),
                    )
                    WatchVerticalDivider(Modifier.height(40.dp))
                    SecondaryStat(
                        label = stringResource(R.string.metric_local),
                        value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMillis)),
                        modifier = Modifier.weight(1f),
                        valueSp = 28.sp.scaled(shape),
                    )
                }
            },
            {
                PositionFooter(
                    position = position,
                    extra = "${altitudeM?.toInt() ?: 0} m · $satsInUse sats · ${"%.1f".format(Locale.ROOT, declinationDeg)}° E".replace("‏", ""),
                )
            },
        ),
    )
}

// ---------- Shared sub-components ---------- //

/**
 * Minimalist bearing dial — hairline outer ring, 36-tick bezel
 * (longer at the cardinal positions), N/E/S/W cardinal letters, and
 * a smooth-rotating accent-coloured north arrow. The arrow rotation
 * is animated via spring so the dial settles like a damped compass
 * card instead of teleporting.
 */
@Composable
private fun BearingDial(
    headingDeg: Float,
    accent: Color,
    size: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val tickMinor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val tickMajor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardinalColor = MaterialTheme.colorScheme.onSurface
    val northColor = accent
    val density = LocalDensity.current

    // Animate the rotation toward the new target. Continuous (no
    // shortest-path wrap) is fine for small relative-bearing swings;
    // for big jumps the spring overshoots a touch which looks alive.
    val animatedDeg = remember { Animatable(headingDeg) }
    LaunchedEffect(headingDeg) {
        animatedDeg.animateTo(
            headingDeg,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // Pre-build the text paint for the N/E/S/W labels — Canvas can't
    // render Compose Text, so we drop down to native Paint here.
    val labelPx = with(density) { (size.value * 0.13f).sp.toPx() }
    val northPxPaint = remember(northColor, labelPx) {
        android.graphics.Paint().apply {
            color = northColor.toArgb()
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val cardinalPxPaint = remember(cardinalColor, labelPx) {
        android.graphics.Paint().apply {
            color = cardinalColor.toArgb()
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f

            // Outer hairline.
            drawCircle(
                color = ring,
                radius = r - with(density) { 1.dp.toPx() },
                style = Stroke(width = with(density) { 1.5.dp.toPx() }),
            )

            // 36 ticks: longer + brighter every 90° (cardinals).
            for (i in 0 until 36) {
                val angle = Math.toRadians(i * 10.0 - 90.0)
                val isCardinal = i % 9 == 0
                val isMajor = isCardinal || i % 3 == 0
                val rOuter = r - with(density) { 3.dp.toPx() }
                val rInner = r - with(density) {
                    when {
                        isCardinal -> 11.dp
                        isMajor -> 8.dp
                        else -> 5.dp
                    }.toPx()
                }
                drawLine(
                    color = if (isMajor) tickMajor else tickMinor,
                    start = Offset(
                        cx + (Math.cos(angle) * rInner).toFloat(),
                        cy + (Math.sin(angle) * rInner).toFloat(),
                    ),
                    end = Offset(
                        cx + (Math.cos(angle) * rOuter).toFloat(),
                        cy + (Math.sin(angle) * rOuter).toFloat(),
                    ),
                    strokeWidth = with(density) {
                        (if (isCardinal) 2.dp else 1.5.dp).toPx()
                    },
                    cap = StrokeCap.Round,
                )
            }

            // N / E / S / W glyphs just inside the bezel.
            val labelRadius = r - with(density) { 22.dp.toPx() }
            drawIntoCanvas { canvas ->
                listOf(
                    Triple(0.0, "N", northPxPaint),
                    Triple(90.0, "E", cardinalPxPaint),
                    Triple(180.0, "S", cardinalPxPaint),
                    Triple(270.0, "W", cardinalPxPaint),
                ).forEach { (deg, letter, paint) ->
                    val a = Math.toRadians(deg - 90.0)
                    val x = cx + (Math.cos(a) * labelRadius).toFloat()
                    val y = cy + (Math.sin(a) * labelRadius).toFloat() + labelPx / 3f
                    canvas.nativeCanvas.drawText(letter, x, y, paint)
                }
            }
        }
        // Accent-coloured north arrow on top, smoothly rotated.
        Icon(
            imageVector = Icons.Outlined.North,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(size * 0.55f)
                .rotate(animatedDeg.value),
        )
    }
}

/**
 * Gimballed marine compass card. Where [BearingDial] keeps the bezel
 * fixed and spins a needle, this mimics a ship's gimballed compass:
 * the rose card itself rotates under a fixed lubber line at the top,
 * and the whole disc tilts with the device's pitch/roll so it reads
 * like a card swinging level on its gimbals. Heading sits under the
 * lubber line; the numeric value is pinned, upright, in the centre.
 */
@Composable
private fun GimbalCompass(
    headingDeg: Float,
    pitchDeg: Float,
    rollDeg: Float,
    accent: Color,
    size: androidx.compose.ui.unit.Dp = 132.dp,
) {
    val ring = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val tickMinor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val tickMajor = MaterialTheme.colorScheme.onSurfaceVariant
    val cardinalColor = MaterialTheme.colorScheme.onSurface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Rose rotation, shortest-path animated so it never spins the long
    // way across the 0°/360° seam.
    val rose = remember { Animatable(-headingDeg) }
    LaunchedEffect(headingDeg) {
        val target = -headingDeg
        val current = rose.value
        val delta = ((target - current) % 360f + 540f) % 360f - 180f
        rose.animateTo(
            current + delta,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }
    // Gimbal tilt — clamp + smooth the device tilt so the card "hangs"
    // level instead of snapping with every wobble.
    val tiltX by animateFloatAsState(pitchDeg.coerceIn(-22f, 22f), label = "gimbalPitch")
    val tiltY by animateFloatAsState((-rollDeg).coerceIn(-22f, 22f), label = "gimbalRoll")

    val labelPx = with(density) { (size.value * 0.15f).sp.toPx() }
    val northPaint = remember(accent, labelPx) {
        android.graphics.Paint().apply {
            color = accent.toArgb()
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val cardinalPaint = remember(cardinalColor, labelPx) {
        android.graphics.Paint().apply {
            color = cardinalColor.toArgb()
            textSize = labelPx
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }

    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        // Tilting + rotating rose card.
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(2.dp)
                .graphicsLayer {
                    rotationX = tiltX
                    rotationY = tiltY
                    rotationZ = rose.value
                    cameraDistance = 12f * density.density
                },
        ) {
            val r = this.size.minDimension / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            drawCircle(
                color = ring,
                radius = r - with(density) { 1.dp.toPx() },
                style = Stroke(width = with(density) { 1.5.dp.toPx() }),
            )
            // 72 ticks (every 5°): longest at cardinals, medium every 30°.
            for (i in 0 until 72) {
                val angle = Math.toRadians(i * 5.0 - 90.0)
                val isCardinal = i % 18 == 0
                val isMajor = isCardinal || i % 6 == 0
                val rOuter = r - with(density) { 3.dp.toPx() }
                val rInner = r - with(density) {
                    when {
                        isCardinal -> 13.dp
                        isMajor -> 9.dp
                        else -> 5.dp
                    }.toPx()
                }
                drawLine(
                    color = if (isMajor) tickMajor else tickMinor,
                    start = Offset(
                        cx + (Math.cos(angle) * rInner).toFloat(),
                        cy + (Math.sin(angle) * rInner).toFloat(),
                    ),
                    end = Offset(
                        cx + (Math.cos(angle) * rOuter).toFloat(),
                        cy + (Math.sin(angle) * rOuter).toFloat(),
                    ),
                    strokeWidth = with(density) { (if (isCardinal) 2.5.dp else 1.5.dp).toPx() },
                    cap = StrokeCap.Round,
                )
            }
            val labelRadius = r - with(density) { 26.dp.toPx() }
            drawIntoCanvas { canvas ->
                listOf(
                    Triple(0.0, "N", northPaint),
                    Triple(90.0, "E", cardinalPaint),
                    Triple(180.0, "S", cardinalPaint),
                    Triple(270.0, "W", cardinalPaint),
                ).forEach { (deg, letter, paint) ->
                    val a = Math.toRadians(deg - 90.0)
                    val x = cx + (Math.cos(a) * labelRadius).toFloat()
                    val y = cy + (Math.sin(a) * labelRadius).toFloat() + labelPx / 3f
                    canvas.nativeCanvas.drawText(letter, x, y, paint)
                }
            }
        }
        // Fixed, upright centre readout.
        Text(
            text = "%03d°".format(Locale.ROOT, headingDeg.toInt()),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = onSurface,
        )
        // Fixed lubber line at the top — the boat's reference mark.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = this.size.width / 2f
            val top = with(density) { 1.dp.toPx() }
            val w = with(density) { 6.dp.toPx() }
            val h = with(density) { 10.dp.toPx() }
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, top + h)
                lineTo(cx - w, top)
                lineTo(cx + w, top)
                close()
            }
            drawPath(path, color = accent)
        }
    }
}

/** Mono-spaced line, used for coordinate readouts. */
@Composable
private fun MonoLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TertiaryChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeroLabel(label)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Persistent footer line: position in DMS plus an extra summary
 *  string (sats / accuracy / altitude / time, etc.). */
@Composable
private fun PositionFooter(position: Pair<Double, Double>?, extra: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (position != null) {
            val dms = CoordinateFormatter.format(position.first, position.second, CoordinateFormat.DMS)
                as FormattedCoord.Pair
            MonoLine("${dms.lat}  ${dms.lon}")
        } else {
            MonoLine("—")
        }
        Text(
            text = extra,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Formatters ---------- //

private fun formatHms(secs: Long): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    else "%02d:%02d".format(Locale.ROOT, m, s)
}

@Composable
private fun formatDistanceCompact(metres: Double, unit: UnitSystem): String {
    val converted = UnitConverter.lengthFromMeters(metres, unit)
    return if (metres < 1_000.0) "%d %s".format(Locale.ROOT, converted.toInt(), lengthUnitLabel(unit))
    else "%.2f km".format(Locale.ROOT, metres / 1_000.0)
}

private fun formatPaceMmSs(secondsPerUnit: Float): String {
    val m = (secondsPerUnit / 60f).toInt()
    val s = (secondsPerUnit % 60f).toInt()
    return "%d:%02d".format(Locale.ROOT, m, s)
}
