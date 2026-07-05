package be.appmire.gpsinfo.ui.cluster

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import be.appmire.gpsinfo.car.CarInstruments
import be.appmire.gpsinfo.car.ClusterData
import be.appmire.gpsinfo.ui.overlay.PhoneOverlayElement
import be.appmire.gpsinfo.ui.overlay.overlayElement
import be.appmire.gpsinfo.ui.overlay.overlayElementVisible
import kotlin.math.roundToInt

/**
 * The **faithful** cockpit cluster — the exact [CarInstruments] cockpit
 * (landscape) and bespoke portrait map-HUD the Android Auto surface draws —
 * but laid out as **separate, individually editable pieces** instead of one
 * monolithic canvas. Each piece (speed, energy/power, compass) is drawn at its
 * natural bounds and tagged with [Modifier.overlayElement], so the phone's
 * layout editor can move / scale each one on its own, exactly like the car
 * surface (`CarMapRenderer.drawCluster`). Non-interactive furniture (edge
 * scrims, clock, speed-limit sign) is drawn plainly behind the pieces.
 *
 * Replaces the single-canvas [GaugeCluster] on the editable phone screens so
 * "edit the layout" actually reaches individual gauges rather than treating
 * the whole cluster as one block.
 */
@Composable
fun CockpitClusterPhone(
    data: ClusterData,
    showCompass: Boolean,
    modifier: Modifier = Modifier,
) {
    val instruments = remember { CarInstruments() }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        if (wPx < 2 || hPx < 2) return@BoxWithConstraints
        val landscape = wPx >= hPx * COCKPIT_MIN_ASPECT

        if (landscape) {
            val g = remember(wPx, hPx) {
                instruments.cockpitGeom(wPx, hPx, RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()))
            }
            // Background furniture — not editable (see class doc).
            PlainLayer { nc ->
                instruments.ckScrims(nc, g, data)
                instruments.ckClock(nc, g, data)
            }
            Piece(PhoneOverlayElement.CLUSTER_SPEED, union(g.speedRect, g.speedTextRect), wPx, hPx, density) { nc ->
                instruments.ckSpeedGauge(nc, g, data)
                instruments.ckSpeedText(nc, g, data)
            }
            if (data.obd) {
                Piece(PhoneOverlayElement.CLUSTER_POWER, union(g.energyRect, g.energyTextRect), wPx, hPx, density) { nc ->
                    instruments.ckEnergyGauge(nc, g, data)
                    instruments.ckEnergyText(nc, g, data)
                }
            }
            if (showCompass) {
                Piece(PhoneOverlayElement.CLUSTER_COMPASS, g.compassRect, wPx, hPx, density) { nc ->
                    instruments.ckCompass(nc, g, data)
                }
            }
        } else {
            val cell = remember(wPx, hPx) { RectF(0f, 0f, wPx.toFloat(), hPx.toFloat()) }
            PlainLayer { nc ->
                instruments.ckPortScrims(nc, cell, data)
                instruments.ckPortClock(nc, cell, data)
                instruments.ckPortLimit(nc, cell, data)
            }
            if (data.obd) {
                Piece(PhoneOverlayElement.CLUSTER_POWER, instruments.portPowerRect(cell), wPx, hPx, density) { nc ->
                    instruments.ckPortPower(nc, cell, data)
                }
            }
            Piece(PhoneOverlayElement.CLUSTER_SPEED, instruments.portSpeedRect(cell), wPx, hPx, density) { nc ->
                instruments.ckPortSpeed(nc, cell, data)
            }
            if (showCompass) {
                Piece(PhoneOverlayElement.CLUSTER_COMPASS, instruments.portCompassRect(cell), wPx, hPx, density) { nc ->
                    instruments.ckPortCompass(nc, cell, data)
                }
            }
        }
    }
}

/** A full-size, non-interactive layer for background furniture. */
@Composable
private fun PlainLayer(draw: (android.graphics.Canvas) -> Unit) {
    Canvas(Modifier.fillMaxSize()) {
        if (size.minDimension < 1f) return@Canvas
        drawIntoCanvas { c -> draw(c.nativeCanvas) }
    }
}

/**
 * One editable cluster piece: a canvas sized + positioned to the piece's
 * natural [rect] within the cluster, tagged with [overlayElement] so it drags
 * and pinches on its own. The piece is drawn in absolute cluster coordinates
 * (the [CarInstruments] pieces expect that), so the native canvas is shifted
 * by -rect so absolute coords land at the node's local origin; Compose doesn't
 * clip, so any glow past [rect] still shows, while the hit area stays [rect]
 * (and pinch pivots at its centre — matching the car surface).
 */
@Composable
private fun Piece(
    element: PhoneOverlayElement,
    rect: RectF,
    parentW: Int,
    parentH: Int,
    density: Density,
    draw: (android.graphics.Canvas) -> Unit,
) {
    if (!overlayElementVisible(element)) return
    if (rect.width() < 1f || rect.height() < 1f) return
    val wDp = with(density) { rect.width().toDp() }
    val hDp = with(density) { rect.height().toDp() }
    Canvas(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .requiredSize(wDp, hDp)
            .overlayElement(element),
    ) {
        if (size.minDimension < 1f) return@Canvas
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(-rect.left, -rect.top)
            draw(nc)
            nc.restore()
        }
    }
}

private fun union(a: RectF, b: RectF): RectF = RectF(a).apply { union(b) }

/** Mirror of [GaugeCluster]'s COCKPIT_MIN_ASPECT / the car surface threshold. */
private const val COCKPIT_MIN_ASPECT = 1.1f
