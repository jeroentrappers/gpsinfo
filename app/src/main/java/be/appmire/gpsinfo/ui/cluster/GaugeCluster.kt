package be.appmire.gpsinfo.ui.cluster

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import be.appmire.gpsinfo.car.CarInstruments
import be.appmire.gpsinfo.car.ClusterData
import kotlin.math.min

/** Which of the two [CarInstruments] layouts to render. */
enum class ClusterMode {
    /** Pick by aspect ratio, exactly like the car surface does. */
    AUTO,
    /** Wide edge-HUD (speed scale left, power right, compass lower-right). */
    COCKPIT,
    /** Self-contained single gauge — the layout that adapts to a tall phone. */
    INTEGRATED,
}

/** Below this width/height the cockpit falls back to the integrated gauge —
 *  mirrors `CarMapRenderer.COCKPIT_MIN_ASPECT`. */
private const val COCKPIT_MIN_ASPECT = 1.1f

/**
 * Draws the **same** instrument cluster the Android Auto surface renders
 * ([CarInstruments]) onto a phone Compose canvas, so the phone and the car
 * show pixel-identical gauges from one drawing code path. The car has no
 * Compose; we reach its `Canvas` drawing through [drawIntoCanvas]'s
 * `nativeCanvas`.
 *
 * [ClusterMode.INTEGRATED] is the portrait-friendly layout (a centred square
 * gauge) — use it on the phone where there's no full-bleed map to hug.
 */
@Composable
fun GaugeCluster(
    data: ClusterData,
    modifier: Modifier = Modifier,
    showCompass: Boolean = true,
    mode: ClusterMode = ClusterMode.AUTO,
) {
    val instruments = remember { CarInstruments() }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w < 1f || h < 1f) return@Canvas
        val cockpit = when (mode) {
            ClusterMode.COCKPIT -> true
            ClusterMode.INTEGRATED -> false
            ClusterMode.AUTO -> w >= h * COCKPIT_MIN_ASPECT
        }
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (cockpit) {
                instruments.drawCockpit(nc, w.toInt(), h.toInt(), data, RectF(0f, 0f, w, h), showCompass)
            } else {
                val s = min(w, h) * 0.98f
                val cx = w / 2f
                val cy = h / 2f
                instruments.drawIntegrated(
                    nc, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), data, showCompass,
                )
            }
        }
    }
}
