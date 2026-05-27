package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.TrailPoint
import be.appmire.gpsinfo.util.CoordinateFormat
import be.appmire.gpsinfo.util.CoordinateFormatter
import be.appmire.gpsinfo.util.UnitConverter
import be.appmire.gpsinfo.util.headingToCardinal
import be.appmire.gpsinfo.util.lengthUnitLabel
import be.appmire.gpsinfo.util.speedUnitLabel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-sheet payload that surfaces everything captured at one
 * trackpoint. Pure presentation — pulled out of [TrailMapScreen] so the
 * sheet content is easy to preview and to keep the map file focused on
 * osmdroid wiring.
 */
@Composable
fun TrailPointDetailsSheet(point: TrailPoint, unitSystem: UnitSystem) {
    val timeFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
    }
    val coords = remember(point.latDeg, point.lonDeg) {
        CoordinateFormatter.format(point.latDeg, point.lonDeg, CoordinateFormat.DMS)
            as be.appmire.gpsinfo.util.FormattedCoord.Pair
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                R.string.trail_point_title,
                timeFormat.format(Date(point.timeMillis)),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = coords.lat,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = coords.lon,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }

        StatRow(
            label = stringResource(R.string.metric_altitude),
            value = point.eleMeters?.let {
                "%.1f %s".format(
                    Locale.ROOT,
                    UnitConverter.lengthFromMeters(it, unitSystem),
                    lengthUnitLabel(unitSystem),
                )
            } ?: DASH,
        )
        StatRow(
            label = stringResource(R.string.metric_speed),
            value = point.speedMps?.let {
                "%.1f %s".format(
                    Locale.ROOT,
                    UnitConverter.speedFromKmh(it * 3.6f, unitSystem),
                    speedUnitLabel(unitSystem),
                )
            } ?: DASH,
        )
        StatRow(
            label = stringResource(R.string.metric_heading),
            value = point.courseDeg?.let {
                "%03d°  %s".format(
                    Locale.ROOT,
                    it.toInt(),
                    headingToCardinal(it),
                )
            } ?: DASH,
        )
        StatRow(
            label = stringResource(R.string.metric_h_accuracy),
            value = point.hAccuracyM?.let {
                "± %.1f %s".format(
                    Locale.ROOT,
                    UnitConverter.lengthFromMeters(it, unitSystem),
                    lengthUnitLabel(unitSystem),
                )
            } ?: DASH,
        )
        StatRow(
            label = stringResource(R.string.metric_v_accuracy),
            value = point.vAccuracyM?.let {
                "± %.1f %s".format(
                    Locale.ROOT,
                    UnitConverter.lengthFromMeters(it, unitSystem),
                    lengthUnitLabel(unitSystem),
                )
            } ?: DASH,
        )
        StatRow(
            label = stringResource(R.string.sat_stat_in_fix),
            value = point.satellitesInFix?.toString() ?: DASH,
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    // Compose 1.8 lint flags Locale.getDefault() reads inside a
    // composable because they don't recompose on locale change.
    // platformLocale via LocalLocale gives the same value AND opts in
    // to recomposition when the user switches language.
    val displayLocale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label.uppercase(displayLocale),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private const val DASH = "—"
