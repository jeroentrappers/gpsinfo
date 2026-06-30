package be.appmire.gpsinfo.ui.cluster

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import be.appmire.gpsinfo.car.CarInstruments
import be.appmire.gpsinfo.car.ClusterData
import be.appmire.gpsinfo.ui.overlay.PhoneOverlayElement
import be.appmire.gpsinfo.ui.overlay.overlayElement

/**
 * The instrument cluster as **independent** gauges — speed, energy/power and
 * the compass/G-meter each drawn as its own self-contained dial and tagged as
 * a separate overlay element, so each can be dragged and scaled on its own in
 * edit mode (unlike the monolithic [GaugeCluster]). Default arrangement adapts
 * to orientation; the user's per-element overrides ride on top.
 */
@Composable
fun ClusterGauges(
    data: ClusterData,
    showCompass: Boolean,
    modifier: Modifier = Modifier,
) {
    val instruments = remember { CarInstruments() }
    BoxWithConstraints(modifier) {
        val landscape = maxWidth >= maxHeight
        val minDim = if (maxWidth <= maxHeight) maxWidth else maxHeight
        if (landscape) {
            // Speed left, compass centre, energy right — a row across the width.
            val s = minDim * 0.72f
            GaugeCanvas(PhoneOverlayElement.CLUSTER_SPEED, Modifier.align(Alignment.CenterStart).size(s)) { c, r ->
                instruments.drawSpeedDial(c, r, data)
            }
            if (showCompass) {
                GaugeCanvas(PhoneOverlayElement.CLUSTER_COMPASS, Modifier.align(Alignment.Center).size(minDim * 0.52f)) { c, r ->
                    instruments.drawCompassDial(c, r, data)
                }
            }
            GaugeCanvas(PhoneOverlayElement.CLUSTER_POWER, Modifier.align(Alignment.CenterEnd).size(s)) { c, r ->
                instruments.drawPowerDial(c, r, data)
            }
        } else {
            // Speed top, compass middle, energy bottom — a column down the height.
            val s = maxWidth * 0.6f
            GaugeCanvas(PhoneOverlayElement.CLUSTER_SPEED, Modifier.align(Alignment.TopCenter).size(s)) { c, r ->
                instruments.drawSpeedDial(c, r, data)
            }
            if (showCompass) {
                GaugeCanvas(PhoneOverlayElement.CLUSTER_COMPASS, Modifier.align(Alignment.Center).size(maxWidth * 0.5f)) { c, r ->
                    instruments.drawCompassDial(c, r, data)
                }
            }
            GaugeCanvas(PhoneOverlayElement.CLUSTER_POWER, Modifier.align(Alignment.BottomCenter).size(s)) { c, r ->
                instruments.drawPowerDial(c, r, data)
            }
        }
    }
}

/** One sub-gauge: a Compose canvas that draws via [CarInstruments]'s native
 *  Canvas, tagged so the layout editor can move/scale it on its own. */
@Composable
private fun GaugeCanvas(
    element: PhoneOverlayElement,
    modifier: Modifier,
    draw: (android.graphics.Canvas, RectF) -> Unit,
) {
    Canvas(modifier.overlayElement(element)) {
        if (size.minDimension < 1f) return@Canvas
        drawIntoCanvas { c -> draw(c.nativeCanvas, RectF(0f, 0f, size.width, size.height)) }
    }
}
