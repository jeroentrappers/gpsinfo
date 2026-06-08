package be.appmire.gpsinfo.data.nav

import org.json.JSONObject

/** What a saved place is to the user. */
enum class PlaceRole { HOME, WORK, LABELED, RECENT }

/**
 * A destination the user can pick again without typing — a recently
 * driven address, or one promoted to Home / Work / a custom label.
 *
 * [name] is the headline (the address or the label), [detail] the
 * secondary line (city/region). [role] decides pinning + sort; [label]
 * carries the custom name when [role] is [PlaceRole.LABELED].
 */
data class SavedPlace(
    val id: String,
    val lat: Double,
    val lon: Double,
    val name: String,
    val detail: String = "",
    val role: PlaceRole = PlaceRole.RECENT,
    val label: String = "",
    val updatedAt: Long = 0L,
) {
    /** Headline shown to the user — the role name for Home/Work, the
     *  custom label when labelled, otherwise the place name. */
    val displayTitle: String
        get() = when (role) {
            PlaceRole.HOME -> "Home"
            PlaceRole.WORK -> "Work"
            PlaceRole.LABELED -> label.ifBlank { name }
            PlaceRole.RECENT -> name
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lat", lat)
        put("lon", lon)
        put("name", name)
        put("detail", detail)
        put("role", role.name)
        put("label", label)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SavedPlace = SavedPlace(
            id = o.getString("id"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            name = o.getString("name"),
            detail = o.optString("detail"),
            role = runCatching { PlaceRole.valueOf(o.optString("role")) }
                .getOrDefault(PlaceRole.RECENT),
            label = o.optString("label"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}
