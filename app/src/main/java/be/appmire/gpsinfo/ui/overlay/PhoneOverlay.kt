package be.appmire.gpsinfo.ui.overlay

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import be.appmire.gpsinfo.car.LayoutOverride
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drag-to-position / pinch-to-scale layout editing for the **phone** overlays
 * (the turn-by-turn nav screen and the standalone cluster screen) — the phone
 * analogue of the Android Auto edit mode. Reuses the car's [LayoutOverride]
 * (dx/dy as a fraction of the parent, scale as a multiplier) but persists
 * independently and per *context* (surface × orientation), since the phone
 * geometry is unrelated to the head unit's.
 */

/** The positionable phone overlay elements. */
enum class PhoneOverlayElement(val key: String) {
    MANEUVER("maneuver"),
    LANES("lanes"),
    ETA("eta"),
    SPEED("speed"),
    // Independently positionable cluster gauges.
    CLUSTER_SPEED("cl_speed"),
    CLUSTER_POWER("cl_power"),
    CLUSTER_COMPASS("cl_compass");

    companion object {
        fun fromKey(k: String): PhoneOverlayElement? = entries.firstOrNull { it.key == k }
    }
}

/** Whether the user may remove this element while editing. Core turn-by-turn
 *  info (the maneuver card and the ETA) stays — it can be moved/scaled but not
 *  removed, so a layout can't hide essential guidance. */
val PhoneOverlayElement.removable: Boolean
    get() = this != PhoneOverlayElement.MANEUVER && this != PhoneOverlayElement.ETA

/** Overrides are stored per surface × orientation so each combination keeps
 *  its own layout (the nav cluster and the full-screen cluster differ, and so
 *  do portrait vs landscape). */
enum class PhoneOverlayContext(val key: String) {
    NAV_PORTRAIT("nav_p"),
    NAV_LANDSCAPE("nav_l"),
    CLUSTER_PORTRAIT("cl_p"),
    CLUSTER_LANDSCAPE("cl_l"),
    LIVEMAP_PORTRAIT("lm_p"),
    LIVEMAP_LANDSCAPE("lm_l");

    companion object {
        fun fromKey(k: String): PhoneOverlayContext? = entries.firstOrNull { it.key == k }
    }
}

/** Per-context, per-element overrides (offset/scale) plus the set of removed
 *  (hidden) elements, persisted as one JSON blob in SettingsRepository. */
@Immutable
data class PhoneOverlayLayout(
    val byContext: Map<PhoneOverlayContext, Map<PhoneOverlayElement, LayoutOverride>> = emptyMap(),
    val hiddenByContext: Map<PhoneOverlayContext, Set<PhoneOverlayElement>> = emptyMap(),
) {
    fun get(ctx: PhoneOverlayContext, el: PhoneOverlayElement): LayoutOverride =
        byContext[ctx]?.get(el) ?: LayoutOverride()

    fun isHidden(ctx: PhoneOverlayContext, el: PhoneOverlayElement): Boolean =
        hiddenByContext[ctx]?.contains(el) == true

    /** Copy with one element's override replaced for [ctx]. */
    fun with(ctx: PhoneOverlayContext, el: PhoneOverlayElement, ov: LayoutOverride): PhoneOverlayLayout {
        val contexts = byContext.toMutableMap()
        val forCtx = (contexts[ctx] ?: emptyMap()).toMutableMap()
        if (ov.isIdentity) forCtx.remove(el) else forCtx[el] = ov
        if (forCtx.isEmpty()) contexts.remove(ctx) else contexts[ctx] = forCtx
        return copy(byContext = contexts)
    }

    /** Copy with [el] marked removed (hidden) for [ctx]. */
    fun hide(ctx: PhoneOverlayContext, el: PhoneOverlayElement): PhoneOverlayLayout {
        val hidden = hiddenByContext.toMutableMap()
        hidden[ctx] = (hidden[ctx] ?: emptySet()) + el
        return copy(hiddenByContext = hidden)
    }

    /** Copy with everything for [ctx] reset to defaults (overrides + removals). */
    fun cleared(ctx: PhoneOverlayContext): PhoneOverlayLayout = copy(
        byContext = byContext.toMutableMap().apply { remove(ctx) },
        hiddenByContext = hiddenByContext.toMutableMap().apply { remove(ctx) },
    )

    fun toJson(): String {
        val root = JSONObject()
        for (ctx in (byContext.keys + hiddenByContext.keys)) {
            val ctxObj = JSONObject()
            byContext[ctx]?.forEach { (el, ov) ->
                ctxObj.put(
                    el.key,
                    JSONObject()
                        .put("dx", ov.dx.toDouble())
                        .put("dy", ov.dy.toDouble())
                        .put("scale", ov.scale.toDouble()),
                )
            }
            hiddenByContext[ctx]?.takeIf { it.isNotEmpty() }?.let { hidden ->
                ctxObj.put("_hidden", JSONArray(hidden.map { it.key }))
            }
            if (ctxObj.length() > 0) root.put(ctx.key, ctxObj)
        }
        return root.toString()
    }

    companion object {
        private const val HIDDEN_KEY = "_hidden"

        fun fromJson(raw: String?): PhoneOverlayLayout {
            if (raw.isNullOrBlank()) return PhoneOverlayLayout()
            return runCatching {
                val root = JSONObject(raw)
                val byContext = HashMap<PhoneOverlayContext, Map<PhoneOverlayElement, LayoutOverride>>()
                val hiddenByContext = HashMap<PhoneOverlayContext, Set<PhoneOverlayElement>>()
                for (ctx in PhoneOverlayContext.entries) {
                    val ctxObj = root.optJSONObject(ctx.key) ?: continue
                    val elements = HashMap<PhoneOverlayElement, LayoutOverride>()
                    for (key in ctxObj.keys()) {
                        if (key == HIDDEN_KEY) continue
                        val el = PhoneOverlayElement.fromKey(key) ?: continue
                        val o = ctxObj.optJSONObject(key) ?: continue
                        elements[el] = LayoutOverride(
                            dx = o.optDouble("dx", 0.0).toFloat(),
                            dy = o.optDouble("dy", 0.0).toFloat(),
                            scale = o.optDouble("scale", 1.0).toFloat(),
                        )
                    }
                    if (elements.isNotEmpty()) byContext[ctx] = elements
                    val hiddenArr = ctxObj.optJSONArray(HIDDEN_KEY)
                    if (hiddenArr != null) {
                        val hidden = HashSet<PhoneOverlayElement>()
                        for (i in 0 until hiddenArr.length()) {
                            PhoneOverlayElement.fromKey(hiddenArr.optString(i))?.let { hidden.add(it) }
                        }
                        if (hidden.isNotEmpty()) hiddenByContext[ctx] = hidden
                    }
                }
                PhoneOverlayLayout(byContext, hiddenByContext)
            }.getOrDefault(PhoneOverlayLayout())
        }
    }
}

/**
 * What [Modifier.overlayElement] needs from the hosting screen. Provided via
 * [LocalOverlayEdit] so individual overlay composables only have to tag
 * themselves with their element — they don't thread editing state through.
 */
@Immutable
data class OverlayEditScope(
    val editing: Boolean,
    val context: PhoneOverlayContext,
    val layout: PhoneOverlayLayout,
    /** Pixel size of the coordinate space the overrides are relative to
     *  (the padded content box). */
    val parentPx: IntSize,
    val onChange: (PhoneOverlayElement, LayoutOverride) -> Unit,
    /** Currently selected element (tapped in edit mode), or null. */
    val selected: PhoneOverlayElement? = null,
    val onSelect: (PhoneOverlayElement?) -> Unit = {},
)

/** Null when no editor is hosting — [Modifier.overlayElement] is then a no-op,
 *  so the same composables work unchanged outside an editable screen. */
val LocalOverlayEdit = compositionLocalOf<OverlayEditScope?> { null }

/** Whether [element] should be drawn — false once the user removes it (until
 *  the layout is reset). True when no editor is hosting. The hosting screen
 *  uses this to skip composing removed elements. */
@Composable
fun overlayElementVisible(element: PhoneOverlayElement): Boolean {
    val scope = LocalOverlayEdit.current ?: return true
    return !scope.layout.isHidden(scope.context, element)
}

private val EditBorder = Color(0x99FFFFFF)
private val SelectBorder = Color(0xFF7FE3FF)

/**
 * Tag a composable as a positionable overlay element. Applies its saved
 * offset + scale always; while [OverlayEditScope.editing] is on, it also
 * accepts one-finger drag (reposition) and two-finger pinch (resize) and
 * draws a selection outline. No-op when no [LocalOverlayEdit] is provided.
 */
fun Modifier.overlayElement(element: PhoneOverlayElement): Modifier = composed {
    val scope = LocalOverlayEdit.current ?: return@composed this
    val w = scope.parentPx.width.coerceAtLeast(1)
    val h = scope.parentPx.height.coerceAtLeast(1)
    val override = scope.layout.get(scope.context, element)
    // Read the latest override inside the long-lived gesture coroutine so pan
    // and zoom accumulate on top of each other rather than the start value.
    val live = rememberUpdatedState(override)

    var m: Modifier = Modifier.graphicsLayer {
        translationX = override.dx * w
        translationY = override.dy * h
        scaleX = override.scale
        scaleY = override.scale
    }
    if (scope.editing) {
        val selected = scope.selected == element
        m = m
            .border(
                if (selected) 2.5.dp else 1.5.dp,
                if (selected) SelectBorder else EditBorder,
                RoundedCornerShape(10.dp),
            )
            .pointerInput(element) {
                detectTapGestures { scope.onSelect(element) }
            }
            .pointerInput(element, w, h) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scope.onSelect(element)
                    val o = live.value
                    scope.onChange(
                        element,
                        o.copy(
                            dx = o.dx + pan.x / w,
                            dy = o.dy + pan.y / h,
                            scale = (o.scale * zoom).coerceIn(0.5f, 2.5f),
                        ),
                    )
                }
            }
    }
    this.then(m)
}
