package be.appmire.gpsinfo.car

import android.location.Location
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
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
 */
class TripDashboardScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var snapshot: GnssSnapshot = GnssSnapshot()
    private var recording: RecordingState = RecordingState.Idle
    private var collectJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // Combine the GNSS stream with the recording state so the
        // pane is invalidated whenever either changes. The Car App
        // Library throttles invalidates so the head unit isn't
        // hammered at 1 Hz.
        val locRepo = LocationRepository(carContext.applicationContext)
        collectJob = combine(
            locRepo.snapshots(),
            TrailRecordingController.state,
        ) { gnss, rec ->
            snapshot = gnss
            recording = rec
        }.onEach { invalidate() }.launchIn(owner.lifecycleScope)
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
    }

    override fun onGetTemplate(): Template {
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

        // Action strip: a single Start/Stop button. Stopping from the
        // car opens the phone app — we can't show the save-name dialog
        // here, so we defer naming + persistence to the phone UI.
        val actionLabel = if (isRecording) {
            carContext.getString(R.string.car_action_open_phone)
        } else {
            carContext.getString(R.string.car_action_start)
        }
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(actionLabel)
                    .setOnClickListener {
                        if (isRecording) {
                            // Open the phone app — user names + saves there.
                            carContext.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_MAIN,
                                ).apply {
                                    setClassName(
                                        carContext.packageName,
                                        "be.appmire.gpsinfo.MainActivity",
                                    )
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        } else {
                            TrailRecordingController.startRecording(carContext)
                            invalidate()
                        }
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_action_recent_trails))
                    .setOnClickListener {
                        screenManager.push(RecentTrailsScreen(carContext))
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane.build())
            .setTitle(carContext.getString(R.string.app_name))
            .setActionStrip(actionStrip)
            .build()
    }

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
