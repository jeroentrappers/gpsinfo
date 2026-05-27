package be.appmire.gpsinfo.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.formatPace
import be.appmire.gpsinfo.util.headingToCardinal
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.paceSecondsPerUnit
import be.appmire.gpsinfo.util.paceUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel

@Composable
fun SpeedCard(
    speedKmh: Float?,
    headingDegMagnetic: Float?,
    altMeters: Double?,
    unitSystem: UnitSystem = UnitSystem.Metric,
    /** GPS course-over-ground in degrees. When present, takes priority
     *  over [headingDegMagnetic] — accurate regardless of phone
     *  orientation (cupholder, windshield mount, lying flat). Falls
     *  back to magnetic compass when the user is stationary and the
     *  chip doesn't report a bearing. */
    gpsBearingDeg: Float? = null,
) {
    val dash = stringResource(R.string.placeholder_dash)
    val speedDisplay = speedKmh?.let { UnitConverter.speedFromKmh(it, unitSystem) }
    val altDisplay = altMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
    val dimmed = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    SectionCard(title = stringResource(R.string.section_movement)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Tile(modifier = Modifier.weight(1.4f)) {
                Text(
                    stringResource(R.string.metric_speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = dimmed,
                )
                Text(
                    speedDisplay?.let { "%.0f".format(it) } ?: dash,
                    style = MaterialTheme.typography.displayLarge,
                    color = if (speedDisplay != null) primary else dimmed,
                )
                Text(
                    speedUnitLabel(unitSystem),
                    style = MaterialTheme.typography.labelMedium,
                    color = dimmed,
                )
                // Pace sub-line. Hidden when stationary so we don't show
                // a flapping "59:59 /km" the moment fix is acquired.
                val pace = paceSecondsPerUnit(speedKmh, unitSystem)
                if (pace != null) {
                    Text(
                        text = "${formatPace(pace)} ${paceUnitLabel(unitSystem)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Tile(modifier = Modifier.weight(1f)) {
                // Prefer GPS course-over-ground when it's available
                // (i.e. the user is moving). Phone-orientation
                // magnetic heading is the fallback for stationary
                // use — orienteering, sighting a landmark — where
                // GPS bearing is null or stale anyway.
                val effective: Float? = gpsBearingDeg ?: headingDegMagnetic
                val fromGps = gpsBearingDeg != null
                Text(
                    stringResource(R.string.metric_heading),
                    style = MaterialTheme.typography.labelSmall,
                    color = dimmed,
                )
                Text(
                    effective?.let { "%03d".format(it.toInt()) } ?: dash,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (effective != null) primary else dimmed,
                )
                Text(
                    effective?.let {
                        // °T = true heading (GPS course over ground).
                        // °M = magnetic (phone compass). Different
                        // suffix so the user knows which source.
                        if (fromGps) {
                            stringResource(R.string.heading_true_suffix, headingToCardinal(it))
                        } else {
                            stringResource(R.string.heading_magnetic_suffix, headingToCardinal(it))
                        }
                    } ?: "°—",
                    style = MaterialTheme.typography.labelMedium,
                    color = dimmed,
                )
            }
            Tile(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.metric_altitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = dimmed,
                )
                Text(
                    altDisplay?.let { it.toInt().toString() } ?: dash,
                    style = MaterialTheme.typography.displaySmall,
                    color = if (altDisplay != null) primary else dimmed,
                )
                Text(
                    lengthUnitLabel(unitSystem),
                    style = MaterialTheme.typography.labelMedium,
                    color = dimmed,
                )
            }
        }
        if (speedKmh == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.acquiring_fix),
                style = MaterialTheme.typography.bodySmall,
                color = dimmed,
            )
        }
    }
}

@Composable
private fun Tile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.CenterStart) {
            Column { content() }
        }
    }
}
