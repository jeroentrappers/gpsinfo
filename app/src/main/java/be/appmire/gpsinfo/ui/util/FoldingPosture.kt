package be.appmire.gpsinfo.ui.util

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.flowOf

/**
 * Observe the device's folding posture for layout decisions. Returns
 * the currently-active [FoldingFeature] (typically a single hinge),
 * or null on a non-foldable device or while flat.
 *
 * Layouts that care about tabletop / book postures pivot on this:
 *
 *   val fold = rememberFoldingFeature()
 *   val isTabletop = fold?.state == FoldingFeature.State.HALF_OPENED &&
 *                    fold?.orientation == FoldingFeature.Orientation.HORIZONTAL
 *
 * Cheap to call from any composable — the tracker is window-scoped
 * and the OS only emits when the posture actually changes.
 */
@Composable
fun rememberFoldingFeature(): FoldingFeature? {
    val ctx = LocalContext.current
    val activity = ctx as? Activity ?: return null
    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
    }
    val info by flow.collectAsState(initial = null)
    return info?.displayFeatures?.firstNotNullOfOrNull { it as? FoldingFeature }
}

/** True iff the device is in the half-folded tabletop posture (Pixel
 *  Fold / Galaxy Z Fold standing up like a tiny laptop). The screen
 *  is split by a horizontal hinge; content should live in the upper
 *  half. Top-level function so call sites needn't import the
 *  extension. */
fun isTabletop(fold: FoldingFeature?): Boolean =
    fold != null &&
        fold.state == FoldingFeature.State.HALF_OPENED &&
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL
