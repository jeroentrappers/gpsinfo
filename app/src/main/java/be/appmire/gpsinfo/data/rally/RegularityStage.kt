package be.appmire.gpsinfo.data.rally

import org.json.JSONArray
import org.json.JSONObject

/**
 * A speed change in a regularity stage roadbook: "from km [atKm],
 * hold an average of [speedKmh]". The first change of a stage is at
 * km 0.0 — the imposed speed off the start line.
 */
data class SpeedChange(
    val atKm: Double,
    val speedKmh: Double,
)

/**
 * One "regelmatigheidsproef" (regularity test / RT): a stage where the
 * crew must hold imposed average speeds between distance marks. The
 * whole game is matching the organiser's ideal time at every point —
 * checkpoints are secret, so the target must hold continuously, not
 * just at the finish.
 *
 * The model is the roadbook's own shape: an ordered list of
 * [SpeedChange] breakpoints plus an optional known total length. All
 * the timing math is pure and lives here so it's unit-testable without
 * Android types.
 */
data class RegularityStage(
    val id: String,
    val name: String,
    /** Sorted by [SpeedChange.atKm]; first entry must be at 0.0. */
    val changes: List<SpeedChange>,
    /** Total stage length when the roadbook states it; null = open-ended. */
    val lengthKm: Double? = null,
) {

    /** The imposed average speed at [km] into the stage. */
    fun targetSpeedKmhAt(km: Double): Double {
        if (changes.isEmpty()) return 0.0
        var current = changes.first().speedKmh
        for (c in changes) {
            if (c.atKm <= km) current = c.speedKmh else break
        }
        return current
    }

    /**
     * The organiser's ideal elapsed time at [km] into the stage, in
     * seconds: piecewise integration of segment-length / imposed-speed
     * over the breakpoints crossed so far.
     */
    fun targetElapsedSecondsAt(km: Double): Double {
        if (changes.isEmpty() || km <= 0.0) return 0.0
        var seconds = 0.0
        for (i in changes.indices) {
            val segStart = changes[i].atKm
            if (segStart >= km) break
            val segEnd = if (i + 1 < changes.size) minOf(changes[i + 1].atKm, km) else km
            val speed = changes[i].speedKmh
            if (speed > 0.0 && segEnd > segStart) {
                seconds += (segEnd - segStart) / speed * 3600.0
            }
        }
        return seconds
    }

    /** Ideal total duration, when the stage length is known. */
    fun targetTotalSeconds(): Double? = lengthKm?.let { targetElapsedSecondsAt(it) }

    fun isComplete(km: Double): Boolean = lengthKm != null && km >= lengthKm

    // ── JSON (org.json — no serialization dependency) ──────────────

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        lengthKm?.let { put("lengthKm", it) }
        put(
            "changes",
            JSONArray().apply {
                changes.forEach { c ->
                    put(JSONObject().apply {
                        put("atKm", c.atKm)
                        put("speedKmh", c.speedKmh)
                    })
                }
            },
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): RegularityStage {
            val arr = obj.getJSONArray("changes")
            val changes = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                SpeedChange(atKm = c.getDouble("atKm"), speedKmh = c.getDouble("speedKmh"))
            }.sortedBy { it.atKm }
            return RegularityStage(
                id = obj.getString("id"),
                name = obj.getString("name"),
                changes = changes,
                lengthKm = if (obj.has("lengthKm")) obj.getDouble("lengthKm") else null,
            )
        }
    }
}
