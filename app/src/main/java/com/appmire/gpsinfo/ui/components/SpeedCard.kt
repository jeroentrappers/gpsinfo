package com.appmire.gpsinfo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.util.UnitConverter
import com.appmire.gpsinfo.util.headingToCardinal
import com.appmire.gpsinfo.util.lengthUnitLabel
import com.appmire.gpsinfo.util.speedUnitLabel

@Composable
fun SpeedCard(
    speedKmh: Float?,
    headingDegMagnetic: Float?,
    altMeters: Double?,
    unitSystem: UnitSystem = UnitSystem.Metric,
) {
    val dash = stringResource(R.string.placeholder_dash)
    val speedDisplay = speedKmh?.let { UnitConverter.speedFromKmh(it, unitSystem) }
    val altDisplay = altMeters?.let { UnitConverter.lengthFromMeters(it, unitSystem) }
    SectionCard(title = stringResource(R.string.section_movement)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Tile(modifier = Modifier.weight(1.4f)) {
                Text(
                    stringResource(R.string.metric_speed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    speedDisplay?.let { "%.0f".format(it) } ?: dash,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    speedUnitLabel(unitSystem),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Tile(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.metric_heading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    headingDegMagnetic?.let { "%03d".format(it.toInt()) } ?: dash,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    headingDegMagnetic?.let {
                        stringResource(R.string.heading_magnetic_suffix, headingToCardinal(it))
                    } ?: "°M",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Tile(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.metric_altitude),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    altDisplay?.let { it.toInt().toString() } ?: dash,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    lengthUnitLabel(unitSystem),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
