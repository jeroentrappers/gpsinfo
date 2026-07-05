package be.appmire.gpsinfo.car

import org.json.JSONArray
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
    NAV_BANNER("nav_banner"),
    // Individually positionable pieces of the gauge cluster (cockpit).
    CL_SPEED("cl_speed"),
    CL_SPEED_TXT("cl_speed_txt"),
    CL_ENERGY("cl_energy"),
    CL_ENERGY_TXT("cl_energy_txt"),
    CL_CLOCK("cl_clock"),
    // Black-transparent gradient backgrounds, positionable on their own.
    SCRIM_TOP("scrim_top"),
    SCRIM_BOTTOM("scrim_bottom"),
    SCRIM_EDGES("scrim_edges");

    companion object {
        fun fromKey(k: String): OverlayElement? = entries.firstOrNull { it.key == k }
    }
}

/** Whether the user may remove (hide) this element in edit mode. The
 *  navigation banner stays so guidance can't be hidden; everything else,
 *  including speed + speed-limit, can be hidden when unwanted. */
val OverlayElement.removable: Boolean
    get() = this != OverlayElement.NAV_BANNER

/** Whether the user is actively navigating — overrides are stored per
 *  state so the layout can differ between turn-by-turn and free driving. */
enum class OverlayState(val key: String) { NAV("nav"), IDLE("idle") }

/**
 * Head-unit width class. Android Auto hosts split the surface — our app may
 * get the full width, or ~⅔ / ~⅓ of it (alongside the rail or a second pane).
 * The layout adapts and is customised per class.
 */
enum class WidthClass(val key: String) { FULL("full"), TWO_THIRDS("2_3"), ONE_THIRD("1_3") }

/** A user reposition/resize of one overlay: [dx]/[dy] are offsets as a
 *  fraction of surface width/height (resolution-independent), [scale] is a
 *  multiplier on the element's natural size. Identity = the designed
 *  position. */
data class LayoutOverride(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f) {
    val isIdentity: Boolean get() = dx == 0f && dy == 0f && scale == 1f
}

/** One layout bucket: a head-unit width class × nav state. */
data class OverlayBucket(val width: WidthClass, val state: OverlayState)

/**
 * Per-bucket (width-class × nav-state), per-element layout overrides plus the
 * set of removed (hidden) elements, persisted as a single JSON blob in
 * [be.appmire.gpsinfo.data.SettingsRepository]. Shape:
 * `{ "full": { "nav": { "cluster": {"dx":..}, "_hidden":["speed"] }, "idle": {..} }, "2_3": {..}, "1_3": {..} }`.
 */
data class CarOverlayLayout(
    val overrides: Map<OverlayBucket, Map<OverlayElement, LayoutOverride>> = emptyMap(),
    val hidden: Map<OverlayBucket, Set<OverlayElement>> = emptyMap(),
) {
    fun get(bucket: OverlayBucket, el: OverlayElement): LayoutOverride =
        overrides[bucket]?.get(el) ?: LayoutOverride()

    fun forBucket(bucket: OverlayBucket): Map<OverlayElement, LayoutOverride> =
        overrides[bucket] ?: emptyMap()

    fun hiddenFor(bucket: OverlayBucket): Set<OverlayElement> =
        hidden[bucket] ?: emptySet()

    fun isHidden(bucket: OverlayBucket, el: OverlayElement): Boolean =
        hidden[bucket]?.contains(el) == true

    /** Copy with one element's override replaced for [bucket]. */
    fun with(bucket: OverlayBucket, el: OverlayElement, ov: LayoutOverride): CarOverlayLayout {
        val map = overrides.toMutableMap()
        val forBucket = (map[bucket] ?: emptyMap()).toMutableMap()
        if (ov.isIdentity) forBucket.remove(el) else forBucket[el] = ov
        if (forBucket.isEmpty()) map.remove(bucket) else map[bucket] = forBucket
        return copy(overrides = map)
    }

    /** Copy with [el] marked removed (hidden) for [bucket]. */
    fun hide(bucket: OverlayBucket, el: OverlayElement): CarOverlayLayout {
        val map = hidden.toMutableMap()
        map[bucket] = (map[bucket] ?: emptySet()) + el
        return copy(hidden = map)
    }

    /** Copy with everything for [bucket] reset (overrides + removals). */
    fun clear(bucket: OverlayBucket): CarOverlayLayout = copy(
        overrides = overrides.toMutableMap().apply { remove(bucket) },
        hidden = hidden.toMutableMap().apply { remove(bucket) },
    )

    fun toJson(): String {
        val root = JSONObject()
        fun bucketObj(width: WidthClass): JSONObject =
            root.optJSONObject(width.key) ?: JSONObject().also { root.put(width.key, it) }
        for ((bucket, elements) in overrides) {
            if (elements.isEmpty()) continue
            val stateObj = JSONObject()
            for ((el, ov) in elements) {
                stateObj.put(
                    el.key,
                    JSONObject().put("dx", ov.dx.toDouble()).put("dy", ov.dy.toDouble()).put("scale", ov.scale.toDouble()),
                )
            }
            bucketObj(bucket.width).put(bucket.state.key, stateObj)
        }
        for ((bucket, hiddenEls) in hidden) {
            if (hiddenEls.isEmpty()) continue
            val wObj = bucketObj(bucket.width)
            val stateObj = wObj.optJSONObject(bucket.state.key) ?: JSONObject().also { wObj.put(bucket.state.key, it) }
            stateObj.put(HIDDEN_KEY, JSONArray(hiddenEls.map { it.key }))
        }
        return root.toString()
    }

    companion object {
        private const val HIDDEN_KEY = "_hidden"

        fun fromJson(raw: String?): CarOverlayLayout {
            if (raw.isNullOrBlank()) return CarOverlayLayout()
            return runCatching {
                val root = JSONObject(raw)
                val overrides = HashMap<OverlayBucket, Map<OverlayElement, LayoutOverride>>()
                val hidden = HashMap<OverlayBucket, Set<OverlayElement>>()
                for (width in WidthClass.entries) {
                    val wObj = root.optJSONObject(width.key) ?: continue
                    for (state in OverlayState.entries) {
                        val stateObj = wObj.optJSONObject(state.key) ?: continue
                        val bucket = OverlayBucket(width, state)
                        val elements = HashMap<OverlayElement, LayoutOverride>()
                        for (key in stateObj.keys()) {
                            if (key == HIDDEN_KEY) continue
                            val el = OverlayElement.fromKey(key) ?: continue
                            val o = stateObj.optJSONObject(key) ?: continue
                            elements[el] = LayoutOverride(
                                dx = o.optDouble("dx", 0.0).toFloat(),
                                dy = o.optDouble("dy", 0.0).toFloat(),
                                scale = o.optDouble("scale", 1.0).toFloat(),
                            )
                        }
                        if (elements.isNotEmpty()) overrides[bucket] = elements
                        stateObj.optJSONArray(HIDDEN_KEY)?.let { arr ->
                            val set = HashSet<OverlayElement>()
                            for (i in 0 until arr.length()) OverlayElement.fromKey(arr.optString(i))?.let { set.add(it) }
                            if (set.isNotEmpty()) hidden[bucket] = set
                        }
                    }
                }
                CarOverlayLayout(overrides, hidden)
            }.getOrDefault(CarOverlayLayout())
        }
    }
}
