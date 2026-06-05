package be.appmire.gpsinfo.data.nav

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads and manages BRouter rd5 segment tiles — the offline road
 * network. Tiles come from the BRouter project's public segment
 * server (rebuilt weekly from OSM); one 5°×5° tile is tens to a few
 * hundred MB, so downloads stream to a `.part` file and rename on
 * completion — a killed download never leaves a corrupt tile behind.
 *
 * The Benelux fits in a single tile (E0_N50.rd5 covers 0–5°E,
 * 50–55°N: all of Belgium except the south-eastern Ardennes sliver,
 * which lives in E5_N45/E0_N45 (= the larger Tulpenrallye/France
 * routes pull those in via the route bbox).
 */
class RoutingDataRepository(private val segmentsDir: File) {

    sealed interface DownloadState {
        data class Progress(val tile: String, val bytesRead: Long, val totalBytes: Long) : DownloadState
        data class Done(val tile: String, val file: File) : DownloadState
        data class Failed(val tile: String, val message: String) : DownloadState
    }

    /** Tiles already on disk, with sizes. */
    fun installedTiles(): Map<String, Long> =
        segmentsDir.listFiles { f -> f.extension == "rd5" }
            ?.associate { it.name to it.length() }
            ?: emptyMap()

    fun deleteTile(tile: String): Boolean = File(segmentsDir, tile).delete()

    /**
     * Stream-download one tile, emitting progress. Safe to collect on
     * any dispatcher; IO happens on [Dispatchers.IO]. Existing tiles
     * are re-downloaded (the server rebuilds them weekly — callers
     * decide when a refresh is worth the bytes).
     */
    fun download(tile: String): Flow<DownloadState> = flow {
        val target = File(segmentsDir, tile)
        val partial = File(segmentsDir, "$tile.part")
        try {
            val connection = URL(Rd5Tiles.downloadUrl(tile)).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    emit(DownloadState.Failed(tile, "HTTP ${connection.responseCode}"))
                    return@flow
                }
                val total = connection.contentLengthLong
                var read = 0L
                var lastEmit = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            // Emit at most every 2 MB — collectors are
                            // UI progress bars, not byte counters.
                            if (read - lastEmit > 2L * 1024 * 1024) {
                                lastEmit = read
                                emit(DownloadState.Progress(tile, read, total))
                            }
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    emit(DownloadState.Failed(tile, "rename failed"))
                    return@flow
                }
                emit(DownloadState.Done(tile, target))
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            partial.delete()
            emit(DownloadState.Failed(tile, e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)
}
