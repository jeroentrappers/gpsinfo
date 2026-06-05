package be.appmire.gpsinfo.data

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
import be.appmire.gpsinfo.MainActivity
import be.appmire.gpsinfo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import be.appmire.gpsinfo.data.model.CyclingPowerState
import be.appmire.gpsinfo.data.model.HeartRateState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
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
    private var stepJob: Job? = null
    private var hrJob: Job? = null
    private var powerJob: Job? = null
    private var batteryJob: Job? = null
    private var nmeaLogger: NmeaLogger? = null
    private var nmeaBtBridge: NmeaBluetoothBridge? = null

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
            .onEach {
                TrailRecordingController.offer(it)
                // Rally distance + nav guidance keep updating while
                // backgrounded — both dedupe multi-source feeds.
                be.appmire.gpsinfo.data.rally.RallyController.offer(it)
                be.appmire.gpsinfo.data.nav.NavigationController.offer(it)
            }
            .launchIn(scope)
        // Step counter alongside GNSS — silently no-ops if the device
        // lacks the sensor or if ACTIVITY_RECOGNITION wasn't granted.
        stepJob = SensorRepository(applicationContext).stepCounterStream()
            .onEach { TrailRecordingController.offerSteps(it) }
            .launchIn(scope)
        // HR forwarding — when a BLE strap is paired, push each fresh
        // BPM into the recorder so the next accepted trail point
        // carries it. distinctUntilChanged so we don't refresh the
        // recorder field on every duplicate sample.
        hrJob = HeartRateRepository.getInstance(applicationContext).state
            .filterIsInstance<HeartRateState.Connected>()
            .onEach { conn ->
                conn.lastBpm?.let { TrailRecordingController.offerHr(it) }
                if (conn.lastRrIntervalsMs.isNotEmpty()) {
                    TrailRecordingController.offerRrIntervals(conn.lastRrIntervalsMs)
                }
            }
            .launchIn(scope)
        // Cycling power forwarding — same shape as HR. When a paired
        // BLE power meter delivers a sample, push its watts into the
        // recorder so the next accepted trail point carries the value.
        powerJob = CyclingPowerRepository.getInstance(applicationContext).state
            .filterIsInstance<CyclingPowerState.Connected>()
            .onEach { conn ->
                conn.lastWatts?.let { TrailRecordingController.offerPower(it) }
            }
            .launchIn(scope)
        // Battery-aware sampling. Poll once per minute — the OS already
        // throttles ACTION_BATTERY_CHANGED broadcasts to ~1 minute, and
        // polling skips a broadcast receiver lifecycle that the service
        // doesn't otherwise need.
        batteryJob = kotlinx.coroutines.flow.flow {
            while (true) {
                emit(currentBatteryPct())
                kotlinx.coroutines.delay(60_000L)
            }
        }.onEach { TrailRecordingController.setBatteryLevel(it) }.launchIn(scope)
        // NMEA logging — only when the user has opted in via Settings.
        // Reads the pref once at service start; toggling the pref
        // mid-recording requires re-starting the recording to take
        // effect (acceptable for a diagnostic switch).
        val settings = SettingsRepository(applicationContext)
        kotlinx.coroutines.flow.flow {
            emit(settings.nmeaLoggingEnabled.first())
        }.onEach { enabled ->
            if (enabled && nmeaLogger == null) {
                nmeaLogger = NmeaLogger(applicationContext).also { it.start() }
            }
        }.launchIn(scope)
        // NMEA-over-Bluetooth-SPP — same one-shot pref read pattern.
        // Failure to start (no BT, no pairing, no perms) is silent;
        // the user sees the toggle stay on but no clients connect.
        kotlinx.coroutines.flow.flow {
            emit(settings.nmeaBtBridgeEnabled.first())
        }.onEach { enabled ->
            if (enabled && nmeaBtBridge == null) {
                nmeaBtBridge = NmeaBluetoothBridge.getInstance(applicationContext)
                    .also { it.start() }
            }
        }.launchIn(scope)
    }

    private fun currentBatteryPct(): Int {
        val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            ?: return 100
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
    }

    override fun onDestroy() {
        collectJob?.cancel()
        stepJob?.cancel()
        hrJob?.cancel()
        powerJob?.cancel()
        batteryJob?.cancel()
        nmeaLogger?.stop()
        nmeaLogger = null
        nmeaBtBridge?.stop()
        nmeaBtBridge = null
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
        const val ACTION_STOP = "be.appmire.gpsinfo.action.STOP_RECORDING"

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
