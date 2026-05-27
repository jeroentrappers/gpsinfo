package be.appmire.gpsinfo.ui.nmea

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.SatelliteInfo
import be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import java.util.Locale

/**
 * Diagnostics-style readout of the raw GNSS callback fields, one row
 * per tracked satellite. Same audience that wants a GPS-info dashboard
 * tends to want this — every column is a value the OS exposes verbatim
 * via [android.location.GnssStatus], no smoothing, no rounding beyond
 * what the chip already does.
 *
 * Why not actual NMEA: Android does expose raw NMEA strings via
 * [LocationManager.OnNmeaMessageListener], but those carry the same
 * fields the structured [SatelliteInfo] already gives us — going
 * through the string layer adds parsing complexity for no information
 * gain. This screen is "NMEA-style" in spirit, not in literal format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NmeaReadoutScreen(vm: DashboardViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sats = state.gnss.satellites

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_nmea)) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Header() }
            items(sats, key = { "${it.constellation}-${it.svid}-${it.carrierFrequencyHz.toInt()}" }) { sat ->
                SatRow(sat)
            }
            if (sats.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.sky_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell("SVID", weight = 1f)
        Cell("CON", weight = 1f)
        Cell("AZ°", weight = 1f)
        Cell("EL°", weight = 1f)
        Cell("Cn0", weight = 1f)
        Cell("FREQ", weight = 1.4f)
        Cell("FLAGS", weight = 1.2f)
    }
}

@Composable
private fun SatRow(sat: SatelliteInfo) {
    val freqMhz = if (sat.carrierFrequencyHz > 0f) sat.carrierFrequencyHz / 1_000_000f else 0f
    val flags = buildString {
        if (sat.usedInFix) append('U') else append('-')
        if (sat.hasEphemeris) append('E') else append('-')
        if (sat.hasAlmanac) append('A') else append('-')
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell(sat.svid.toString(), weight = 1f)
        Cell(
            text = sat.constellation.label.take(3),
            weight = 1f,
            color = Color(sat.constellation.color),
        )
        Cell("%.0f".format(Locale.ROOT, sat.azimuthDeg), weight = 1f)
        Cell("%.0f".format(Locale.ROOT, sat.elevationDeg), weight = 1f)
        Cell("%.0f".format(Locale.ROOT, sat.cn0DbHz), weight = 1f)
        Cell(
            text = if (freqMhz > 0f) "%.2f".format(Locale.ROOT, freqMhz) else "—",
            weight = 1.4f,
        )
        Cell(flags, weight = 1.2f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    weight: Float,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}
