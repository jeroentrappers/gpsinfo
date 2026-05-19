package com.appmire.gpsinfo.data

import android.content.Context
import android.util.Log
import com.appmire.gpsinfo.data.gpx.GpxIo
import com.appmire.gpsinfo.data.gpx.TrailSimplifier
import com.appmire.gpsinfo.data.model.Trail
import com.appmire.gpsinfo.data.model.TrailPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistence + lookup of recorded trails. Files live under
 * `<filesDir>/trails/<id>.gpx`; the filename (sans suffix) is the [Trail.id].
 *
 * Tests can substitute via [TestDataSourceOverride.trails].
 */
interface TrailDataSource {
    /** Live list of all stored trails. Re-emits after every save/delete. */
    val trails: Flow<List<TrailSummary>>

    /** Load full trail content (with all points). */
    suspend fun load(id: String): Trail?

    /** Persist a trail. Returns the stored id. */
    suspend fun save(name: String, points: List<TrailPoint>): String

    suspend fun delete(id: String)

    /** Rename a stored trail in place. The on-disk filename (and thus
     *  [Trail.id]) doesn't change — only the human-readable name does.
     *  Returns false if the trail doesn't exist. */
    suspend fun rename(id: String, newName: String): Boolean

    /**
     * Backing GPX file on disk — exposed so callers can hand it to
     * FileProvider for a share-out intent. Tests can implement this by
     * writing a tmp file on demand. Returns null if the trail doesn't
     * exist.
     */
    fun gpxFile(id: String): java.io.File?

    /**
     * Read an externally-sourced GPX stream, parse it, and persist as a
     * new trail. The [suggestedName] is used when the imported file has
     * no `<metadata><name>` of its own. Returns the new trail id, or
     * null when parsing yields zero points.
     */
    suspend fun importGpx(input: java.io.InputStream, suggestedName: String): String?

    /**
     * Simplify a stored trail with Ramer–Douglas–Peucker at [epsilonMeters].
     *
     * @param replace  when true, overwrites the original file in place
     *                 and returns the same id. When false, persists as a
     *                 new sibling trail named "<original> (simplified)"
     *                 and returns the new id.
     *
     * Returns null if the trail doesn't exist or simplification would
     * leave fewer than 2 points (a degenerate case we refuse to write).
     */
    suspend fun simplify(id: String, epsilonMeters: Double, replace: Boolean): String?
}

/**
 * Lightweight metadata for the trails list — avoids loading every
 * point in every file just to render a list row.
 */
data class TrailSummary(
    val id: String,
    val name: String,
    val startTimeMillis: Long?,
    val endTimeMillis: Long?,
    val pointCount: Int,
    val distanceMeters: Double,
)

class TrailRepository(context: Context) : TrailDataSource {

    private val root: File = File(context.filesDir, DIR).apply { mkdirs() }
    private val _trails = MutableStateFlow<List<TrailSummary>>(emptyList())

    override val trails: Flow<List<TrailSummary>> = _trails.asStateFlow()

    init {
        _trails.value = scanSummaries()
    }

    override suspend fun load(id: String): Trail? = withContext(Dispatchers.IO) {
        val file = File(root, "$id.gpx").takeIf { it.exists() } ?: return@withContext null
        return@withContext try {
            file.inputStream().use { stream ->
                val parsed = GpxIo.parse(stream)
                Trail(id = id, name = parsed.name ?: id, points = parsed.points)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse trail $id", e)
            null
        }
    }

    override suspend fun save(name: String, points: List<TrailPoint>): String = withContext(Dispatchers.IO) {
        val id = generateId(name)
        val file = File(root, "$id.gpx")
        val trail = Trail(id = id, name = name.ifBlank { id }, points = points)
        // Write to a temp file first so a crash mid-write doesn't leave
        // half a GPX file lying around.
        val tmp = File(root, "$id.gpx.part")
        tmp.outputStream().use { GpxIo.write(trail, it) }
        if (!tmp.renameTo(file)) {
            // renameTo can fail across filesystems; copy + delete as fallback.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _trails.value = scanSummaries()
        id
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, "$id.gpx").delete()
        _trails.value = scanSummaries()
        Unit
    }

    override suspend fun rename(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val existing = load(id) ?: return@withContext false
        val cleaned = newName.trim().ifBlank { return@withContext false }
        // The id is the on-disk filename and stays stable; only the GPX
        // `<name>` metadata changes. Same temp-file-then-rename dance as
        // save() so a crash mid-write can't corrupt the original.
        val file = File(root, "$id.gpx")
        val tmp = File(root, "$id.gpx.part")
        tmp.outputStream().use { GpxIo.write(existing.copy(name = cleaned), it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        _trails.value = scanSummaries()
        true
    }

    override fun gpxFile(id: String): File? =
        File(root, "$id.gpx").takeIf { it.exists() }

    override suspend fun importGpx(
        input: java.io.InputStream,
        suggestedName: String,
    ): String? = withContext(Dispatchers.IO) {
        val parsed = try {
            input.use { GpxIo.parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse incoming GPX", e)
            return@withContext null
        }
        if (parsed.points.isEmpty()) return@withContext null
        save(name = parsed.name ?: suggestedName, points = parsed.points)
    }

    override suspend fun simplify(
        id: String,
        epsilonMeters: Double,
        replace: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val original = load(id) ?: return@withContext null
        val reduced = TrailSimplifier.simplify(original.points, epsilonMeters)
        if (reduced.size < 2) return@withContext null

        if (replace) {
            // Overwrite via the same temp-file-rename dance save() uses,
            // so a crash here can't corrupt the original. We bypass save()
            // because that one always allocates a fresh id from the wall
            // clock, which would change the filename.
            val file = File(root, "$id.gpx")
            val tmp = File(root, "$id.gpx.part")
            val trail = Trail(id = id, name = original.name, points = reduced)
            tmp.outputStream().use { GpxIo.write(trail, it) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            _trails.value = scanSummaries()
            id
        } else {
            save(name = "${original.name} (simplified)", points = reduced)
        }
    }

    /**
     * Read every `*.gpx` file and produce a [TrailSummary] for each. We
     * still parse the points so distance + duration are accurate, but we
     * discard the points after summarising so the cached list stays small.
     */
    private fun scanSummaries(): List<TrailSummary> {
        val files = root.listFiles { _, name -> name.endsWith(".gpx") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                f.inputStream().use { stream ->
                    val parsed = GpxIo.parse(stream)
                    val id = f.nameWithoutExtension
                    val tmpTrail = Trail(id = id, name = parsed.name ?: id, points = parsed.points)
                    TrailSummary(
                        id = id,
                        name = tmpTrail.name,
                        startTimeMillis = tmpTrail.startTimeMillis,
                        endTimeMillis = tmpTrail.endTimeMillis,
                        pointCount = parsed.points.size,
                        distanceMeters = tmpTrail.distanceMeters,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unparseable trail ${f.name}", e)
                null
            }
        }.sortedByDescending { it.startTimeMillis ?: 0L }
    }

    /**
     * Filenames combine the start-of-recording epoch and a sanitised
     * version of the user-provided name. Epoch prefix keeps natural
     * sort = chronological; the name suffix is human-recognisable when
     * the user exports the file to another tool.
     */
    private fun generateId(name: String): String {
        val nameSlug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "trail" }
        return "${System.currentTimeMillis()}-$nameSlug"
    }

    private companion object {
        const val DIR = "trails"
        const val TAG = "TrailRepository"
    }
}
