package com.appmire.gpsinfo.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.ui.components.CompassCard
import com.appmire.gpsinfo.ui.components.PositionCard
import com.appmire.gpsinfo.ui.components.SkyViewCard
import com.appmire.gpsinfo.ui.components.SpeedCard
import com.appmire.gpsinfo.ui.components.StatusBar
import com.appmire.gpsinfo.ui.components.TimeSunCard
import com.appmire.gpsinfo.ui.components.WorldMapCard
import com.appmire.gpsinfo.ui.viewmodel.DashboardViewModel
import com.appmire.gpsinfo.util.CoordinateFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onOpenSatellites: () -> Unit = {},
    onOpenCompass: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory())
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var coordFormat by remember { mutableStateOf(CoordinateFormat.DMS) }
    val config = LocalConfiguration.current
    val twoColumn = config.screenWidthDp >= 720

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            contentDescription = stringResource(R.string.action_toggle_theme)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars
    ) { padding ->
        val loc = state.gnss.location
        val sections = listOf<@Composable () -> Unit>(
            {
                StatusBar(
                    fix = state.gnss.fix,
                    accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    satellitesInView = state.gnss.satellitesInView,
                    satellitesInUse = state.gnss.satellitesInUse,
                    averageSnr = state.gnss.averageSnr
                )
            },
            {
                PositionCard(
                    latDeg = loc?.latitude,
                    lonDeg = loc?.longitude,
                    altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                    hAccuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    vAccuracyMeters = loc?.takeIf {
                        android.os.Build.VERSION.SDK_INT >= 26 && it.hasVerticalAccuracy()
                    }?.verticalAccuracyMeters,
                    format = coordFormat,
                    onToggleFormat = {
                        coordFormat = if (coordFormat == CoordinateFormat.DMS)
                            CoordinateFormat.DECIMAL else CoordinateFormat.DMS
                    }
                )
            },
            {
                Box(modifier = Modifier.clickable(onClick = onOpenSpeed)) {
                    SpeedCard(
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        headingDegMagnetic = state.compass.magneticHeadingDeg,
                        altMeters = loc?.takeIf { it.hasAltitude() }?.altitude
                    )
                }
            },
            {
                Box(modifier = Modifier.clickable(onClick = onOpenSatellites)) {
                    SkyViewCard(state.gnss)
                }
            },
            {
                Box(modifier = Modifier.clickable(onClick = onOpenCompass)) {
                    CompassCard(state.compass)
                }
            },
            { WorldMapCard(latDeg = loc?.latitude, lonDeg = loc?.longitude, sun = state.sun) },
            { TimeSunCard(nowMillis = state.nowMillis, sun = state.sun) }
        )

        if (twoColumn) {
            TwoColumnLayout(
                padding = padding,
                sections = sections,
                onOpenAbout = onOpenAbout,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sections.size) { idx -> sections[idx]() }
                item { Spacer(Modifier.height(16.dp)) }
                item { CopyrightFooter(onOpenAbout) }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun CopyrightFooter(onClick: () -> Unit) {
    // Year resolved at composition time — no hardcoded value to bit-rot.
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "© $year Appmire",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun TwoColumnLayout(
    padding: PaddingValues,
    sections: List<@Composable () -> Unit>,
    onOpenAbout: () -> Unit,
) {
    // status bar full-width, then two columns
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            sections.firstOrNull()?.invoke()
            val rest = sections.drop(1)
            val left = rest.filterIndexed { i, _ -> i % 2 == 0 }
            val right = rest.filterIndexed { i, _ -> i % 2 == 1 }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    left.forEach { it() }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    right.forEach { it() }
                }
            }
            Spacer(Modifier.height(8.dp))
            CopyrightFooter(onOpenAbout)
        }
    }
}
