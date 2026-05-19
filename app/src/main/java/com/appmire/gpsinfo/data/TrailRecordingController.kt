package com.appmire.gpsinfo.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.TrailPoint
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide owner of the in-flight recording. Both the dashboard
 * activity (when foregrounded) and the [TrailRecordingService] (always,
 * once recording is active) feed it new GNSS snapshots; both also observe
 * its [state] for the FAB and the notification.
 *
 * Why a singleton: the activity's ViewModel is destroyed when the user
 * backgrounds the app, but the recording is meant to continue. Hoisting
 * the recorder + lifecycle into an `object` makes the on/off state
 * survive ViewModel teardown without requiring service-binding ceremony.
 *
 * The service-start side-effect lives here, not in the ViewModel, so
 * `vm.startRecording()` can stay a pure suspend-less action.
 */
object TrailRecordingController {

    private val recorder = TrailRecorder()

    /** Live state of the recording — Idle or Recording(start, pointCount). */
    val state: StateFlow<RecordingState> = recorder.state

    /** Push a fresh snapshot into the recorder. No-op when not recording. */
    fun offer(snapshot: GnssSnapshot) = recorder.offer(snapshot)

    /**
     * Begin a recording and start the foreground service so capture
     * continues if the activity is killed. Safe to call while the app
     * is in the foreground — the service will sit idle if nobody else
     * is offering snapshots; the activity feeds it for free via its
     * own GNSS subscription.
     */
    fun startRecording(context: Context) {
        recorder.start()
        val intent = Intent(context.applicationContext, TrailRecordingService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } else {
            context.applicationContext.startService(intent)
        }
    }

    /**
     * Stop the recording. Returns the captured points (possibly empty)
     * and drops the recorder back to Idle. The caller decides what to
     * persist — the controller doesn't know the user-chosen trail name.
     */
    fun stopRecording(context: Context): List<TrailPoint> {
        val points = recorder.stop()
        context.applicationContext.stopService(
            Intent(context.applicationContext, TrailRecordingService::class.java)
        )
        return points
    }

    /** True while we're inside an active recording, false otherwise. */
    val isRecording: Boolean get() = state.value is RecordingState.Recording
}
