package be.appmire.gpsinfo.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.LocationRepository
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.TrailRecordingController
import be.appmire.gpsinfo.data.TrailRepository
import be.appmire.gpsinfo.data.rally.RallyController
import be.appmire.gpsinfo.data.rally.RallyState
import be.appmire.gpsinfo.util.TrailNaming
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Android Auto main screen: a [NavigationTemplate] whose entire body is
 * the video surface painted by [CarMapRenderer] (map tiles + trail
 * breadcrumb + HUD). Template UI is reduced to the two action strips —
 * recording controls top-right, zoom on the map strip — everything else
 * is drawn by us.
 *
 * Navigation category, not POI: it's the only category granted surface
 * access, and a live trip map + driving readouts is squarely the
 * "maps / driver assistance" bucket Google opened the category to in
 * 2024. We still ship no turn-by-turn and never claim routing.
 *
 * Host-constraint gotchas (enforced with an IllegalArgumentException at
 * template-build time, i.e. a crash):
 *   - Map ActionStrip actions must be icon-only (no custom titles).
 *   - Location permission is NOT granted by the host: without it we
 *     show a grant-access message template instead of a dead map,
 *     using CarContext.requestPermissions (parked only).
 */
class TripDashboardScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer,
) : Screen(carContext), DefaultLifecycleObserver {

    private var recording: RecordingState = RecordingState.Idle
    private var rally: RallyState = RallyState.Idle
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

    /** Pipe GNSS + recording + rally state into the surface renderer.
     *  The template itself only changes when the recording toggles or
     *  the rally state changes phase (the action strips swap), so
     *  those are the only [invalidate] triggers. Idempotent: called
     *  from onStart and again after a mid-session permission grant. */
    private fun startCollecting() {
        if (collectJob != null) return
        // Re-link the paired wheel probe so RT distance comes from
        // wheel revolutions even when the phone app never opened.
        be.appmire.gpsinfo.data.WheelSensorRepository
            .getInstance(carContext.applicationContext)
            .connectIfPaired(
                be.appmire.gpsinfo.data.SettingsRepository(carContext.applicationContext)
            )
        val locRepo = LocationRepository(carContext.applicationContext)
        collectJob = combine(
            locRepo.snapshots(),
            TrailRecordingController.state,
            RallyController.state,
        ) { gnss, rec, rallyState -> Triple(gnss, rec, rallyState) }
            .onEach { (gnss, rec, rallyState) ->
                // Rally distance keeps integrating from the car's own
                // GNSS stream; the controller dedupes if the phone or
                // the recording service also feed it.
                RallyController.offer(gnss)
                val recordingToggled =
                    (rec is RecordingState.Recording) != (recording is RecordingState.Recording)
                val rallyPhaseChanged = rallyState::class != rally::class
                recording = rec
                rally = rallyState
                renderer.update(gnss, rec, rallyState)
                if (recordingToggled || rallyPhaseChanged) invalidate()
            }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        if (!hasLocationPermission()) return permissionTemplate()

        val isRecording = recording is RecordingState.Recording

        val recordAction = Action.Builder()
            .setIcon(
                carIcon(if (isRecording) R.drawable.ic_car_stop else R.drawable.ic_car_record)
            )
            .setTitle(
                if (isRecording) carContext.getString(R.string.car_action_stop)
                else carContext.getString(R.string.car_action_start)
            )
            .setOnClickListener {
                if (isRecording) stopAndSave() else startRecordingSafely()
            }
            .build()
        val trailsAction = Action.Builder()
            .setIcon(carIcon(R.drawable.ic_car_trails))
            .setOnClickListener {
                screenManager.push(RecentTrailsScreen(carContext))
            }
            .build()

        // The main strip is contextual on the rally phase. During a
        // running RT the ±10 m distance-sync nudges take the slots —
        // they're the controls a co-driver actually needs mid-stage.
        val actionStrip = when (rally) {
            is RallyState.Running -> ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(carIcon(R.drawable.ic_car_stop))
                        .setTitle(carContext.getString(R.string.car_action_rally_stop))
                        .setOnClickListener {
                            RallyController.stop()
                            invalidate()
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("−10 m")
                        .setOnClickListener { RallyController.nudge(-10.0) }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("+10 m")
                        .setOnClickListener { RallyController.nudge(+10.0) }
                        .build()
                )
                .build()
            is RallyState.Armed -> ActionStrip.Builder()
                .addAction(
                    Action.Builder()
                        .setIcon(carIcon(R.drawable.ic_car_record))
                        .setTitle(carContext.getString(R.string.car_action_rally_start))
                        .setOnClickListener {
                            // Marshal's go. Also make sure the trail is
                            // being recorded — an RT without its
                            // evidence GPX is a wasted stage.
                            RallyController.start()
                            if (!isRecording) startRecordingSafely()
                            invalidate()
                        }
                        .build()
                )
                .addAction(trailsAction)
                .build()
            RallyState.Idle -> ActionStrip.Builder()
                .addAction(recordAction)
                .addAction(trailsAction)
                .build()
        }

        // Zoom + tilt + pan live on the map action strip (anchored to
        // the map edge by the host). Icon-only is mandatory here. The
        // zoom buttons nudge a bias on top of the speed-adaptive level
        // rather than fighting it. Action.PAN is what makes the host
        // forward drag/pinch gestures to the surface at all — without
        // it the map is display-only.
        val mapActionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_zoom_in))
                    .setOnClickListener { renderer.zoomIn() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_zoom_out))
                    .setOnClickListener { renderer.zoomOut() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_tilt))
                    .setOnClickListener { renderer.toggleTilt() }
                    .build()
            )
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .setMapActionStrip(mapActionStrip)
            .setPanModeListener { isInPanMode ->
                renderer.setPanMode(isInPanMode)
            }
            .build()
    }

    /** Shown instead of the map while ACCESS_FINE_LOCATION is missing —
     *  e.g. a fresh install opened from the car before the phone app
     *  ever ran. The grant action is parked-only because the host
     *  routes the permission prompt through a parked flow. */
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

    /** Stop the recording and persist it under the default timestamp
     *  name ([TrailNaming]) — no keyboards in the car, and the user
     *  can rename on the phone whenever. An empty recording (no
     *  accepted points) is dropped with a toast instead of producing
     *  a zero-length GPX. */
    private fun stopAndSave() {
        val result = TrailRecordingController.stopRecording(carContext)
        invalidate()
        if (result.points.isEmpty()) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_toast_save_empty),
                CarToast.LENGTH_LONG,
            ).show()
            return
        }
        val name = TrailNaming.defaultTrailName(System.currentTimeMillis())
        lifecycleScope.launch {
            TrailRepository(carContext.applicationContext).save(
                name = name,
                points = result.points,
                laps = result.laps,
            )
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_toast_saved, name),
                CarToast.LENGTH_LONG,
            ).show()
        }
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
