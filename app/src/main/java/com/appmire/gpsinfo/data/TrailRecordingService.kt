package com.appmire.gpsinfo.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.appmire.gpsinfo.MainActivity
import com.appmire.gpsinfo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground service that keeps GNSS streaming while the user has the
 * app backgrounded. It owns its own [LocationRepository] subscription —
 * separate from the activity's — so capture continues independently of
 * the activity lifecycle. Captured snapshots are fed to the
 * process-wide [TrailRecordingController]; the activity, when it
 * resumes, observes the same controller for its FAB state.
 *
 * Notification is sticky and non-dismissable: that's required by
 * Android's foreground-service contract. A "Stop" action on the
 * notification fires the [ACTION_STOP] intent, which stops the service;
 * an unsaved trail then sits in [TrailRecordingController] for the
 * activity to finalise when the user next opens it.
 */
class TrailRecordingService : Service() {

    private lateinit var scope: CoroutineScope
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Notification "Stop" tap. We just stopSelf — the activity
            // is responsible for persisting whatever was captured the
            // next time it opens, via its existing save flow.
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        startCollecting()
        // START_STICKY so Android restarts the service if it's killed
        // for memory pressure mid-recording. The user's "Stop" action
        // is what intentionally ends it.
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startCollecting() {
        if (collectJob != null) return
        // Note: while the activity is also foregrounded, this is a SECOND
        // GNSS subscription on top of the one the activity's ViewModel
        // owns. The OS multiplexes both onto a single GPS hardware
        // session, so the battery cost is the listener overhead only —
        // not a second radio activation. The recorder's per-point
        // distance/time gate (see [TrailRecorder.offer]) dedupes the
        // double feed. We accept this small overhead because it makes
        // background-only recording (the dashboard never opened during
        // a recording session) self-contained.
        collectJob = LocationRepository(applicationContext).snapshots()
            .onEach { TrailRecordingController.offer(it) }
            .launchIn(scope)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val tapToOpen = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, TrailRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.trail_record_notification_title))
            .setContentText(getString(R.string.trail_record_notification_body))
            .setContentIntent(tapToOpen)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.trail_stop),
                stopAction,
            )
            .setOngoing(true)
            // CATEGORY_PROGRESS keeps Android from minimising the notif
            // into the status-bar drawer; the user always sees it.
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.trail_record_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.trail_record_notification_channel_body)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "trail_recording"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.appmire.gpsinfo.action.STOP_RECORDING"

        /** Convenience for callers that want to ensure the channel is up
         *  without starting the service — e.g. when prompting the user
         *  for notification permission before they ever hit Record. */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.trail_record_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.trail_record_notification_channel_body)
                    setShowBadge(false)
                }
            )
        }
    }
}
