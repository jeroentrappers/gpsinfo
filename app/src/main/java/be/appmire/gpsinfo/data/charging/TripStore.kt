package be.appmire.gpsinfo.data.charging

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's saved [SavedTrip]s as a JSON file in the app's private
 * storage (same pattern as PlacesRepository). Exposes a [StateFlow] the trips
 * screen observes, refreshed after each mutation.
 */
class TripStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "planned_trips.json")

    private val _trips = MutableStateFlow<List<SavedTrip>>(emptyList())
    val trips: StateFlow<List<SavedTrip>> = _trips

    suspend fun load() = withContext(Dispatchers.IO) {
        _trips.value = read()
    }

    suspend fun add(name: String, destLat: Double, destLon: Double, destName: String): SavedTrip =
        withContext(Dispatchers.IO) {
            val trip = SavedTrip(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { destName },
                destLat = destLat,
                destLon = destLon,
                destName = destName,
            )
            val next = read() + trip
            write(next)
            _trips.value = next
            trip
        }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val next = read().filterNot { it.id == id }
        write(next)
        _trips.value = next
    }

    private fun read(): List<SavedTrip> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    SavedTrip(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        destLat = o.optDouble("destLat"),
                        destLon = o.optDouble("destLon"),
                        destName = o.optString("destName"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write(trips: List<SavedTrip>) {
        val arr = JSONArray()
        trips.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("destLat", it.destLat)
                    .put("destLon", it.destLon)
                    .put("destName", it.destName),
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
