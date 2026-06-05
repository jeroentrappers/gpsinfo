package be.appmire.gpsinfo.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.NavigationTarget
import be.appmire.gpsinfo.ui.trails.MapnikBulkOk
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Two-tab picker for the bearing-to-waypoint flow:
 *   - Pick on map: tap an osmdroid map to place a marker.
 *   - Enter coordinates: paste decimal lat/lon (plus optional name).
 *
 * On confirm, returns the chosen [NavigationTarget.Single] to the caller
 * via [onConfirm] and pops back. The screen has no VM state of its own;
 * its `pickedLat`/`pickedLon`/`coords*` are local to the composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationPickerScreen(
    initialLatDeg: Double?,
    initialLonDeg: Double?,
    onBack: () -> Unit,
    onConfirm: (NavigationTarget.Single) -> Unit,
    /** Optional: start offline turn-by-turn driving to the target
     *  instead of the bearing-style waypoint navigation. */
    onDriveTo: ((NavigationTarget.Single) -> Unit)? = null,
) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_pick_screen_title)) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.nav_pick_tab_map)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.nav_pick_tab_coords)) },
                )
            }
            when (tab) {
                0 -> MapPickPane(
                    modifier = Modifier.weight(1f),
                    initialLatDeg = initialLatDeg,
                    initialLonDeg = initialLonDeg,
                    onConfirm = onConfirm,
                    onDriveTo = onDriveTo,
                )
                else -> CoordsPickPane(
                    modifier = Modifier.weight(1f),
                    onConfirm = onConfirm,
                    onDriveTo = onDriveTo,
                )
            }
        }
    }
}

@Composable
private fun MapPickPane(
    modifier: Modifier = Modifier,
    initialLatDeg: Double?,
    initialLonDeg: Double?,
    onConfirm: (NavigationTarget.Single) -> Unit,
    onDriveTo: ((NavigationTarget.Single) -> Unit)? = null,
) {
    val defaultName = stringResource(R.string.nav_default_name)
    var picked by remember { mutableStateOf<GeoPoint?>(null) }
    // `modifier` carries the parent Column's weight(1f) so this pane
    // fills only the space below the tab selector. Without it the
    // map requested the full parent height and overflowed up under
    // the selector.
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.nav_pick_map_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // clipToBounds is load-bearing: osmdroid's MapView paints its
        // tiles outside its own layout rect, which otherwise overdraws
        // the tab selector + hint sitting above it (they're correctly
        // placed per the view hierarchy but painted over). Clipping the
        // map to its slot keeps the chrome above it visible.
        Box(modifier = Modifier.weight(1f).clipToBounds()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(MapnikBulkOk)
                        setMultiTouchControls(true)
                        minZoomLevel = MapnikBulkOk.minimumZoomLevel.toDouble()
                        maxZoomLevel = MapnikBulkOk.maximumZoomLevel.toDouble()
                        val seed = if (initialLatDeg != null && initialLonDeg != null) {
                            GeoPoint(initialLatDeg, initialLonDeg)
                        } else {
                            // Mid-Europe fallback — better than ocean-default.
                            GeoPoint(50.0, 10.0)
                        }
                        controller.setCenter(seed)
                        controller.setZoom(if (initialLatDeg != null) 14.0 else 5.0)
                        // MapEventsOverlay turns taps into latlon callbacks.
                        // We replace the marker on every tap rather than
                        // appending — single-destination picker, no list.
                        val events = MapEventsOverlay(object : MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                                picked = p
                                overlays.removeAll { it is PickedMarker }
                                overlays.add(
                                    PickedMarker(this@apply).apply {
                                        position = p
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    }
                                )
                                invalidate()
                                return true
                            }
                            override fun longPressHelper(p: GeoPoint): Boolean = false
                        })
                        overlays.add(0, events)
                    }
                },
                onRelease = { it.onDetach() },
            )
        }
        val p = picked
        Button(
            onClick = {
                if (p != null) {
                    onConfirm(NavigationTarget.Single(p.latitude, p.longitude, defaultName))
                }
            },
            enabled = p != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            Text(stringResource(R.string.nav_pick_confirm))
        }
        if (onDriveTo != null) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    if (p != null) {
                        onDriveTo(NavigationTarget.Single(p.latitude, p.longitude, defaultName))
                    }
                },
                enabled = p != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) {
                Text(stringResource(R.string.nav_pick_drive))
            }
        }
    }
}

private class PickedMarker(mapView: MapView) : Marker(mapView)

@Composable
private fun CoordsPickPane(
    modifier: Modifier = Modifier,
    onConfirm: (NavigationTarget.Single) -> Unit,
    onDriveTo: ((NavigationTarget.Single) -> Unit)? = null,
) {
    val defaultName = stringResource(R.string.nav_default_name)
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var plusCodeText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    val lat = be.appmire.gpsinfo.util.CoordinateParser.parseHalf(latText, isLat = true)
    val lon = be.appmire.gpsinfo.util.CoordinateParser.parseHalf(lonText, isLat = false)
    val valid = lat != null && lon != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Alternative-format fast-path. A complete Plus Code
        // ("7FG49QCJ+2V") or Maidenhead Locator ("JO21in") decodes
        // to lat/lon and populates the fields below — useful when
        // the user pastes a code from a geocache app, ham log, or
        // Maps share link.
        OutlinedTextField(
            value = plusCodeText,
            onValueChange = { newValue ->
                plusCodeText = newValue
                val parsed = if (newValue.contains('+')) {
                    be.appmire.gpsinfo.util.CoordinateParser.parsePlusCode(newValue)
                } else {
                    be.appmire.gpsinfo.util.CoordinateParser.parseMaidenhead(newValue)
                }
                if (parsed != null) {
                    latText = "%.6f".format(java.util.Locale.ROOT, parsed.first)
                    lonText = "%.6f".format(java.util.Locale.ROOT, parsed.second)
                }
            },
            label = { Text(stringResource(R.string.nav_pick_coords_alt_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = latText,
            onValueChange = { latText = it },
            label = { Text(stringResource(R.string.nav_pick_coords_lat_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = lonText,
            onValueChange = { lonText = it },
            label = { Text(stringResource(R.string.nav_pick_coords_lon_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nameText,
            onValueChange = { nameText = it },
            label = { Text(stringResource(R.string.nav_pick_coords_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (latText.isNotBlank() && lonText.isNotBlank() && !valid) {
            Text(
                text = stringResource(R.string.nav_pick_invalid_coords),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                if (lat != null && lon != null) {
                    val name = nameText.trim().ifBlank { defaultName }
                    onConfirm(NavigationTarget.Single(lat, lon, name))
                }
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.nav_pick_confirm))
        }
        if (onDriveTo != null) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    if (lat != null && lon != null) {
                        val name = nameText.trim().ifBlank { defaultName }
                        onDriveTo(NavigationTarget.Single(lat, lon, name))
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.nav_pick_drive))
            }
        }
    }
}
