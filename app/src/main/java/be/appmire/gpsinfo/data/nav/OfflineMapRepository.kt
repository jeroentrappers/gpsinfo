package be.appmire.gpsinfo.data.nav

import android.content.Context
import be.appmire.gpsinfo.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    // ---------------------------------------------------------------------
    // Phase 2 — self-hosted PMTiles offline regions.
    //
    // The downloaders above use MapLibre's OfflineManager, which CANNOT read
    // a pmtiles:// source. Once the live map is self-hosted on PMTiles, an
    // offline region is instead a regional .pmtiles cut by the server's
    // /extract endpoint, downloaded here and (later) rendered from a file://
    // source. This is the download + on-disk store half; the render wiring
    // is still TODO and needs the live server + bundled glyphs/sprites — see
    // deploy/tiles/README.md. Nothing calls these yet, so the working
    // OpenFreeMap offline path above is untouched.
    // ---------------------------------------------------------------------

    data class PmtilesRegion(
        val name: String,
        val file: File,
        val bbox: String,
        val minZoom: Int,
        val maxZoom: Int,
        val sizeBytes: Long,
    )

    private fun pmtilesDir(): File =
        File(appContext.filesDir, OFFLINE_PMTILES_DIR).apply { mkdirs() }

    /** The local file for a downloaded region, or null if not present. */
    fun pmtilesFile(name: String): File? =
        File(pmtilesDir(), "$name.pmtiles").takeIf { it.exists() }

    /** Downloaded PMTiles regions, read from their on-disk sidecars. */
    fun listPmtilesRegions(): List<PmtilesRegion> {
        val dir = pmtilesDir()
        val sidecars = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return sidecars.mapNotNull { meta ->
            runCatching {
                val j = JSONObject(meta.readText())
                val name = j.getString("name")
                val pmt = File(dir, "$name.pmtiles")
                if (!pmt.exists()) return@runCatching null
                PmtilesRegion(
                    name = name,
                    file = pmt,
                    bbox = j.optString("bbox"),
                    minZoom = j.optInt("minZoom"),
                    maxZoom = j.optInt("maxZoom"),
                    sizeBytes = j.optLong("sizeBytes"),
                )
            }.getOrNull()
        }
    }

    fun deletePmtilesRegion(name: String) {
        val dir = pmtilesDir()
        File(dir, "$name.pmtiles").delete()
        File(dir, "$name.json").delete()
    }

    /**
     * Download a regional `.pmtiles` for [bounds] over [minZoom]..[maxZoom]
     * from the self-hosted `/extract` endpoint into
     * `filesDir/offline-pmtiles/<name>.pmtiles`, writing a sidecar describing
     * it. Emits byte-based progress; completes on success. Requires a
     * configured self-hosted server ([BuildConfig.TILES_BASE_URL]).
     */
    fun downloadRegionPmtiles(
        name: String,
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
    ): Flow<DownloadState> = flow {
        val base = BuildConfig.TILES_BASE_URL.trimEnd('/')
        if (base.isEmpty()) {
            emit(DownloadState.Failed("No self-hosted tile server configured"))
            return@flow
        }
        val bbox = listOf(
            bounds.longitudeWest, bounds.latitudeSouth,
            bounds.longitudeEast, bounds.latitudeNorth,
        ).joinToString(",")
        val keyQ = BuildConfig.TILES_API_KEY.let { if (it.isEmpty()) "" else "&key=$it" }
        val url = "$base/extract?bbox=$bbox&minzoom=$minZoom&maxzoom=$maxZoom$keyQ"

        val dir = pmtilesDir()
        val out = File(dir, "$name.pmtiles")
        val part = File(dir, "$name.pmtiles.part")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                emit(DownloadState.Failed("HTTP $code"))
                return@flow
            }
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastEmit >= 256 * 1024) {
                            lastEmit = done
                            emit(DownloadState.Progress(done, maxOf(total, done)))
                        }
                    }
                }
            }
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            JSONObject()
                .put("name", name)
                .put("bbox", bbox)
                .put("minZoom", minZoom)
                .put("maxZoom", maxZoom)
                .put("sizeBytes", out.length())
                .let { File(dir, "$name.json").writeText(it.toString()) }
            emit(DownloadState.Done)
        } catch (e: Exception) {
            part.delete()
            emit(DownloadState.Failed(e.message ?: "download failed"))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val OFFLINE_PMTILES_DIR = "offline-pmtiles"
    }
}
