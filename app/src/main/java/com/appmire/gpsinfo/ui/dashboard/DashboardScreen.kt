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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    vm: DashboardViewModel,
    onOpenSatellites: () -> Unit = {},
    onOpenCompass: () -> Unit = {},
    onOpenSpeed: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
        val ctx = LocalContext.current
        val sections = listOfNotNull<@Composable () -> Unit>(
            // Banner when system Location toggle is off — without this the
            // user just sees perpetual NO_FIX with no actionable text.
            if (!state.locationEnabled) {
                {
                    LocationDisabledBanner(onOpenSettings = {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    })
                }
            } else null,
            {
                StatusBar(
                    fix = state.gnss.fix,
                    accuracyMeters = loc?.takeIf { it.hasAccuracy() }?.accuracy,
                    satellitesInView = state.gnss.satellitesInView,
                    satellitesInUse = state.gnss.satellitesInUse,
                    averageSnr = state.gnss.averageSnr,
                    unitSystem = state.unitSystem,
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
                    },
                    unitSystem = state.unitSystem,
                )
            },
            {
                val speedDesc = stringResource(R.string.open_speed_gauge)
                Box(modifier = Modifier
                    .clickable(onClick = onOpenSpeed, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = speedDesc }
                ) {
                    SpeedCard(
                        speedKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f),
                        headingDegMagnetic = state.compass.magneticHeadingDeg,
                        altMeters = loc?.takeIf { it.hasAltitude() }?.altitude,
                        unitSystem = state.unitSystem,
                    )
                }
            },
            {
                val satDesc = stringResource(R.string.open_satellites)
                Box(modifier = Modifier
                    .clickable(onClick = onOpenSatellites, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = satDesc }
                ) {
                    SkyViewCard(state.gnss)
                }
            },
            {
                val compassDesc = stringResource(R.string.open_compass_detail)
                Box(modifier = Modifier
                    .clickable(onClick = onOpenCompass, role = Role.Button)
                    .semantics(mergeDescendants = true) { contentDescription = compassDesc }
                ) {
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
                items(sections.size, key = { "section-$it" }) { idx -> sections[idx]() }
                item(key = "footer-spacer") { Spacer(Modifier.height(16.dp)) }
                item(key = "footer-copy") { CopyrightFooter(onOpenAbout) }
                item(key = "footer-tail") { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun CopyrightFooter(onClick: () -> Unit) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val description = stringResource(R.string.action_open_about)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "© $year Appmire",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.action_about),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.action_open_about),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationDisabledBanner(onOpenSettings: () -> Unit) {
    val description = stringResource(R.string.location_off_open_settings)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings, role = Role.Button)
            .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.location_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.location_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = stringResource(R.string.location_off_open_settings),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
        }
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
