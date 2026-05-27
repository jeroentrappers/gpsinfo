package be.appmire.gpsinfo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.NavigationTarget
import be.appmire.gpsinfo.util.NavigationMath
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.formatPace
import be.appmire.gpsinfo.util.paceSecondsPerUnit
import be.appmire.gpsinfo.util.paceUnitLabel
import java.util.Locale

/**
 * Dashboard card driving the bearing-to-waypoint / track-back UX.
 *
 * Shows a relative-bearing arrow (rotates so "up" is the direction the
 * user is facing — when the arrow points up they're heading straight
 * for the target) plus distance, bearing, and ETA. Renders the
 * "Acquiring satellites…" caption when no fix is available, since
 * neither bearing nor distance is meaningful then.
 *
 * For a [NavigationTarget.Route] the title shows how many waypoints
 * remain in the track-back so the user knows how far in they are.
 */
@Composable
fun NavigationCard(
    target: NavigationTarget,
    currentLatDeg: Double?,
    currentLonDeg: Double?,
    currentHeadingDeg: Float,
    currentSpeedKmh: Float?,
    unitSystem: UnitSystem,
    onStop: () -> Unit,
    onEditGoal: () -> Unit = {},
) {
    SectionCard(
        title = stringResource(R.string.nav_card_title),
        trailing = {
            FilledTonalIconButton(onClick = onStop) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.nav_stop),
                )
            }
        },
    ) {
        Column {
            Text(
                text = target.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (target is NavigationTarget.Route) {
                Text(
                    text = stringResource(
                        R.string.nav_route_progress,
                        target.currentIdx + 1,
                        target.points.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (currentLatDeg == null || currentLonDeg == null) {
                Text(
                    text = stringResource(R.string.acquiring_fix),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@SectionCard
            }

            val targetBearing = NavigationMath.bearingDegrees(
                currentLatDeg, currentLonDeg, target.targetLatDeg, target.targetLonDeg,
            )
            val distance = NavigationMath.distanceMetres(
                currentLatDeg, currentLonDeg, target.targetLatDeg, target.targetLonDeg,
            )
            val relBearing = NavigationMath.relativeBearingDegrees(targetBearing, currentHeadingDeg)
            val eta = currentSpeedKmh?.let { NavigationMath.etaSeconds(distance, it) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                NavigationArrow(relativeBearingDeg = relBearing.toFloat())
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Metric(
                    label = stringResource(R.string.nav_metric_distance),
                    value = formatDistance(distance, unitSystem),
                )
                Metric(
                    label = stringResource(R.string.nav_metric_bearing),
                    value = "%03.0f°".format(Locale.ROOT, targetBearing),
                )
                Metric(
                    label = stringResource(R.string.nav_metric_eta),
                    value = eta?.let(::formatDuration) ?: stringResource(R.string.placeholder_dash),
                )
            }

            // Pace goal row — shown when target pace is set on the
            // navigation target. The runner taps "Set goal" to author
            // one, then sees live deviation against their current pace.
            // For Routes with per-segment targets the effective value
            // changes as currentIdx advances.
            Spacer(Modifier.height(8.dp))
            PaceGoalRow(
                targetPaceSecondsPerUnit = target.effectiveTargetPaceSecondsPerUnit(unitSystem),
                currentPaceSecondsPerUnit = paceSecondsPerUnit(currentSpeedKmh, unitSystem),
                unitSystem = unitSystem,
                onEditGoal = onEditGoal,
            )
        }
    }
}

@Composable
private fun PaceGoalRow(
    targetPaceSecondsPerUnit: Float?,
    currentPaceSecondsPerUnit: Float?,
    unitSystem: UnitSystem,
    onEditGoal: () -> Unit,
) {
    val paceUnit = paceUnitLabel(unitSystem)
    if (targetPaceSecondsPerUnit == null) {
        androidx.compose.material3.TextButton(onClick = onEditGoal) {
            Text(stringResource(R.string.nav_set_pace_goal))
        }
        return
    }
    // Delta is positive when the user is slower than goal (pace seconds
    // higher), negative when faster. We surface the human-readable
    // direction in addition to the signed value.
    val delta: Float? = currentPaceSecondsPerUnit?.let { it - targetPaceSecondsPerUnit }
    val statusRes = when {
        delta == null -> R.string.nav_goal_acquiring
        delta > GOAL_DEVIATION_THRESHOLD_S -> R.string.nav_goal_too_slow
        delta < -GOAL_DEVIATION_THRESHOLD_S -> R.string.nav_goal_too_fast
        else -> R.string.nav_goal_on_target
    }
    val statusColor = when {
        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
        kotlin.math.abs(delta) <= GOAL_DEVIATION_THRESHOLD_S ->
            MaterialTheme.colorScheme.primary
        kotlin.math.abs(delta) <= GOAL_DEVIATION_THRESHOLD_S * 3 ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.nav_goal_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${formatPace(targetPaceSecondsPerUnit)} $paceUnit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(statusRes),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
            Text(
                text = if (delta == null) stringResource(R.string.placeholder_dash)
                else "%+d s%s".format(Locale.ROOT, delta.toInt(), paceUnit),
                style = MaterialTheme.typography.titleMedium,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    androidx.compose.material3.TextButton(onClick = onEditGoal) {
        Text(stringResource(R.string.nav_edit_pace_goal))
    }
}

/** Threshold inside which the runner is "on goal" — within 5 s/km of
 *  target. Beyond it, the row tints amber; beyond ×3 it tints red. */
private const val GOAL_DEVIATION_THRESHOLD_S = 5f

@Composable
private fun NavigationArrow(relativeBearingDeg: Float) {
    val animated by animateFloatAsState(
        targetValue = relativeBearingDeg,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "navArrow",
    )
    val arrowColor = MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.outline
    Box(modifier = Modifier.size(140.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().size(140.dp)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = kotlin.math.min(w, h) / 2f * 0.92f
            // Outer reference ring — gives the arrow a frame so a "0°"
            // arrow doesn't read as just floating in space.
            drawCircle(
                color = ringColor.copy(alpha = 0.55f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
            )
            // "N" tick at top so the user can read the arrow against
            // the same up = forward convention as the compass card.
            drawCircle(
                color = ringColor,
                radius = 3f,
                center = Offset(cx, cy - r),
            )
            rotate(degrees = animated, pivot = Offset(cx, cy)) {
                // Arrow pointing up — chevron-style, wider base, tapered
                // tip so the direction reads instantly even on a small
                // dashboard card.
                val tipY = cy - r * 0.78f
                val baseY = cy + r * 0.42f
                val halfW = r * 0.35f
                val notchY = cy + r * 0.05f
                val path = Path().apply {
                    moveTo(cx, tipY)
                    lineTo(cx + halfW, baseY)
                    lineTo(cx, notchY)
                    lineTo(cx - halfW, baseY)
                    close()
                }
                drawPath(path, color = arrowColor)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatDistance(metres: Double, unit: UnitSystem): String = when (unit) {
    UnitSystem.Metric ->
        if (metres < 1_000.0) "%d m".format(Locale.ROOT, metres.toInt())
        else "%.2f km".format(Locale.ROOT, metres / 1_000.0)
    UnitSystem.Imperial -> {
        val ft = UnitConverter.lengthFromMeters(metres, unit)
        if (ft < 5_280) "%d ft".format(Locale.ROOT, ft.toInt())
        else "%.2f mi".format(Locale.ROOT, ft / 5_280.0)
    }
    UnitSystem.Nautical -> "%.2f NM".format(Locale.ROOT, metres / 1_852.0)
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600L
    val m = (seconds % 3600L) / 60L
    val s = seconds % 60L
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s)
    else "%d:%02d".format(Locale.ROOT, m, s)
}

