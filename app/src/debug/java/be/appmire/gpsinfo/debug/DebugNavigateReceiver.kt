package be.appmire.gpsinfo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import be.appmire.gpsinfo.data.nav.NavigationController

/**
 * Debug-only adb hook to start/stop offline navigation without
 * touching the phone UI — the emulator test loop for the car screen:
 *
 *   adb shell am broadcast -a be.appmire.gpsinfo.DEBUG_NAVIGATE \
 *       --ef lat 51.209 --ef lon 3.225 be.appmire.gpsinfo
 *   adb shell am broadcast -a be.appmire.gpsinfo.DEBUG_NAV_STOP \
 *       be.appmire.gpsinfo
 *
 * Lives in src/debug — release builds neither register nor ship it.
 */
class DebugNavigateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NAVIGATE -> {
                val lat = intent.getFloatExtra("lat", Float.NaN).toDouble()
                val lon = intent.getFloatExtra("lon", Float.NaN).toDouble()
                if (!lat.isNaN() && !lon.isNaN()) {
                    NavigationController.navigateTo(context, lat, lon)
                }
            }
            ACTION_STOP -> NavigationController.stop()
            // Import rd5 tiles pushed to the app's external-files dir
            // (the only place adb can reach across AAOS's user 10):
            //   adb push E0_N50.rd5 \
            //     /storage/emulated/10/Android/data/be.appmire.gpsinfo/files/
            //   adb shell am broadcast -a be.appmire.gpsinfo.DEBUG_IMPORT_TILES \
            //     be.appmire.gpsinfo
            ACTION_IMPORT_TILES -> {
                val src = context.getExternalFilesDir(null) ?: return
                val dst = java.io.File(context.filesDir, "brouter/segments").apply { mkdirs() }
                src.listFiles { f -> f.extension == "rd5" }?.forEach { f ->
                    f.copyTo(java.io.File(dst, f.name), overwrite = true)
                    android.util.Log.d("DebugNav", "imported ${f.name} (${f.length()} B)")
                }
            }
        }
    }

    companion object {
        const val ACTION_NAVIGATE = "be.appmire.gpsinfo.DEBUG_NAVIGATE"
        const val ACTION_STOP = "be.appmire.gpsinfo.DEBUG_NAV_STOP"
        const val ACTION_IMPORT_TILES = "be.appmire.gpsinfo.DEBUG_IMPORT_TILES"
    }
}
