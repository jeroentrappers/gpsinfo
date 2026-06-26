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
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.EnergyLevel
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
import be.appmire.gpsinfo.data.SensorRepository
import be.appmire.gpsinfo.data.TrailRecordingController
import be.appmire.gpsinfo.data.TrailRepository
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.data.nav.SpeedLimitProvider
import be.appmire.gpsinfo.data.rally.RallyController
import be.appmire.gpsinfo.data.rally.RallyState
import be.appmire.gpsinfo.util.TrailNaming
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Android Auto main screen: a [NavigationTemplate] whose entire body is
 * the video surface painted by [CarMapRenderer] (map tiles + trail
 * breadcrumb + HUD). Template UI is reduced to the two action strips —
 * recording controls top-right, zoom on the map strip — everything else
 * is drawn by us.
 *
 * Navigation category: a live trip map + driving readouts plus offline
 * turn-by-turn (the maneuver card here, fed by [NavigationController]).
 * Next-turn guidance also reaches the instrument cluster via
 * [ClusterNavReporter] on the owning [TripDashboardSession]. Starting a
 * route is the labelled "Where to?" action on the strip.
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
    private var nav: NavigationController.NavState = NavigationController.NavState.Idle
    private var collectJob: Job? = null
    private var gForceJob: Job? = null

    /** Latest trustworthy GPS course, handed to the mount-independent
     *  G-force fusion as the "forward" reference (null below a walking
     *  pace, where the stream holds the last good heading). */
    @Volatile
    private var lastBearingDeg: Float? = null

    /** Car-hardware energy feed. Available on Android Automotive OS and
     *  hosts that expose it; over Android Auto projection it's usually
     *  unimplemented, so the readouts stay dashed until OBD2 fills them. */
    private var carInfo: CarInfo? = null

    /** True once the OBD live feed is supplying energy values, so the
     *  car-hardware EnergyLevel feed yields to the (richer) OBD source. */
    @Volatile
    private var obdProvidesEnergy = false
    private var obdJob: Job? = null

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { level ->
        if (!obdProvidesEnergy) {
            // Prefer EV state of charge; fall back to fuel % for combustion.
            val soc = floatOrNull(level.batteryPercent) ?: floatOrNull(level.fuelPercent)
            val rangeKm = floatOrNull(level.rangeRemainingMeters)?.div(1000f)
            renderer.updateEnergy(soc, rangeKm)
        }
    }

    private fun floatOrNull(cv: CarValue<Float>): Float? =
        if (cv.status == CarValue.STATUS_SUCCESS) cv.value else null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        if (hasLocationPermission()) startCollecting()
        startEnergyUpdates()
        startObdFeed()
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
        gForceJob?.cancel()
        gForceJob = null
        obdJob?.cancel()
        obdJob = null
        stopEnergyUpdates()
    }

    /** Auto-connect the OBD live feed if the user configured an adapter in
     *  the OBD Lab, and pipe its power/SoC/range into the energy dial.
     *  Power has no Car-API source, so OBD owns it outright. */
    private fun startObdFeed() {
        be.appmire.gpsinfo.obd.ObdLiveController.startIfConfigured(carContext.applicationContext)
        if (obdJob != null) return
        obdJob = be.appmire.gpsinfo.obd.ObdLiveController.state
            .onEach { d ->
                renderer.updateObdConnected(d.connected)
                if (d.connected) {
                    renderer.updatePower(d.powerKw)
                    renderer.updateAmbientTemp(d.ambientTempC)
                    if (d.socPercent != null || d.rangeKm != null) {
                        obdProvidesEnergy = true
                        renderer.updateEnergy(d.socPercent?.toFloat(), d.rangeKm?.toFloat())
                    }
                } else {
                    obdProvidesEnergy = false
                    renderer.updateAmbientTemp(null)
                }
            }
            .launchIn(lifecycleScope)
    }

    /** Subscribe to the car's energy level (battery %, range). Wrapped
     *  because hosts without car-hardware support throw on access. */
    private fun startEnergyUpdates() {
        if (carInfo != null) return
        try {
            val mgr = carContext.getCarService(CarHardwareManager::class.java)
            carInfo = mgr.carInfo.also {
                it.addEnergyLevelListener(
                    ContextCompat.getMainExecutor(carContext), energyListener,
                )
            }
        } catch (_: Exception) {
            // No car-hardware on this host — readouts stay dashed.
            carInfo = null
        }
    }

    private fun stopEnergyUpdates() {
        try {
            carInfo?.removeEnergyLevelListener(energyListener)
        } catch (_: Exception) {
            // Host already tore the binding down; nothing to recover.
        }
        carInfo = null
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
            NavigationController.state,
        ) { gnss, rec, rallyState, navState ->
            arrayOf(gnss, rec, rallyState, navState)
        }
            .onEach { (gnssAny, recAny, rallyAny, navAny) ->
                val gnss = gnssAny as be.appmire.gpsinfo.data.model.GnssSnapshot
                val rec = recAny as RecordingState
                val rallyState = rallyAny as RallyState
                val navState = navAny as NavigationController.NavState
                // Rally distance + nav guidance keep updating from the
                // car's own GNSS stream; both controllers dedupe if
                // the phone or the recording service also feed them.
                RallyController.offer(gnss)
                NavigationController.offer(gnss)
                lastBearingDeg = gnss.location
                    ?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.5f }
                    ?.bearing
                val recordingToggled =
                    (rec is RecordingState.Recording) != (recording is RecordingState.Recording)
                val rallyPhaseChanged = rallyState::class != rally::class
                val navChanged = navTemplateKey(navState) != navTemplateKey(nav)
                recording = rec
                rally = rallyState
                nav = navState
                renderer.update(gnss, rec, rallyState)
                renderer.updateNavigationRoute(
                    // Only the road ahead — drop the points already driven
                    // so the route line starts at the vehicle, not the origin.
                    (navState as? NavigationController.NavState.Navigating)?.let { n ->
                        val from = n.segmentIndex.coerceIn(0, (n.route.points.size - 1).coerceAtLeast(0))
                        n.route.points.subList(from, n.route.points.size)
                    }
                )
                renderer.updateNavigationStatus(
                    when (navState) {
                        is NavigationController.NavState.Preparing -> navState.detail
                        is NavigationController.NavState.Failed -> navState.message
                        else -> null
                    }
                )
                renderer.updateNavProgress(
                    (navState as? NavigationController.NavState.Navigating)?.distanceRemainingM
                )
                // Always-on speed limit: while navigating the route's own
                // (offline, segment-accurate) limit wins; otherwise resolve the
                // current road offline-first (BRouter) + online refine (Valhalla).
                gnss.location?.let { loc ->
                    SpeedLimitProvider.offer(
                        carContext, loc,
                        navigating = navState is NavigationController.NavState.Navigating,
                        navLimitKmh = (navState as? NavigationController.NavState.Navigating)?.speedLimitKmh,
                    )
                }
                if (recordingToggled || rallyPhaseChanged || navChanged) invalidate()
            }
            .launchIn(lifecycleScope)

        // Push the resolved speed limit to the cluster as it settles.
        SpeedLimitProvider.limit
            .onEach { renderer.updateSpeedLimit(it) }
            .launchIn(lifecycleScope)

        // G-meter feed, on its own job. The fused accelerometer stream
        // runs at the game sensor rate (~50 Hz); sample it down to a
        // few frames a second so the corner dial animates smoothly
        // without flooding the surface with redraws — the snapshotter
        // dedupes the unchanged map camera, so each extra frame is just
        // a cheap re-blit of the cached bitmap plus the gauge.
        val sensorRepo = SensorRepository(carContext.applicationContext)
        gForceJob = sensorRepo.gForceStream { lastBearingDeg }
            .sample(GFORCE_SAMPLE_MS)
            .onEach { renderer.updateGForce(it) }
            .launchIn(lifecycleScope)
    }

    /** What of the nav state is visible in the template: the phase,
     *  the upcoming turn, and the countdown in 10 m steps — anything
     *  else changing shouldn't burn a template refresh. */
    private fun navTemplateKey(s: NavigationController.NavState): Any? = when (s) {
        is NavigationController.NavState.Navigating -> Triple(
            s.nextTurn?.trackIndex,
            (s.distanceToTurnM / 10).toInt(),
            (s.etaSeconds / 60),
        )
        else -> s::class
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
        // "Where to?" — the turn-by-turn entry point: opens the saved +
        // recent destination picker (trails live inside). Labelled, not
        // icon-only: a Play review flagged that starting directions wasn't
        // discoverable. The navigation action strip permits several custom
        // titles, so this coexists with the record/end-route titles.
        val placesAction = Action.Builder()
            .setIcon(carIcon(R.drawable.ic_car_places))
            .setTitle(carContext.getString(R.string.car_action_navigate))
            .setOnClickListener {
                screenManager.push(PlacesScreen(carContext))
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
                .addAction(placesAction)
                .build()
            RallyState.Idle -> ActionStrip.Builder().apply {
                addAction(recordAction)
                // End-route control while navigating or preparing a
                // route; trails browsing keeps its slot otherwise.
                if (nav is NavigationController.NavState.Navigating ||
                    nav is NavigationController.NavState.Preparing
                ) {
                    addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_action_end_nav))
                            .setOnClickListener {
                                NavigationController.stop()
                                invalidate()
                            }
                            .build()
                    )
                }
                addAction(placesAction)
            }.build()
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
                    .setOnClickListener { renderer.cycleViewMode() }
                    .build()
            )
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder().apply {
            setActionStrip(actionStrip)
            setMapActionStrip(mapActionStrip)
            setPanModeListener { isInPanMode ->
                renderer.setPanMode(isInPanMode)
            }
            when (val n = nav) {
                is NavigationController.NavState.Navigating -> {
                    setNavigationInfo(routingInfo(n))
                    setDestinationTravelEstimate(travelEstimate(n))
                }
                is NavigationController.NavState.Preparing -> {
                    setNavigationInfo(
                        androidx.car.app.navigation.model.RoutingInfo.Builder()
                            .setLoading(true)
                            .build()
                    )
                }
                else -> Unit
            }
        }.build()
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

    // ── Turn-by-turn template furniture ────────────────────────────

    /** Host-rendered maneuver card: turn arrow + distance countdown,
     *  built from the shared [CarManeuvers] mapping so the screen and the
     *  instrument cluster ([ClusterNavReporter]) always show the same turn.
     *  The host draws standard arrows from the maneuver type — no icons. */
    private fun routingInfo(
        n: NavigationController.NavState.Navigating,
    ): androidx.car.app.navigation.model.RoutingInfo {
        val turn = n.nextTurn
            ?: return androidx.car.app.navigation.model.RoutingInfo.Builder()
                .setLoading(true).build()
        return androidx.car.app.navigation.model.RoutingInfo.Builder()
            .setCurrentStep(
                CarManeuvers.step(carContext, turn),
                CarManeuvers.carDistance(n.distanceToTurnM),
            )
            .build()
    }

    private fun travelEstimate(
        n: NavigationController.NavState.Navigating,
    ): androidx.car.app.navigation.model.TravelEstimate {
        val arrivalMillis = System.currentTimeMillis() + n.etaSeconds * 1000L
        return androidx.car.app.navigation.model.TravelEstimate.Builder(
            CarManeuvers.carDistance(n.distanceRemainingM),
            androidx.car.app.model.DateTimeWithZone.create(
                arrivalMillis, java.util.TimeZone.getDefault(),
            ),
        )
            .setRemainingTimeSeconds(n.etaSeconds.toLong())
            .build()
    }

    private companion object {
        /** G-meter redraw cadence on the car — ~5 fps for the corner
         *  dial. Glanceable, not a game. */
        const val GFORCE_SAMPLE_MS = 200L
    }
}
