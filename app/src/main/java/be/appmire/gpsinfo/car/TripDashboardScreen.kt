package be.appmire.gpsinfo.car

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

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

    /** Pipe GNSS + recording state into the surface renderer. The
     *  template itself only changes when the recording toggles (the
     *  action strip flips between Start and Open-on-phone), so that's
     *  the only thing that triggers [invalidate]. Idempotent: called
     *  from onStart and again after a mid-session permission grant. */
    private fun startCollecting() {
        if (collectJob != null) return
        val locRepo = LocationRepository(carContext.applicationContext)
        collectJob = combine(
            locRepo.snapshots(),
            TrailRecordingController.state,
        ) { gnss, rec -> gnss to rec }
            .onEach { (gnss, rec) ->
                val recordingToggled =
                    (rec is RecordingState.Recording) != (recording is RecordingState.Recording)
                recording = rec
                renderer.update(gnss, rec)
                if (recordingToggled) invalidate()
            }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        if (!hasLocationPermission()) return permissionTemplate()

        val isRecording = recording is RecordingState.Recording

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

        // Zoom lives on the map action strip (anchored to the map edge
        // by the host). Icon-only is mandatory here.
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
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(recordAction)
                    .addAction(trailsAction)
                    .build()
            )
            .setMapActionStrip(mapActionStrip)
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
}
