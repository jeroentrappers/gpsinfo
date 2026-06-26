package be.appmire.gpsinfo.car

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.min

/**
 * Android Studio previews of the REAL instrument cluster. The gauges are drawn
 * with raw [android.graphics.Canvas] (not Compose), so this wraps the actual
 * [CarInstruments] draw code on a Compose canvas with fixed sample data — the
 * @Preview pane then renders exactly what the car surface will, at any aspect
 * ratio, without an emulator or the Android Auto host.
 *
 * There's no MapLibre map in a preview; a flat mid-tone stands in for it so the
 * cockpit's edge scrims read. Resize the preview (or add @Preview sizes) to see
 * the width-based fallback: wide → cockpit, narrow → integrated — the same rule
 * [CarMapRenderer.drawCluster] uses live.
 */
private val PREVIEW_DATA = ClusterData(
    kmh = 56.0, hasSpeed = true, speedAccKmh = 4f,
    kw = 12.0, peakKw = 84.0, consKwh100 = 13.2,
    socPct = 90f, rangeKm = 214f,
    headingDeg = 33f, hasHeading = true,
    latG = -0.23f, lonG = -0.01f,
    speedLimitKmh = 65, odometerKm = 534.1,
    ambientTempC = 10.0, clock = "14:32", obd = true,
)

@Composable
private fun ClusterPreview() {
    val instruments = remember { CarInstruments() }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width.toInt()
        val h = size.height.toInt()
        drawRect(Color(0xFF1B2530)) // faux map tone, so the HUD scrims are visible
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            if (w >= h * 1.1f) {
                // Simulate the host's safe area (status/action chrome top & bottom).
                instruments.drawCockpit(nc, w, h, PREVIEW_DATA, RectF(w * 0.05f, h * 0.2f, w * 0.94f, h * 0.86f))
            } else {
                val s = min(w, h) * 0.98f
                val cx = w / 2f
                val cy = h / 2f
                instruments.drawIntegrated(nc, RectF(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f), PREVIEW_DATA)
            }
        }
    }
}

@Preview(name = "Cockpit — wide cluster", widthDp = 1000, heightDp = 420)
@Composable
private fun PreviewWide() = ClusterPreview()

@Preview(name = "Cockpit — square-ish", widthDp = 860, heightDp = 740)
@Composable
private fun PreviewSquare() = ClusterPreview()

@Preview(name = "Integrated — narrow/portrait", widthDp = 460, heightDp = 820)
@Composable
private fun PreviewNarrow() = ClusterPreview()
