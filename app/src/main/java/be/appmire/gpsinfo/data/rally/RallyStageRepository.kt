package be.appmire.gpsinfo.data.rally

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Stores regularity stages as one JSON file per stage under
 * `filesDir/rally/` — same files-on-disk philosophy as the GPX trail
 * store: no database, trivially backed up, human-inspectable.
 *
 * The in-memory list loads lazily on first collection and is the
 * single source of truth afterwards; mutations write through to disk.
 */
class RallyStageRepository(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "rally").apply { mkdirs() }

    private val _stages = MutableStateFlow<List<RegularityStage>?>(null)

    /** All stored stages, name-sorted. Loads from disk on first use. */
    val stages: StateFlow<List<RegularityStage>?> = _stages.asStateFlow()

    suspend fun loadIfNeeded() {
        if (_stages.value != null) return
        withContext(Dispatchers.IO) {
            val loaded = dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    runCatching { RegularityStage.fromJson(JSONObject(f.readText())) }.getOrNull()
                }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            _stages.value = loaded
        }
    }

    suspend fun save(stage: RegularityStage): RegularityStage = withContext(Dispatchers.IO) {
        val withId = if (stage.id.isBlank()) stage.copy(id = UUID.randomUUID().toString()) else stage
        File(dir, "${withId.id}.json").writeText(withId.toJson().toString(2))
        _stages.value = ((_stages.value ?: emptyList()).filterNot { it.id == withId.id } + withId)
            .sortedBy { it.name.lowercase() }
        withId
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _stages.value = (_stages.value ?: emptyList()).filterNot { it.id == id }
    }
}
