package be.appmire.gpsinfo.ui.trails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.model.LatLonBounds
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.BoundingBox

/**
 * Lets the user download OSM tiles for the bounding box of a trail so
 * the map renders without network later. Three depth presets correspond
 * to ~zoomed-out, comfortable, and street-level — the higher the zoom,
 * the more tiles and the longer the download.
 *
 * Why a fixed set of presets rather than letting the user pick zoom
 * levels: tile counts grow as 4ⁿ in zoom. A naïve "max zoom = 18" pick
 * over a hike-sized box can mean 50k+ tiles, which the OSM tile policy
 * pretty clearly doesn't want us hammering for. The presets keep us
 * within community-sensible limits.
 */
@Composable
fun OfflineCacheDialog(
    bounds: LatLonBounds,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var selectedDepth by remember { mutableIntStateOf(1) }   // Default = Medium
    var inProgress by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    // Outcome is kept as structured state (Done / Failed(errors) / null)
    // rather than a pre-formatted string. That way the string-resource
    // lookup happens inside composition and respects locale changes.
    var outcome by remember { mutableStateOf<DownloadOutcome?>(null) }

    val depths = listOf(
        DepthChoice(R.string.tile_cache_depth_overview, minZoom = 8, maxZoom = 12),
        DepthChoice(R.string.tile_cache_depth_medium, minZoom = 10, maxZoom = 14),
        DepthChoice(R.string.tile_cache_depth_street, minZoom = 12, maxZoom = 16),
    )

    val bb = BoundingBox(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon)
    // We build CacheManager from the tile source + a direct SqlTileWriter
    // rather than wrapping a MapView. The `CacheManager(MapView)`
    // overload runs a TileSourcePolicy check that stock Mapnik fails (the
    // OSM tile usage policy forbids bulk downloads), and the same check
    // runs again inside the download AsyncTask. Both crash with
    // TileSourcePolicyException. [MapnikBulkOk] is a Mapnik variant whose
    // policy doesn't carry FLAG_NO_BULK; the depth presets above keep
    // usage reasonable.
    val tileSource = MapnikBulkOk
    val cache = remember {
        CacheManager(
            tileSource,
            SqlTileWriter(),
            tileSource.minimumZoomLevel,
            tileSource.maximumZoomLevel,
        )
    }

    // Estimate tile count for the current selection so the user can
    // bail out of an oversized download before kicking it off.
    val tileEstimate = remember(selectedDepth) {
        val d = depths[selectedDepth]
        cache.possibleTilesInArea(bb, d.minZoom, d.maxZoom)
    }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(stringResource(R.string.tile_cache_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.tile_cache_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                depths.forEachIndexed { idx, choice ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = selectedDepth == idx,
                            onClick = { if (!inProgress) selectedDepth = idx },
                            enabled = !inProgress,
                        )
                        Text(
                            text = stringResource(choice.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.tile_cache_estimate, tileEstimate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (inProgress) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                outcome?.let { o ->
                    Text(
                        text = when (o) {
                            DownloadOutcome.Done -> stringResource(R.string.tile_cache_done)
                            is DownloadOutcome.Failed ->
                                stringResource(R.string.tile_cache_failed, o.errors)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inProgress,
                onClick = {
                    inProgress = true
                    val d = depths[selectedDepth]
                    try {
                        cache.downloadAreaAsync(
                            ctx, bb, d.minZoom, d.maxZoom,
                            object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() {
                                    // Success path — nothing else for the
                                    // user to do here, so close the dialog
                                    // straight away. Failures stay open so
                                    // the error count is visible.
                                    inProgress = false
                                    outcome = DownloadOutcome.Done
                                    onDismiss()
                                }
                                override fun onTaskFailed(errors: Int) {
                                    inProgress = false
                                    outcome = DownloadOutcome.Failed(errors)
                                }
                                override fun updateProgress(progressP: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                    // CacheManager reports a 0–100 int.
                                    progress = progressP / 100f
                                }
                                override fun downloadStarted() {}
                                override fun setPossibleTilesInArea(total: Int) {}
                            }
                        )
                    } catch (t: Throwable) {
                        // Any synchronous setup failure (path issues,
                        // tile-source policy, etc.) lands here instead of
                        // taking the activity down. The async task itself
                        // routes its own exceptions through onTaskFailed.
                        inProgress = false
                        outcome = DownloadOutcome.Failed(errors = -1)
                    }
                }
            ) {
                Text(stringResource(R.string.tile_cache_download))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !inProgress,
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private data class DepthChoice(val labelRes: Int, val minZoom: Int, val maxZoom: Int)

private sealed interface DownloadOutcome {
    data object Done : DownloadOutcome
    data class Failed(val errors: Int) : DownloadOutcome
}
