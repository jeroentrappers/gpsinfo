package com.appmire.gpsinfo.ui.satellite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appmire.gpsinfo.data.model.Constellation
import com.appmire.gpsinfo.data.model.SatelliteInfo
import com.appmire.gpsinfo.ui.theme.SignalGreen
import com.appmire.gpsinfo.ui.theme.SignalOrange
import com.appmire.gpsinfo.ui.theme.SignalRed
import com.appmire.gpsinfo.ui.theme.SignalYellow
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel

/**
 * Detail view that lists every satellite the GNSS chip currently reports,
 * sorted by constellation then SVID. Header summarises in-view / in-fix
 * counts and the strongest signal.
 */
enum class SatSortMode(val label: String) {
    /** Group by constellation, then SVID — stable, easy to find a specific PRN. */
    CONSTELLATION("By constellation"),
    /** Strongest Cn0 first — useful for "what's actually tracking well right now". */
    SIGNAL("By signal");
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteListScreen(
    vm: DashboardViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sortMode by remember { mutableStateOf(SatSortMode.CONSTELLATION) }

    // derivedStateOf so the sort runs only when satellites or sortMode change,
    // not on every recomposition that happens to read this list.
    val sats: List<SatelliteInfo> by remember(vm) {
        derivedStateOf {
            val raw = state.gnss.satellites
            when (sortMode) {
                SatSortMode.CONSTELLATION -> raw.sortedWith(
                    compareBy(
                        { it.constellation.ordinal },
                        { it.svid },
                        { it.carrierFrequencyHz },
                    ),
                )
                SatSortMode.SIGNAL -> raw.sortedWith(
                    // Strongest first. Push silent satellites (cn0 == 0) to the
                    // bottom regardless, so the top of the list is always the
                    // useful end. Constellation/SVID as tie-breakers keep order
                    // stable when two satellites share a Cn0 value.
                    compareByDescending<SatelliteInfo> { it.cn0DbHz > 0f }
                        .thenByDescending { it.cn0DbHz }
                        .thenBy { it.constellation.ordinal }
                        .thenBy { it.svid }
                        .thenBy { it.carrierFrequencyHz },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Satellites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    val nextMode = when (sortMode) {
                        SatSortMode.CONSTELLATION -> SatSortMode.SIGNAL
                        SatSortMode.SIGNAL -> SatSortMode.CONSTELLATION
                    }
                    IconButton(onClick = { sortMode = nextMode }) {
                        // Icon reflects the *current* sort mode so the user
                        // sees what they have, and the tooltip describes what
                        // tapping will do (switch to the other mode).
                        Icon(
                            imageVector = when (sortMode) {
                                SatSortMode.CONSTELLATION -> Icons.AutoMirrored.Outlined.Sort
                                SatSortMode.SIGNAL -> Icons.Outlined.SignalCellularAlt
                            },
                            contentDescription = "Sort: ${nextMode.label}",
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Header(sats) }
            item { Spacer(Modifier.height(4.dp)) }
            item {
                SortChips(
                    current = sortMode,
                    onSelect = { sortMode = it },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
            item { ColumnHeaders() }
            // Key includes carrier frequency + index so multi-band reports
            // of the same PRN (e.g. GPS L1 + L5 for SVID 1) don't collide.
            itemsIndexed(
                items = sats,
                key = { idx, it ->
                    "${it.constellation.ordinal}-${it.svid}-${it.carrierFrequencyHz.toRawBits()}-$idx"
                },
            ) { _, sat ->
                SatelliteRow(sat)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChips(
    current: SatSortMode,
    onSelect: (SatSortMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SORT",
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        SatSortMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
                leadingIcon = if (mode == current) {
                    {
                        Icon(
                            imageVector = when (mode) {
                                SatSortMode.CONSTELLATION -> Icons.AutoMirrored.Outlined.Sort
                                SatSortMode.SIGNAL -> Icons.Outlined.SignalCellularAlt
                            },
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}

@Composable
private fun Header(sats: List<SatelliteInfo>) {
    val inFix = sats.count { it.usedInFix }
    val withSignal = sats.count { it.cn0DbHz > 0f }
    val best = sats.maxOfOrNull { it.cn0DbHz } ?: 0f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "GNSS OVERVIEW",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("In view", "${sats.size}")
                Stat("In fix", "$inFix")
                Stat("Tracking", "$withSignal")
                Stat("Best", if (best > 0f) "%.0f dB-Hz".format(best) else "—")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ColumnHeaders() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("CON", 56.dp)
        HeaderCell("SVID", 56.dp)
        HeaderCell("CN0", 56.dp)
        Box(Modifier.weight(1f)) { HeaderCell("SIGNAL") }
        HeaderCell("ELV", 52.dp)
        HeaderCell("AZ", 52.dp)
        HeaderCell("FIX", 40.dp)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp = 0.dp) {
    val mod = if (width > 0.dp) Modifier.width(width) else Modifier
    Text(
        text = text,
        modifier = mod,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun SatelliteRow(sat: SatelliteInfo) {
    val rowBg = if (sat.usedInFix)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBg)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConstellationBadge(sat.constellation, Modifier.width(56.dp))
        MonoCell("${sat.svid}", 56.dp)
        MonoCell(
            text = if (sat.cn0DbHz > 0f) "%.0f".format(sat.cn0DbHz) else "—",
            width = 56.dp,
            color = signalColor(sat.cn0DbHz),
        )
        // CN0 bar — fills 15..50 dB-Hz range.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)),
        ) {
            val frac = ((sat.cn0DbHz - 15f) / 35f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(signalColor(sat.cn0DbHz)),
            )
        }
        Spacer(Modifier.width(8.dp))
        MonoCell(if (sat.elevationDeg >= 0f) "%.0f°".format(sat.elevationDeg) else "—", 52.dp)
        MonoCell(if (sat.azimuthDeg >= 0f) "%.0f°".format(sat.azimuthDeg) else "—", 52.dp)
        Text(
            text = if (sat.usedInFix) "✓" else "·",
            modifier = Modifier.width(40.dp),
            color = if (sat.usedInFix) SignalGreen
                else MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MonoCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ConstellationBadge(c: Constellation, modifier: Modifier = Modifier) {
    val tint = Color(c.color)
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = c.label.take(4),
                color = tint,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun signalColor(cn0: Float): Color = when {
    cn0 < 25f -> SignalRed
    cn0 < 32f -> SignalOrange
    cn0 < 38f -> SignalYellow
    else -> SignalGreen
}
