package be.appmire.gpsinfo.data

import android.content.Context
import android.util.Log
import be.appmire.gpsinfo.data.model.Waypoint
import be.appmire.gpsinfo.data.model.WaypointMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists a flat list of [Waypoint]s as a single JSON file under
 * `filesDir/waypoints.json`. The list lives in memory once loaded; the
 * file is rewritten on every mutation (small lists, infrequent edits —
 * a streaming or partial-update scheme isn't worth the complexity).
 *
 * Thread safety: all mutations go through `synchronized(lock)` and the
 * resulting list is published through a [MutableStateFlow] so UI
 * observers see the new state atomically. No coroutine scope of our
 * own — callers (ViewModels) decide where to launch writes.
 */
class WaypointRepository(private val appContext: Context) {

    private val lock = Any()
    private val jsonFile: File by lazy { File(appContext.filesDir, "waypoints.json") }

    /** Media directory under `filesDir/waypoints/`. Created lazily on
     *  first capture — until then we never touch disk. */
    val mediaDir: File by lazy {
        File(appContext.filesDir, "waypoints").apply { mkdirs() }
    }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!jsonFile.exists()) {
                _waypoints.value = emptyList()
                return
            }
            runCatching {
                val text = jsonFile.readText(Charsets.UTF_8)
                val root = JSONArray(text)
                val list = ArrayList<Waypoint>(root.length())
                for (i in 0 until root.length()) {
                    list.add(parseWaypoint(root.getJSONObject(i)))
                }
                _waypoints.value = list
            }.onFailure { Log.w(TAG, "Failed to load waypoints.json", it) }
        }
    }

    private fun writeToDisk(list: List<Waypoint>) {
        synchronized(lock) {
            runCatching {
                val arr = JSONArray()
                for (w in list) arr.put(serialiseWaypoint(w))
                jsonFile.writeText(arr.toString(), Charsets.UTF_8)
            }.onFailure { Log.w(TAG, "Failed to write waypoints.json", it) }
        }
    }

    fun add(waypoint: Waypoint) {
        synchronized(lock) {
            val next = _waypoints.value + waypoint
            _waypoints.value = next
            writeToDisk(next)
        }
    }

    fun update(waypoint: Waypoint) {
        synchronized(lock) {
            val next = _waypoints.value.map { if (it.id == waypoint.id) waypoint else it }
            _waypoints.value = next
            writeToDisk(next)
        }
    }

    /** Remove a waypoint and its media file (if any). */
    fun delete(id: String) {
        synchronized(lock) {
            val toRemove = _waypoints.value.firstOrNull { it.id == id } ?: return
            val next = _waypoints.value.filterNot { it.id == id }
            _waypoints.value = next
            writeToDisk(next)
            // Best-effort media cleanup. A failure here just leaves a
            // dangling file; on next launch it'd be unreferenced.
            when (val m = toRemove.media) {
                is WaypointMedia.Voice -> File(mediaDir, m.fileName).delete()
                is WaypointMedia.Photo -> File(mediaDir, m.fileName).delete()
                WaypointMedia.None -> Unit
            }
        }
    }

    private fun serialiseWaypoint(w: Waypoint): JSONObject {
        val o = JSONObject()
        o.put("id", w.id)
        o.put("time", w.timeMillis)
        o.put("lat", w.latDeg)
        o.put("lon", w.lonDeg)
        if (w.eleMeters != null) o.put("ele", w.eleMeters)
        if (w.note.isNotEmpty()) o.put("note", w.note)
        when (val m = w.media) {
            WaypointMedia.None -> Unit
            is WaypointMedia.Voice -> {
                o.put("media_type", "voice")
                o.put("media_file", m.fileName)
                o.put("media_duration_ms", m.durationMs)
            }
            is WaypointMedia.Photo -> {
                o.put("media_type", "photo")
                o.put("media_file", m.fileName)
            }
        }
        return o
    }

    private fun parseWaypoint(o: JSONObject): Waypoint {
        val media = when (o.optString("media_type", "")) {
            "voice" -> WaypointMedia.Voice(
                fileName = o.getString("media_file"),
                durationMs = o.optLong("media_duration_ms", 0L),
            )
            "photo" -> WaypointMedia.Photo(fileName = o.getString("media_file"))
            else -> WaypointMedia.None
        }
        return Waypoint(
            id = o.getString("id"),
            timeMillis = o.getLong("time"),
            latDeg = o.getDouble("lat"),
            lonDeg = o.getDouble("lon"),
            eleMeters = if (o.has("ele")) o.optDouble("ele") else null,
            note = o.optString("note", ""),
            media = media,
        )
    }

    companion object {
        private const val TAG = "WaypointRepository"

        @Volatile private var instance: WaypointRepository? = null
        fun getInstance(context: Context): WaypointRepository =
            instance ?: synchronized(this) {
                instance ?: WaypointRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
