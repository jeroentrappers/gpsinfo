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
import kotlinx.coroutines.flow.flatMapLatest
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

    /** In-memory copy of the user's car-overlay drag/scale layout, kept in
     *  sync with DataStore so the edit-mode persistence callback can patch a
     *  single element and re-save the whole blob. */
    private var overlayLayout: CarOverlayLayout = CarOverlayLayout()
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

    /** Guards the one-shot OBD reconnect prompt so the 5-minute decision
     *  dialog is pushed once, not on every state emission. */
    private var obdPromptShown = false

    /** Whether the vehicle is at a standstill (GPS speed below
     *  [STOPPED_MPS]) — gates the layout-editor action's visibility.
     *  Starts true so the editor is reachable before the first fix. */
    private var stopped = true

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
                // After 5 min of failed reconnection the controller pauses and
                // asks: keep trying or give up. Surface that once.
                if (d.awaitingDecision && !obdPromptShown) {
                    obdPromptShown = true
                    screenManager.push(ObdReconnectScreen(carContext) { obdPromptShown = false })
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        // Auto-drive ("test drive"): while the simulator is enabled it
        // replaces the live GPS with synthetic fixes walking the route, so
        // the whole surface drives itself end-to-end (Play AUTO_DRIVE check).
        val locationSource = be.appmire.gpsinfo.data.nav.NavigationSimulator.active
            .flatMapLatest { simulating ->
                if (simulating) be.appmire.gpsinfo.data.nav.NavigationSimulator.snapshots()
                else locRepo.snapshots()
            }
        collectJob = combine(
            locationSource,
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
                // Standstill drives the layout-editor button's visibility: it's
                // only offered (and only usable) when the vehicle is stopped,
                // from GPS speed — the host's "parked" state isn't exposed to
                // apps and, over projection, often reads as "driving" even at a
                // standstill. Auto-leave edit mode the moment we start moving.
                val mps = gnss.location?.takeIf { it.hasSpeed() }?.speed ?: 0f
                val nowStopped = mps < STOPPED_MPS
                val stoppedChanged = nowStopped != stopped
                stopped = nowStopped
                if (!nowStopped && renderer.isEditMode()) renderer.setEditMode(false)
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
                renderer.updateAlternatives(
                    (navState as? NavigationController.NavState.Navigating)?.alternatives ?: emptyList()
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
                // Live traffic viewport: the route corridor while navigating,
                // otherwise a box around the vehicle.
                val navg2 = navState as? NavigationController.NavState.Navigating
                if (navg2 != null) {
                    be.appmire.gpsinfo.data.nav.TrafficController.setRoute(
                        navg2.route.points.map { doubleArrayOf(it.lat, it.lon) },
                    )
                } else {
                    gnss.location?.let {
                        be.appmire.gpsinfo.data.nav.TrafficController.setLocation(it.latitude, it.longitude)
                    }
                }
                if (recordingToggled || rallyPhaseChanged || navChanged || stoppedChanged) invalidate()
            }
            .launchIn(lifecycleScope)

        // Push the resolved speed limit to the cluster as it settles.
        SpeedLimitProvider.limit
            .onEach { renderer.updateSpeedLimit(it) }
            .launchIn(lifecycleScope)

        // Live traffic: subscribe (SSE under the hood) and draw incidents.
        be.appmire.gpsinfo.data.nav.TrafficController.start()
        be.appmire.gpsinfo.data.nav.TrafficController.incidents
            .onEach { renderer.updateTraffic(it) }
            .launchIn(lifecycleScope)

        // Which optional overlays to draw, from the phone's "Android Auto"
        // settings. Defaults keep the surface navigation-only (Play policy);
        // each extra overlay is opt-in.
        val settings = be.appmire.gpsinfo.data.SettingsRepository(carContext.applicationContext)
        combine(
            settings.carOverlaySpeed,
            settings.carOverlaySpeedLimit,
            settings.carOverlayCluster,
            settings.carOverlayCompass,
            settings.carOverlayRecordingStrip,
            settings.carOverlayRallyPanel,
        ) { v ->
            CarOverlayConfig(
                speed = v[0],
                speedLimit = v[1],
                cluster = v[2],
                compass = v[3],
                recordingStrip = v[4],
                rallyPanel = v[5],
            )
        }
            .onEach { renderer.updateOverlayConfig(it) }
            .launchIn(lifecycleScope)

        // Live-GL map backend (opt-in). Off → the snapshotter path renders.
        settings.carLiveGlMap
            .onEach { renderer.updateLiveGlMap(it) }
            .launchIn(lifecycleScope)

        // Dynamic overlay layout (drag/scale per nav state). Push the active
        // state's overrides whenever the saved layout or the nav phase changes.
        combine(
            settings.carOverlayLayoutJson,
            NavigationController.state,
        ) { json, navState ->
            val layout = CarOverlayLayout.fromJson(json)
            overlayLayout = layout
            val state = if (
                navState is NavigationController.NavState.Navigating ||
                navState is NavigationController.NavState.Preparing
            ) OverlayState.NAV else OverlayState.IDLE
            state to layout
        }
            .onEach { (state, layout) ->
                // The renderer picks the active (width-class × state) bucket
                // itself, from the surface size.
                renderer.updateOverlayLayout(layout, state)
            }
            .launchIn(lifecycleScope)

        // Persist a finished edit: patch the one element in the right bucket
        // (width-class × nav-state) and re-save the whole layout blob.
        renderer.onLayoutChanged = { bucket, element, override ->
            overlayLayout = overlayLayout.with(bucket, element, override)
            lifecycleScope.launch { settings.setCarOverlayLayoutJson(overlayLayout.toJson()) }
        }
        // Remove: hide the element in this bucket.
        renderer.onElementHidden = { bucket, element ->
            overlayLayout = overlayLayout.hide(bucket, element)
            lifecycleScope.launch { settings.setCarOverlayLayoutJson(overlayLayout.toJson()) }
        }
        // Reset: clear the active bucket (overrides + removals) and re-save.
        renderer.onLayoutReset = { bucket ->
            overlayLayout = overlayLayout.clear(bucket)
            lifecycleScope.launch { settings.setCarOverlayLayoutJson(overlayLayout.toJson()) }
        }

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
        is NavigationController.NavState.Navigating -> listOf(
            s.nextTurn?.trackIndex,
            (s.distanceToTurnM / 10).toInt(),
            (s.etaSeconds / 60),
            s.alternatives.isNotEmpty(),
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
                // Take an en-route alternative when one is offered at a fork.
                val navAlt = (nav as? NavigationController.NavState.Navigating)
                    ?.alternatives?.firstOrNull()
                if (navAlt != null) {
                    addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_action_take_alt))
                            .setOnClickListener {
                                NavigationController.acceptAlternative(navAlt)
                                invalidate()
                            }
                            .build()
                    )
                }
                // Layout editor — only while the vehicle is stopped (GPS
                // standstill), and not during active navigation (keeps the
                // strip within its 4-action cap and the layout is tuned at
                // rest). We gate on our own standstill signal rather than a
                // ParkedOnlyOnClickListener: the host's "parked" gate often
                // reports "driving" even at a standstill over projection,
                // which is exactly the "not allowed while driving" toast.
                // Editing auto-exits the moment the car starts moving again.
                // Editing is allowed at any standstill — including while
                // navigating, so the NAV preset (a separate layout from the
                // idle one) can be tuned too. It auto-exits when the car moves.
                if (stopped) {
                    addAction(
                        Action.Builder()
                            .setTitle(
                                carContext.getString(
                                    if (renderer.isEditMode()) R.string.car_action_done
                                    else R.string.car_action_edit_layout
                                )
                            )
                            .setOnClickListener {
                                renderer.setEditMode(!renderer.isEditMode())
                                invalidate()
                            }
                            .build()
                    )
                    if (renderer.isEditMode()) {
                        addAction(
                            Action.Builder()
                                .setTitle(carContext.getString(R.string.car_action_remove))
                                .setOnClickListener {
                                    renderer.removeSelected()
                                    invalidate()
                                }
                                .build()
                        )
                        addAction(
                            Action.Builder()
                                .setTitle(carContext.getString(R.string.car_action_reset))
                                .setOnClickListener {
                                    renderer.resetLayout()
                                    invalidate()
                                }
                                .build()
                        )
                    }
                }
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
        // Diagnostic (temporary): pinpoints whether the top-left turn card is
        // empty because there's no maneuver (nextTurn null → loading) vs. a
        // host/step issue. Grep logcat for "NavCard".
        android.util.Log.w(
            "NavCard",
            "routingInfo nextTurn=${n.nextTurn?.command} turns=${n.route.turns.size} distToTurnM=${n.distanceToTurnM.toInt()}",
        )
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

        /** Below this GPS speed (m/s ≈ 1.8 km/h) the vehicle is treated as
         *  stopped, so the layout editor may be opened. */
        const val STOPPED_MPS = 0.5f
    }
}
