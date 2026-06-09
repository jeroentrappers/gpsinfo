package be.appmire.gpsinfo.obd

import org.json.JSONObject

/**
 * A confirmed wiring of [ObdRole]s to concrete ELM requests for one
 * vehicle, keyed by VIN (preferred) or adapter MAC when no VIN is
 * available. Persisted so a known car is recognised and its sensors
 * re-used without re-probing.
 */
data class ObdMapping(
    val vehicleKey: String,
    val profileId: String,
    /** role → ELM request to poll (may be a composite "ATSH…;22…"). */
    val roles: Map<ObdRole, String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("vehicleKey", vehicleKey)
        put("profileId", profileId)
        put(
            "roles",
            JSONObject().apply { roles.forEach { (r, req) -> put(r.name, req) } },
        )
    }

    companion object {
        fun fromJson(o: JSONObject): ObdMapping {
            val rolesObj = o.optJSONObject("roles") ?: JSONObject()
            val roles = buildMap {
                rolesObj.keys().forEach { name ->
                    runCatching { ObdRole.valueOf(name) }.getOrNull()
                        ?.let { put(it, rolesObj.getString(name)) }
                }
            }
            return ObdMapping(
                vehicleKey = o.getString("vehicleKey"),
                profileId = o.optString("profileId", ObdProfiles.GENERIC.id),
                roles = roles,
            )
        }
    }
}

/** One role's probe result on a specific profile. */
data class RoleReading(
    val role: ObdRole,
    val profileId: String,
    val request: String,
    val value: Double?,
    val rawHex: String?,
    val live: Boolean,
)

/**
 * Turns probe role-readings into a suggested mapping — the "smart" half
 * of probe-then-confirm. Pure and unit-tested.
 */
object MappingSuggester {

    data class Suggestion(val profileId: String, val mapping: ObdMapping?)

    /**
     * Pick the best profile (the make profile that lit up the most
     * energy-dial roles, else generic) and, per role, the live reading
     * from that profile, falling back to a generic live reading.
     */
    fun suggest(vehicleKey: String, readings: List<RoleReading>): Suggestion {
        val live = readings.filter { it.live }
        val bestMake = live
            .filter { it.profileId != ObdProfiles.GENERIC.id }
            .groupBy { it.profileId }
            .maxByOrNull { e -> e.value.count { it.role.energyDial } }
        val chosen = if (bestMake != null && bestMake.value.any { it.role.energyDial }) {
            bestMake.key
        } else {
            ObdProfiles.GENERIC.id
        }
        val roles = ObdRole.entries.mapNotNull { role ->
            val r = live.firstOrNull { it.role == role && it.profileId == chosen }
                ?: live.firstOrNull { it.role == role && it.profileId == ObdProfiles.GENERIC.id }
            r?.let { role to it.request }
        }.toMap()
        return Suggestion(chosen, if (roles.isEmpty()) null else ObdMapping(vehicleKey, chosen, roles))
    }
}
