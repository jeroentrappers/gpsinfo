package be.appmire.gpsinfo

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import be.appmire.gpsinfo.data.TrailRecordingController

/**
 * Quick Settings tile that toggles trail recording.
 *
 * - Tap when idle: starts recording via [TrailRecordingController]. The
 *   tile then flips to the Active state. The foreground service the
 *   controller starts keeps capture alive even if the user never opens
 *   the app.
 * - Tap when recording: opens the MainActivity so the user can hit
 *   Stop, name the trail, and save it. We don't auto-save from here
 *   because the user is the only one who knows the name; surfacing
 *   the save dialog mid-shade-pull would be poor UX.
 *
 * Active-tile semantics aren't declared in the manifest — the system
 * delivers fresh [onStartListening] callbacks each time the user pulls
 * the shade, which is sufficient for our purposes.
 */
@RequiresApi(Build.VERSION_CODES.N)
class RecordingTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (TrailRecordingController.isRecording) {
            // Already recording — bring the user to the app so they
            // can stop + save the trail with a meaningful name.
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        this,
                        0,
                        launch,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
        } else {
            TrailRecordingController.startRecording(applicationContext)
            refreshTile()
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        tile.state = if (TrailRecordingController.isRecording) Tile.STATE_ACTIVE
        else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.contentDescription = getString(
            if (TrailRecordingController.isRecording) R.string.qs_tile_state_recording
            else R.string.qs_tile_state_idle
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (TrailRecordingController.isRecording) R.string.qs_tile_state_recording
                else R.string.qs_tile_state_idle
            )
        }
        tile.updateTile()
    }
}
