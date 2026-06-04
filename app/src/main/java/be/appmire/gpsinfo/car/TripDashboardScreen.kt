package be.appmire.gpsinfo.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.LocationRepository
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.TrailRecordingController
import be.appmire.gpsinfo.data.model.GnssSnapshot
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Live trip dashboard surfaced via Android Auto. PaneTemplate is the
 * cleanest fit for a four-row glanceable readout — Speed, Heading,
 * Altitude, Distance — with recording controls in the ActionStrip.
 *
 * No full-screen map (that would mean claiming "navigation" category,
 * which means owning turn-by-turn routing). We're a passive trip
 * dashboard + recording remote.
 *
 * Host-constraint gotchas this screen must respect (all enforced with
 * an IllegalArgumentException at template-build time, i.e. a crash):
 *   - PaneTemplate's ActionStrip allows at most ONE action with a
 *     custom title (ACTIONS_CONSTRAINTS_SIMPLE). Every other action
 *     must be icon-only.
 *   - Location permission is NOT granted by the host: without it we
 *     show a grant-access message template instead of a dead "—"
 *     dashboard, using CarContext.requestPermissions (parked only).
 */
class TripDashboardScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var snapshot: GnssSnapshot = GnssSnapshot()
    private var recording: RecordingState = RecordingState.Idle
    private var collectJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (hasLocationPermission()) startCollecting()
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            carContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** Combine the GNSS stream with the recording state so the pane is
     *  invalidated whenever either changes. The Car App Library
     *  throttles invalidates so the head unit isn't hammered at 1 Hz.
     *  Idempotent: called from onStart and again after a mid-session
     *  permission grant. */
    private fun startCollecting() {
        if (collectJob != null) return
        val locRepo = LocationRepository(carContext.applicationContext)
        collectJob = combine(
            locRepo.snapshots(),
            TrailRecordingController.state,
        ) { gnss, rec ->
            snapshot = gnss
            recording = rec
        }.onEach { invalidate() }.launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        if (!hasLocationPermission()) return permissionTemplate()

        val loc = snapshot.location
        val isRecording = recording is RecordingState.Recording
        val rec = recording as? RecordingState.Recording

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_row_speed))
                    .addText(formatSpeed(loc))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_row_heading))
                    .addText(formatHeading(loc))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_row_altitude))
                    .addText(formatAltitude(loc))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_row_trip))
                    .addText(
                        if (rec != null) "%.2f km · %d pts".format(
                            Locale.ROOT,
                            rec.distanceMetres / 1000.0,
                            rec.pointCount,
                        )
                        else carContext.getString(R.string.car_row_trip_idle)
                    )
                    .build()
            )

        // Action strip: Start/Stop + Recent Trails. Only the record
        // action may carry a title (host allows one custom title per
        // strip — see class doc); Recent Trails is icon-only.
        val recordAction = Action.Builder()
            .setIcon(
                carIcon(if (isRecording) R.drawable.ic_car_phone else R.drawable.ic_car_record)
            )
            .setTitle(
                if (isRecording) carContext.getString(R.string.car_action_open_phone)
                else carContext.getString(R.string.car_action_start)
            )
            .setOnClickListener {
                if (isRecording) openPhoneApp() else startRecordingSafely()
            }
            .build()
        val trailsAction = Action.Builder()
            .setIcon(carIcon(R.drawable.ic_car_trails))
            .setOnClickListener {
                screenManager.push(RecentTrailsScreen(carContext))
            }
            .build()
        val actionStrip = ActionStrip.Builder()
            .addAction(recordAction)
            .addAction(trailsAction)
            .build()

        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setActionStrip(actionStrip)
            .build()
    }

    /** Shown instead of the dashboard while ACCESS_FINE_LOCATION is
     *  missing — e.g. a fresh install opened from the car before the
     *  phone app ever ran. The grant action is parked-only because the
     *  host routes the permission prompt through a parked flow. */
    private fun permissionTemplate(): Template {
        val grantAction = Action.Builder()
            .setTitle(carContext.getString(R.string.car_permission_grant))
            .setOnClickListener(
                ParkedOnlyOnClickListener.create {
                    carContext.requestPermissions(
                        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    ) { granted, _ ->
                        if (granted.isNotEmpty()) {
                            startCollecting()
                            invalidate()
                        }
                    }
                }
            )
            .build()
        return MessageTemplate.Builder(carContext.getString(R.string.car_permission_message))
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .addAction(grantAction)
            .build()
    }

    /** Start the recording + its foreground service, surfacing failure
     *  as a CarToast instead of crashing the session. Android 12+ can
     *  reject FGS starts from apps it considers backgrounded
     *  (ForegroundServiceStartNotAllowedException extends
     *  IllegalStateException); Android 14+ throws SecurityException
     *  when a location-type FGS starts without while-in-use grant. */
    private fun startRecordingSafely() {
        try {
            TrailRecordingController.startRecording(carContext)
        } catch (_: IllegalStateException) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_toast_record_failed),
                CarToast.LENGTH_LONG,
            ).show()
        } catch (_: SecurityException) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_toast_record_failed),
                CarToast.LENGTH_LONG,
            ).show()
        }
        invalidate()
    }

    /** Stop-from-car opens the phone app — we can't show the save-name
     *  dialog on the head unit, so naming + persistence happen on the
     *  phone. Android 10+ background-activity-launch rules may swallow
     *  the startActivity silently, so we always also show a toast
     *  telling the user where to go. */
    private fun openPhoneApp() {
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.car_toast_open_phone),
            CarToast.LENGTH_LONG,
        ).show()
        try {
            carContext.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(carContext.packageName, "be.appmire.gpsinfo.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            // BAL restriction or missing activity — the toast above
            // already told the user what to do.
        }
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    private fun formatSpeed(loc: Location?): String {
        if (loc == null || !loc.hasSpeed()) return "—"
        val kmh = loc.speed * 3.6f
        return "%.1f km/h".format(Locale.ROOT, kmh)
    }

    private fun formatHeading(loc: Location?): String {
        // GPS course-over-ground, NOT the magnetometer. The phone in a
        // car is typically lying flat in a cradle or cupholder, so its
        // magnetic heading is meaningless for direction-of-travel.
        // Location.bearing is derived from successive GPS positions
        // and is unaffected by phone orientation — exactly what AA
        // (and any driving context) actually wants. Returns "—" when
        // the user is stationary or moving too slowly for the chip to
        // compute a bearing.
        if (loc == null || !loc.hasBearing()) return "—"
        return "%.0f°".format(Locale.ROOT, loc.bearing)
    }

    private fun formatAltitude(loc: Location?): String {
        if (loc == null || !loc.hasAltitude()) return "—"
        return "%d m".format(Locale.ROOT, loc.altitude.toInt())
    }
}
