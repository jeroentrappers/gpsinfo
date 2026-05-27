package be.appmire.gpsinfo.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.ui.theme.SignalGreen
import be.appmire.gpsinfo.ui.theme.SignalOrange
import be.appmire.gpsinfo.ui.theme.SignalRed
import be.appmire.gpsinfo.ui.theme.SignalYellow
import be.appmire.gpsinfo.util.NavigationMath
import be.appmire.gpsinfo.util.RouteProjection
import be.appmire.gpsinfo.util.UnitConverter
import java.util.Locale

/**
 * Intensity-profile strip: the next ~1.5 km of trail rendered as a
 * stacked horizontal bar, each band coloured by grade. Below the bar,
 * an ETA-to-next-climb chip when a qualifying climb is in range.
 *
 * Visibility logic lives in the caller — render this only when an
 * active track-back route is set on the dashboard, otherwise the bar
 * is empty and the card looks broken.
 */
@Composable
fun IntensityProfileCard(
    segments: List<RouteProjection.UpcomingSegment>,
    nextClimb: RouteProjection.Climb?,
    currentSpeedKmh: Float?,
    unitSystem: UnitSystem,
) {
    if (segments.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_card_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ProfileBar(segments = segments)
            Spacer(Modifier.height(8.dp))
            ProfileScaleRow(segments = segments, unitSystem = unitSystem)
            if (nextClimb != null) {
                Spacer(Modifier.height(10.dp))
                NextClimbRow(
                    climb = nextClimb,
                    currentSpeedKmh = currentSpeedKmh,
                    unitSystem = unitSystem,
                )
            }
        }
    }
}

@Composable
private fun ProfileBar(segments: List<RouteProjection.UpcomingSegment>) {
    val total = segments.sumOf { it.distanceMetres }.coerceAtLeast(0.001)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp)),
    ) {
        for (s in segments) {
            val weight = (s.distanceMetres / total).toFloat().coerceAtLeast(0.001f)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(28.dp)
                    .background(gradeColor(s.gradePercent)),
            )
        }
    }
}

@Composable
private fun ProfileScaleRow(
    segments: List<RouteProjection.UpcomingSegment>,
    unitSystem: UnitSystem,
) {
    val total = segments.sumOf { it.distanceMetres }
    val low = segments.minOf { kotlin.math.min(it.elevationStartM, it.elevationEndM) }
    val high = segments.maxOf { kotlin.math.max(it.elevationStartM, it.elevationEndM) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(
                R.string.profile_distance_ahead,
                formatDistanceShort(total, unitSystem),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = stringResource(
                R.string.profile_elevation_range,
                formatElevationShort(low, unitSystem),
                formatElevationShort(high, unitSystem),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun NextClimbRow(
    climb: RouteProjection.Climb,
    currentSpeedKmh: Float?,
    unitSystem: UnitSystem,
) {
    val etaSec = currentSpeedKmh?.let { NavigationMath.etaSeconds(climb.distanceToStartM, it) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.profile_next_climb).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.profile_climb_summary,
                        formatDistanceShort(climb.climbDistanceM, unitSystem),
                        formatElevationShort(climb.climbGainM, unitSystem),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.profile_climb_in),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDistanceShort(climb.distanceToStartM, unitSystem),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                )
                if (etaSec != null) {
                    Text(
                        text = formatDurationShort(etaSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Map a grade percentage to a colour band. Symmetric around zero;
 *  steeper = more saturated. Tuned for runners' intuition: green when
 *  flat/easy, red on punchy climbs. */
private fun gradeColor(gradePercent: Float): Color = when {
    gradePercent >= 10f -> SignalRed
    gradePercent >= 5f -> SignalOrange
    gradePercent >= 1f -> SignalYellow
    gradePercent >= -1f -> SignalGreen
    gradePercent >= -5f -> SignalGreen.copy(alpha = 0.65f)
    else -> Color(0xFF4FC3F7)  // blue = descent
}

private fun formatDistanceShort(metres: Double, unit: UnitSystem): String = when (unit) {
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

private fun formatElevationShort(metres: Double, unit: UnitSystem): String = when (unit) {
    UnitSystem.Metric -> "%d m".format(Locale.ROOT, metres.toInt())
    UnitSystem.Imperial, UnitSystem.Nautical -> {
        val ft = UnitConverter.lengthFromMeters(metres, unit)
        "%d ft".format(Locale.ROOT, ft.toInt())
    }
}

private fun formatDurationShort(seconds: Long): String {
    val m = seconds / 60L
    val s = seconds % 60L
    return "%d:%02d".format(Locale.ROOT, m, s)
}
