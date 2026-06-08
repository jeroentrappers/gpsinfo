package be.appmire.gpsinfo.data.nav

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Saved + recent destinations, persisted as one JSON file. Recently
 * driven places are auto-recorded (deduped by proximity, capped); any
 * of them can be promoted to Home, Work, or a custom label so it pins
 * to the top and survives the recents cap.
 *
 * The single source of truth for both the phone picker and the car
 * Places screen — the car offers exactly this list, no keyboard.
 *
 * Pure-ish: all mutation goes through the in-memory list + a JSON
 * write; the [SavedPlace] math (dedupe, role rules, sort) is static
 * and unit-tested.
 */
class PlacesRepository(context: Context) {

    private val file = File(context.applicationContext.filesDir, "places.json")
    private val _places = MutableStateFlow(load())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    /** Record a just-navigated destination. Bumps an existing nearby
     *  place (keeping its role/label) or adds a new RECENT, then
     *  trims the recents to the cap. No-op for blank names. */
    fun recordVisit(lat: Double, lon: Double, name: String, detail: String, now: Long) {
        if (name.isBlank()) return
        _places.value = recorded(_places.value, lat, lon, name, detail, now)
        persist()
    }

    fun setHome(id: String) = applyRole(id, PlaceRole.HOME)
    fun setWork(id: String) = applyRole(id, PlaceRole.WORK)

    fun setLabel(id: String, label: String) {
        _places.value = _places.value.map {
            if (it.id == id) it.copy(role = PlaceRole.LABELED, label = label) else it
        }.let(::sorted)
        persist()
    }

    /** Demote back to a plain recent. */
    fun clearRole(id: String) {
        _places.value = _places.value.map {
            if (it.id == id) it.copy(role = PlaceRole.RECENT, label = "") else it
        }.let(::sorted)
        persist()
    }

    fun delete(id: String) {
        _places.value = _places.value.filterNot { it.id == id }
        persist()
    }

    private fun applyRole(id: String, role: PlaceRole) {
        _places.value = sorted(
            _places.value.map {
                when {
                    it.id == id -> it.copy(role = role, label = "")
                    // Only one Home and one Work — demote the previous holder.
                    it.role == role -> it.copy(role = PlaceRole.RECENT)
                    else -> it
                }
            }
        )
        persist()
    }

    private fun load(): List<SavedPlace> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            sorted((0 until arr.length()).map { SavedPlace.fromJson(arr.getJSONObject(it)) })
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val arr = JSONArray()
        _places.value.forEach { arr.put(it.toJson()) }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        const val RECENTS_CAP = 20
        /** Two destinations within this distance are "the same place". */
        const val DEDUPE_METERS = 60.0

        /** Pure record-visit transform — testable without Android. */
        fun recorded(
            current: List<SavedPlace>,
            lat: Double,
            lon: Double,
            name: String,
            detail: String,
            now: Long,
        ): List<SavedPlace> {
            val existing = current.firstOrNull { metersBetween(it.lat, it.lon, lat, lon) < DEDUPE_METERS }
            val updated = if (existing != null) {
                current.map {
                    if (it.id == existing.id) it.copy(updatedAt = now) else it
                }
            } else {
                current + SavedPlace(
                    id = UUID.randomUUID().toString(),
                    lat = lat, lon = lon, name = name, detail = detail,
                    role = PlaceRole.RECENT, updatedAt = now,
                )
            }
            return sorted(trimRecents(updated))
        }

        /** Keep only the newest [RECENTS_CAP] recents; never drop a
         *  Home/Work/labelled place. */
        private fun trimRecents(places: List<SavedPlace>): List<SavedPlace> {
            val recents = places.filter { it.role == PlaceRole.RECENT }
                .sortedByDescending { it.updatedAt }
            if (recents.size <= RECENTS_CAP) return places
            val keep = recents.take(RECENTS_CAP).map { it.id }.toSet()
            return places.filter { it.role != PlaceRole.RECENT || it.id in keep }
        }

        /** Home, Work, then labelled (alpha), then recents (newest
         *  first) — the order both the phone list and the car screen
         *  render. */
        fun sorted(places: List<SavedPlace>): List<SavedPlace> =
            places.sortedWith(
                compareBy<SavedPlace> {
                    when (it.role) {
                        PlaceRole.HOME -> 0
                        PlaceRole.WORK -> 1
                        PlaceRole.LABELED -> 2
                        PlaceRole.RECENT -> 3
                    }
                }
                    .thenBy { if (it.role == PlaceRole.LABELED) it.label.lowercase() else "" }
                    .thenByDescending { if (it.role == PlaceRole.RECENT) it.updatedAt else 0L }
            )

        private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1)
            val dl = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
