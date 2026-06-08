package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.graphics.Bitmap
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Offline vector-map regions via MapLibre's [OfflineManager]. A
 * download fetches the style, fonts/glyphs and every vector tile for
 * a bounding box across a zoom range into MapLibre's local cache —
 * after which the same online style ([MapLibreMapHost]'s OpenFreeMap)
 * renders with no network, on both the phone map and the car
 * snapshotter (they share the one ambient cache).
 *
 * This is the map-imagery counterpart to the rd5 road-network tiles:
 * download the region you'll drive, then navigate it fully offline.
 */
class OfflineMapRepository(context: Context) {

    private val appContext = context.applicationContext
    private val manager: OfflineManager by lazy {
        MapLibre.getInstance(appContext)
        OfflineManager.getInstance(appContext)
    }

    sealed interface DownloadState {
        data class Progress(val completed: Long, val required: Long) : DownloadState
        data object Done : DownloadState
        data class Failed(val message: String) : DownloadState
    }

    /**
     * Download all tiles for [bounds] from [minZoom]..[maxZoom] of the
     * given [styleUrl]. Emits progress; completes the flow when the
     * region is fully cached. Cancelling the collector pauses the
     * download (the region stays in the cache for resume/reuse).
     */
    fun downloadRegion(
        name: String,
        bounds: LatLngBounds,
        styleUrl: String,
        minZoom: Double,
        maxZoom: Double,
    ): Flow<DownloadState> = callbackFlow {
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density,
        )
        val metadata = name.toByteArray(Charsets.UTF_8)

        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            trySend(
                                DownloadState.Progress(
                                    status.completedResourceCount,
                                    // requiredResourceCount is a lower
                                    // bound early on; max keeps the bar
                                    // monotonic.
                                    maxOf(status.requiredResourceCount, status.completedResourceCount),
                                )
                            )
                            if (status.isComplete) {
                                trySend(DownloadState.Done)
                                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                close()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            trySend(DownloadState.Failed("${error.reason}: ${error.message}"))
                            close()
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            trySend(DownloadState.Failed("Tile limit exceeded ($limit)"))
                            close()
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    trySend(DownloadState.Failed(error))
                    close()
                }
            },
        )
        awaitClose { }
    }

    /** Names of regions already downloaded, via the listing callback. */
    fun listRegions(onResult: (List<String>) -> Unit) {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                onResult(
                    regions?.map { String(it.metadata, Charsets.UTF_8) } ?: emptyList()
                )
            }
            override fun onError(error: String) = onResult(emptyList())
        })
    }
}
