package be.appmire.gpsinfo.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists confirmed [ObdMapping]s plus which adapter + vehicle is
 * "active" (the one the live feed auto-connects to). One small JSON
 * file, mirroring PlacesRepository's storage style.
 *
 * Configuring an active adapter here *is* the opt-in for auto-connect:
 * nothing touches Bluetooth on launch unless the user saved one in the
 * OBD Lab.
 */
class ObdMappingRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, "obd-mappings.json")

    data class Store(
        val activeAddress: String?,
        val activeKey: String?,
        val mappings: List<ObdMapping>,
    )

    @Synchronized
    fun load(): Store {
        if (!file.exists()) return Store(null, null, emptyList())
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("mappings") ?: JSONArray()
            val mappings = (0 until arr.length()).map { ObdMapping.fromJson(arr.getJSONObject(it)) }
            Store(
                activeAddress = root.optString("activeAddress").ifEmpty { null },
                activeKey = root.optString("activeKey").ifEmpty { null },
                mappings = mappings,
            )
        }.getOrElse { Store(null, null, emptyList()) }
    }

    /** Upsert [mapping] (by vehicleKey) and mark it + [address] active. */
    @Synchronized
    fun saveActive(mapping: ObdMapping, address: String) {
        val cur = load()
        val merged = cur.mappings.filter { it.vehicleKey != mapping.vehicleKey } + mapping
        write(Store(address, mapping.vehicleKey, merged))
    }

    @Synchronized
    fun clearActive() {
        val cur = load()
        write(cur.copy(activeAddress = null, activeKey = null))
    }

    fun activeMapping(): ObdMapping? {
        val s = load()
        return s.mappings.firstOrNull { it.vehicleKey == s.activeKey }
    }

    private fun write(store: Store) {
        val root = JSONObject().apply {
            store.activeAddress?.let { put("activeAddress", it) }
            store.activeKey?.let { put("activeKey", it) }
            put("mappings", JSONArray().apply { store.mappings.forEach { put(it.toJson()) } })
        }
        runCatching { file.writeText(root.toString()) }
    }
}
