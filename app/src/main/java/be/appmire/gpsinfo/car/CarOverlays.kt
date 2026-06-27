package be.appmire.gpsinfo.car

import org.json.JSONObject

/**
 * The optional overlays the car surface may draw on top of the navigation
 * map, and where the user has dragged/scaled each one.
 *
 * Play's Auto App Quality policy for the NAVIGATION category forbids drawing
 * anything other than the map or driving-related information on the
 * navigation template. So the navigation baseline — base map, route line,
 * breadcrumb, vehicle puck, the nav-status/turn banner, and a minimal
 * speed + speed-limit badge (both explicitly permitted as driving info) —
 * always draws and isn't represented here. Everything in [CarOverlayConfig]
 * is opt-in and defaults OFF, so a fresh install presents a
 * navigation-only surface.
 */
data class CarOverlayConfig(
    /** Minimal speed readout (km/h). Driving info — on by default. */
    val speed: Boolean = true,
    /** Posted speed-limit sign. Driving info — on by default. */
    val speedLimit: Boolean = true,
    /** Full gauge cluster (speed + power cockpit/integrated dials). */
    val cluster: Boolean = false,
    /** Compass rose + G-meter dial inside the cluster. */
    val compass: Boolean = false,
    /** Recording trip strip (distance · duration + REC dot). */
    val recordingStrip: Boolean = false,
    /** Rally / regularity-test status panel. */
    val rallyPanel: Boolean = false,
)

/** The draggable/scalable overlay elements (Phase 4). The navigation
 *  baseline (map/route/puck) is not user-positionable. */
enum class OverlayElement(val key: String) {
    CLUSTER("cluster"),
    COMPASS("compass"),
    SPEED("speed"),
    SPEED_LIMIT("speed_limit"),
    RECORDING_STRIP("recording"),
    RALLY_PANEL("rally"),
    NAV_BANNER("nav_banner");

    companion object {
        fun fromKey(k: String): OverlayElement? = entries.firstOrNull { it.key == k }
    }
}

/** Whether the user is actively navigating — overrides are stored per
 *  state so the layout can differ between turn-by-turn and free driving. */
enum class OverlayState(val key: String) { NAV("nav"), IDLE("idle") }

/** A user reposition/resize of one overlay: [dx]/[dy] are offsets as a
 *  fraction of surface width/height (resolution-independent), [scale] is a
 *  multiplier on the element's natural size. Identity = the designed
 *  position. */
data class LayoutOverride(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f) {
    val isIdentity: Boolean get() = dx == 0f && dy == 0f && scale == 1f
}

/**
 * Per-state, per-element layout overrides, persisted as a single JSON blob
 * in [be.appmire.gpsinfo.data.SettingsRepository]. Shape:
 * `{ "nav": { "cluster": {"dx":..,"dy":..,"scale":..}, ... }, "idle": {...} }`.
 */
data class CarOverlayLayout(
    val byState: Map<OverlayState, Map<OverlayElement, LayoutOverride>> = emptyMap(),
) {
    fun get(state: OverlayState, el: OverlayElement): LayoutOverride =
        byState[state]?.get(el) ?: LayoutOverride()

    fun forState(state: OverlayState): Map<OverlayElement, LayoutOverride> =
        byState[state] ?: emptyMap()

    /** Return a copy with one element's override replaced for [state]. */
    fun with(state: OverlayState, el: OverlayElement, ov: LayoutOverride): CarOverlayLayout {
        val states = byState.toMutableMap()
        val forState = (states[state] ?: emptyMap()).toMutableMap()
        if (ov.isIdentity) forState.remove(el) else forState[el] = ov
        if (forState.isEmpty()) states.remove(state) else states[state] = forState
        return CarOverlayLayout(states)
    }

    fun toJson(): String {
        val root = JSONObject()
        for ((state, elements) in byState) {
            if (elements.isEmpty()) continue
            val stateObj = JSONObject()
            for ((el, ov) in elements) {
                stateObj.put(
                    el.key,
                    JSONObject()
                        .put("dx", ov.dx.toDouble())
                        .put("dy", ov.dy.toDouble())
                        .put("scale", ov.scale.toDouble()),
                )
            }
            root.put(state.key, stateObj)
        }
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String?): CarOverlayLayout {
            if (raw.isNullOrBlank()) return CarOverlayLayout()
            return runCatching {
                val root = JSONObject(raw)
                val byState = HashMap<OverlayState, Map<OverlayElement, LayoutOverride>>()
                for (state in OverlayState.entries) {
                    val stateObj = root.optJSONObject(state.key) ?: continue
                    val elements = HashMap<OverlayElement, LayoutOverride>()
                    for (key in stateObj.keys()) {
                        val el = OverlayElement.fromKey(key) ?: continue
                        val o = stateObj.optJSONObject(key) ?: continue
                        elements[el] = LayoutOverride(
                            dx = o.optDouble("dx", 0.0).toFloat(),
                            dy = o.optDouble("dy", 0.0).toFloat(),
                            scale = o.optDouble("scale", 1.0).toFloat(),
                        )
                    }
                    if (elements.isNotEmpty()) byState[state] = elements
                }
                CarOverlayLayout(byState)
            }.getOrDefault(CarOverlayLayout())
        }
    }
}
