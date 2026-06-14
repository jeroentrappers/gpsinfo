package be.appmire.gpsinfo.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import be.appmire.gpsinfo.data.AudibleCueManager
import be.appmire.gpsinfo.data.DashboardDensity
import be.appmire.gpsinfo.data.CyclingPowerRepository
import be.appmire.gpsinfo.data.HeartRateRepository
import be.appmire.gpsinfo.data.model.CyclingPowerState
import be.appmire.gpsinfo.data.LocationDataSource
import be.appmire.gpsinfo.data.LocationRepository
import be.appmire.gpsinfo.data.RecordingState
import be.appmire.gpsinfo.data.SensorDataSource
import be.appmire.gpsinfo.data.SensorRepository
import be.appmire.gpsinfo.data.SettingsDataSource
import be.appmire.gpsinfo.data.SettingsRepository
import be.appmire.gpsinfo.data.TestDataSourceOverride
import be.appmire.gpsinfo.data.ThemeOverride
import be.appmire.gpsinfo.data.TrailDataSource
import be.appmire.gpsinfo.data.TrailRecordingController
import be.appmire.gpsinfo.data.TrailRepository
import be.appmire.gpsinfo.data.TrailSummary
import be.appmire.gpsinfo.data.UnitSystem
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.model.HeartRateState
import be.appmire.gpsinfo.data.model.HrZoneConfig
import be.appmire.gpsinfo.data.model.NavigationTarget
import be.appmire.gpsinfo.data.model.SunInfo
import be.appmire.gpsinfo.data.model.withTargetPace
import be.appmire.gpsinfo.data.sun.SunPositionCalculator
import be.appmire.gpsinfo.util.NavigationMath
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The dashboard's slow-moving state — everything that ticks at ≤ 4 Hz.
 *
 * The compass is intentionally NOT in this state. It updates at ~30 Hz
 * via [DashboardViewModel.compass] as its own flow so screens that don't
 * show a heading (satellite list, about) aren't re-evaluated at that
 * rate just because the user rotated the phone.
 */
data class DashboardUiState(
    val gnss: GnssSnapshot = GnssSnapshot(),
    val sun: SunInfo? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val hasPermission: Boolean = false,
    /** False when the system Location toggle is off. UI surfaces an
     *  actionable banner with an intent to system Location settings. */
    val locationEnabled: Boolean = true,
    /** Current adaptive ceiling of the speed dial. Persisted via
     *  [SettingsRepository]; survives process death. */
    val maxSpeedKmh: Float = SettingsRepository.DEFAULT_MAX_SPEED_KMH,
    val themeOverride: ThemeOverride = ThemeOverride.System,
    val unitSystem: UnitSystem = UnitSystem.Metric,
)

@OptIn(FlowPreview::class) // For Flow.sample on the compass stream.
class DashboardViewModel(
    app: Application,
    private val locationRepo: LocationDataSource,
    private val sensorRepo: SensorDataSource,
    private val settings: SettingsDataSource,
    private val trailRepo: TrailDataSource,
) : AndroidViewModel(app) {

    // Recording lives in [TrailRecordingController] (process singleton)
    // because the service needs to feed it while the activity (and this
    // ViewModel) are not alive. We keep a thin facade on this VM for the
    // UI but don't own the recorder.

    private val _permission = MutableStateFlow(locationRepo.hasFineLocationPermission())
    val hasPermission: StateFlow<Boolean> = _permission.asStateFlow()

    /** Re-read the OS permission state. Call from the activity's
     *  `ON_RESUME` so a revoke-via-Settings round-trip flips the UI
     *  back to the permission-required screen. */
    fun refreshPermissionState() {
        _permission.value = locationRepo.hasFineLocationPermission()
    }

    // The sensor flow needs the latest fix to compute magnetic declination,
    // but it reads on the sensor delivery thread. A MutableStateFlow gives
    // safe cross-thread access without @Volatile and ties update-from-gnss
    // to read-from-sensor through a single observable.
    private val locationStateFlow = MutableStateFlow<Location?>(null)

    // Shared upstream — both `state` and `compass` observe this, so the
    // GNSS listener is only registered once even though they're separate
    // flows downstream. The `onEach` keeps `locationStateFlow` fed and
    // also forwards each snapshot to the recording controller (a no-op
    // when not recording, so it stays cheap). The controller's own
    // service does the same in the background; while both are alive we
    // just hand it slightly more snapshots — the recorder's per-point
    // distance/time gate dedupes them.
    private val gnssState: StateFlow<GnssSnapshot> = locationRepo.snapshots()
        .onEach {
            locationStateFlow.value = it.location
            TrailRecordingController.offer(it)
            // Rally RT delta + nav guidance — no-ops unless active;
            // both controllers dedupe against the service's feed.
            be.appmire.gpsinfo.data.rally.RallyController.offer(it)
            be.appmire.gpsinfo.data.nav.NavigationController.offer(it)
            // Track-back auto-advance: when the user gets within
            // ROUTE_PROXIMITY_M of the current route point, step to the
            // previous one. Cheap (one haversine per snapshot) and a
            // no-op when no Route navigation is active.
            it.location?.let { loc -> maybeAdvanceRoute(loc.latitude, loc.longitude) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssSnapshot())

    // BLE heart-rate manager — singleton tied to the application
    // context, accessed through a thin VM facade so the UI doesn't
    // import the data layer directly.
    private val hrRepo: HeartRateRepository = HeartRateRepository.getInstance(app)
    val hrState: StateFlow<HeartRateState> = hrRepo.state

    /** Begin a BLE scan for HR-service advertisements. Active until
     *  [stopHrScan] is called or the user pairs a device. */
    fun startHrScan() = hrRepo.startScan()
    fun stopHrScan() = hrRepo.stopScan()

    /** Pair the chosen device and persist its MAC. The repo will hold
     *  the connection across configuration changes. */
    fun pairHrDevice(mac: String, friendlyName: String?) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setHrDevice(mac, friendlyName)
            hrRepo.connect(mac, friendlyName)
        }
    }

    fun forgetHrDevice() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setHrDevice(null, null)
            hrRepo.disconnect()
        }
    }

    /** Drop the live GATT connection but keep the device paired. The
     *  dashboard card hides until the user reconnects (either via the
     *  pairing screen or a future Reconnect action). On next cold start
     *  the persisted MAC means [connectHrIfPaired] tries to reconnect
     *  automatically — i.e., this is a session-scoped disconnect. */
    fun disconnectHr() {
        hrRepo.disconnect()
    }

    /** Resume connection on activity start when a device is paired. */
    fun connectHrIfPaired() {
        (settings as? SettingsRepository)?.let { hrRepo.connectIfPaired(it) }
    }

    /** Latest scan results, snapshotted for the pairing UI. */
    val hrScanResultsView get() = hrRepo.lastScanResultsView

    // BLE cycling-power manager — same VM facade pattern as HR.
    private val cpRepo: CyclingPowerRepository = CyclingPowerRepository.getInstance(app)
    val cpState: StateFlow<CyclingPowerState> = cpRepo.state

    fun startCpScan() = cpRepo.startScan()
    fun stopCpScan() = cpRepo.stopScan()

    /** Pair the chosen cycling-power meter and persist its MAC. */
    fun pairCpDevice(mac: String, friendlyName: String?) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setCpDevice(mac, friendlyName)
            cpRepo.connect(mac, friendlyName)
        }
    }

    fun forgetCpDevice() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setCpDevice(null, null)
            cpRepo.disconnect()
        }
    }

    fun disconnectCp() {
        cpRepo.disconnect()
    }

    fun connectCpIfPaired() {
        (settings as? SettingsRepository)?.let { cpRepo.connectIfPaired(it) }
    }

    val cpScanResultsView get() = cpRepo.lastScanResultsView

    // Waypoints — user-captured POIs with optional voice / photo
    // attachments. The repository owns disk persistence; we just
    // surface its StateFlow and the mutator methods to the UI.
    private val waypointRepo: be.appmire.gpsinfo.data.WaypointRepository =
        be.appmire.gpsinfo.data.WaypointRepository.getInstance(app)
    val waypoints: StateFlow<List<be.appmire.gpsinfo.data.model.Waypoint>> = waypointRepo.waypoints

    /** Resolve the on-disk file for a waypoint media attachment. */
    fun waypointMediaFile(fileName: String): java.io.File =
        java.io.File(waypointRepo.mediaDir, fileName)

    /** Pre-resolved media directory — used by the capture sheet to
     *  hand the camera intent a destination path. */
    val waypointMediaDir: java.io.File get() = waypointRepo.mediaDir

    fun addWaypoint(w: be.appmire.gpsinfo.data.model.Waypoint) = waypointRepo.add(w)
    fun updateWaypoint(w: be.appmire.gpsinfo.data.model.Waypoint) = waypointRepo.update(w)
    fun deleteWaypoint(id: String) = waypointRepo.delete(id)

    /** Heart-rate zone config — drives the colour-coded BPM readout
     *  and the audible cue thresholds. Survives process death. */
    val hrZoneConfig: StateFlow<HrZoneConfig> =
        (settings as? SettingsRepository)?.hrZoneConfig
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, HrZoneConfig())
            ?: MutableStateFlow(HrZoneConfig())

    fun setHrZoneConfig(cfg: HrZoneConfig) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setHrZoneConfig(cfg)
        }
    }

    /** Singleton TTS-backed cue manager. Read [audibleCuesEnabled] for
     *  the user toggle; the manager itself honours that flag internally. */
    val audibleCues: AudibleCueManager = AudibleCueManager.getInstance(app)

    val audibleCuesEnabled: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.audibleCuesEnabled
            ?.onEach { audibleCues.enabled = it }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    fun setAudibleCuesEnabled(value: Boolean) {
        audibleCues.enabled = value
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setAudibleCuesEnabled(value)
        }
    }

    /** Singleton vibration cue manager — fires distinct patterns on
     *  the same threshold crosses as [audibleCues]. Independent toggle
     *  so headphone-less / silent-mode users can still get nudges. */
    val vibrationCues: be.appmire.gpsinfo.data.VibrationCueManager =
        be.appmire.gpsinfo.data.VibrationCueManager.getInstance(app)

    val vibrationCuesEnabled: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.vibrationCuesEnabled
            ?.onEach { vibrationCues.enabled = it }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    fun setVibrationCuesEnabled(value: Boolean) {
        vibrationCues.enabled = value
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setVibrationCuesEnabled(value)
        }
    }

    val personalStrideMeters: StateFlow<Float?> =
        (settings as? SettingsRepository)?.personalStrideMeters
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, null)
            ?: MutableStateFlow(null)

    fun setPersonalStrideMeters(value: Float?) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setPersonalStrideMeters(value)
        }
    }

    val nmeaLoggingEnabled: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.nmeaLoggingEnabled
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    fun setNmeaLoggingEnabled(value: Boolean) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setNmeaLoggingEnabled(value)
        }
    }

    val nmeaBtBridgeEnabled: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.nmeaBtBridgeEnabled
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    fun setNmeaBtBridgeEnabled(value: Boolean) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setNmeaBtBridgeEnabled(value)
        }
    }

    // Altitude smoothing — exponentially-weighted IIR filter that
    // takes the edge off the ±5-10 m per-sample jitter on consumer
    // GNSS. The filter lives on the VM scope so it carries state
    // across UI recompositions but resets on process death.
    private val altitudeFilter = be.appmire.gpsinfo.util.AltitudeFilter()

    val altitudeSmoothEnabled: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.altitudeSmoothEnabled
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, false)
            ?: MutableStateFlow(false)

    fun setAltitudeSmoothEnabled(value: Boolean) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setAltitudeSmoothEnabled(value)
            // A fresh toggle should start with a clean history so the
            // first displayed value matches the current raw fix rather
            // than the residual from before the toggle was off.
            if (value) altitudeFilter.reset()
        }
    }

    /** Displayed-altitude flow. When smoothing is on, returns the
     *  filter output; when off, the raw GNSS altitude. Both are in
     *  metres above the ellipsoid (what the chip reports). */
    val displayAltitudeMeters: StateFlow<Double?> =
        locationStateFlow.combine(altitudeSmoothEnabled) { loc, smooth ->
            val raw = loc?.takeIf { it.hasAltitude() }?.altitude
            if (smooth) altitudeFilter.update(raw) else raw
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Direct passthrough to the device pedometer for the stride-
     *  calibration screen. Caller owns its lifecycle (collectAsState
     *  inside the screen, dropped on dispose). */
    fun stepCounterFlow(): Flow<Long> =
        SensorRepository(getApplication()).stepCounterStream()

    /** Active dashboard profile — one of the built-in presets or the
     *  user-edited "Custom" profile. Drives which cards the dashboard
     *  surfaces and in what order. */
    val dashboardProfile: StateFlow<be.appmire.gpsinfo.data.model.DashboardProfile> =
        (settings as? SettingsRepository)?.let { s ->
            combine(
                s.dashboardProfileId,
                s.customProfileCardsRaw,
                s.customProfileAccent,
                s.customProfileChrome,
            ) { id, raw, accent, chrome ->
                if (id == be.appmire.gpsinfo.data.model.DashboardProfile.CUSTOM_ID) {
                    be.appmire.gpsinfo.data.model.DashboardProfile.customFrom(raw, accent, chrome)
                } else {
                    be.appmire.gpsinfo.data.model.DashboardProfile.fromId(id)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                be.appmire.gpsinfo.data.model.DashboardProfile.Default,
            )
        } ?: MutableStateFlow(be.appmire.gpsinfo.data.model.DashboardProfile.Default)

    fun setDashboardProfile(id: String) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setDashboardProfileId(id)
        }
    }

    // ---------- Ghost runner (virtual partner) ---------- //

    /** Persisted ghost selection (null = off). One of three sources:
     *  even target pace, a goal time+distance, or a past recorded run. */
    val ghostReference: StateFlow<be.appmire.gpsinfo.data.model.GhostReference?> =
        (settings as? SettingsRepository)?.ghostReference
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, null)
            ?: MutableStateFlow(null)

    // Runtime ghost curve, rebuilt whenever the reference changes. For
    // a past run we load the trail and sample its distance-over-time;
    // pace/goal are constant-speed.
    private val _ghost = MutableStateFlow<be.appmire.gpsinfo.data.model.Ghost?>(null)
    val ghost: StateFlow<be.appmire.gpsinfo.data.model.Ghost?> = _ghost.asStateFlow()

    // The loaded ghost trail (exposes the trail name to racing UIs).
    // Null unless racing a past run.
    private val _ghostTrail = MutableStateFlow<be.appmire.gpsinfo.data.model.Trail?>(null)
    val ghostTrail: StateFlow<be.appmire.gpsinfo.data.model.Trail?> = _ghostTrail.asStateFlow()

    init {
        viewModelScope.launch {
            ghostReference.collect { ref -> buildGhost(ref) }
        }
    }

    private suspend fun buildGhost(ref: be.appmire.gpsinfo.data.model.GhostReference?) {
        when (ref) {
            null -> {
                _ghost.value = null
                _ghostTrail.value = null
            }
            is be.appmire.gpsinfo.data.model.GhostReference.TargetPace -> {
                _ghost.value =
                    be.appmire.gpsinfo.data.model.ConstantPaceGhost.fromPaceSecPerKm(ref.secondsPerKm)
                _ghostTrail.value = null
            }
            is be.appmire.gpsinfo.data.model.GhostReference.Goal -> {
                _ghost.value = be.appmire.gpsinfo.data.model.ConstantPaceGhost
                    .fromGoal(ref.totalSeconds, ref.totalMeters)
                _ghostTrail.value = null
            }
            is be.appmire.gpsinfo.data.model.GhostReference.PastRun -> {
                val trail = trailRepo.load(ref.trailId)
                _ghostTrail.value = trail
                _ghost.value = trail?.let { buildTrailGhost(it) }
            }
        }
    }

    private fun buildTrailGhost(
        trail: be.appmire.gpsinfo.data.model.Trail,
    ): be.appmire.gpsinfo.data.model.Ghost? {
        val pts = trail.points
        if (pts.size < 2) return null
        val start = trail.startTimeMillis ?: pts.first().timeMillis
        val samples = ArrayList<be.appmire.gpsinfo.data.model.TrailGhost.Sample>(pts.size)
        samples.add(be.appmire.gpsinfo.data.model.TrailGhost.Sample(0L, 0.0))
        var cum = 0.0
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val cur = pts[i]
            cum += NavigationMath.distanceMetres(prev.latDeg, prev.lonDeg, cur.latDeg, cur.lonDeg)
            samples.add(
                be.appmire.gpsinfo.data.model.TrailGhost.Sample(
                    (cur.timeMillis - start).coerceAtLeast(0L), cum,
                ),
            )
        }
        return be.appmire.gpsinfo.data.model.TrailGhost(samples)
    }

    /** Live gap to the ghost — recomputed every second against the
     *  running distance + elapsed. Null when not recording or no ghost.
     *  Positive = runner is ahead. */
    val ghostGap: StateFlow<be.appmire.gpsinfo.data.model.GhostGap?> =
        combine(
            flow { while (true) { emit(System.currentTimeMillis()); delay(1_000L) } },
            TrailRecordingController.state,
            ghost,
        ) { now, rec, gh ->
            val recording = rec as? RecordingState.Recording ?: return@combine null
            val g = gh ?: return@combine null
            val elapsedMs = (now - recording.startedAtMillis).coerceAtLeast(0L)
            val d = recording.distanceMetres
            val aheadSec = (g.elapsedMsAtDistance(d) - elapsedMs) / 1000.0
            val aheadM = d - g.distanceMetersAt(elapsedMs)
            val finished = d >= g.totalMeters || elapsedMs >= g.totalMillis
            be.appmire.gpsinfo.data.model.GhostGap(aheadSec, aheadM, finished)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setGhostPace(secondsPerKm: Float) =
        persistGhost(be.appmire.gpsinfo.data.model.GhostReference.TargetPace(secondsPerKm))

    fun setGhostGoal(totalSeconds: Long, totalMeters: Double) =
        persistGhost(be.appmire.gpsinfo.data.model.GhostReference.Goal(totalSeconds, totalMeters))

    /** Race a saved trail. Kept name-compatible with the Trails-list
     *  "Race against this" entry. */
    fun setGhostTrail(id: String?) {
        if (id == null) {
            clearGhost()
            return
        }
        viewModelScope.launch {
            val name = trailRepo.load(id)?.name ?: id
            persistGhost(be.appmire.gpsinfo.data.model.GhostReference.PastRun(id, name))
        }
    }

    fun clearGhost() = persistGhost(null)

    private fun persistGhost(ref: be.appmire.gpsinfo.data.model.GhostReference?) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setGhostReference(ref)
        }
    }

    /** Persist a new ordering/visibility and optional accent / chrome
     *  for the Custom profile and switch the active profile to it.
     *  UI calls this from the profile editor's Save button. Pass null
     *  for [accentArgb] / [chromeStyle] to leave the existing value
     *  intact. */
    fun saveCustomProfile(
        cards: List<be.appmire.gpsinfo.data.model.DashboardSection>,
        accentArgb: Int? = null,
        chromeStyle: be.appmire.gpsinfo.data.model.ChromeStyle? = null,
    ) {
        viewModelScope.launch {
            val repo = settings as? SettingsRepository ?: return@launch
            repo.setCustomProfileCardsRaw(
                be.appmire.gpsinfo.data.model.DashboardProfile.encodeCards(cards)
            )
            if (accentArgb != null) repo.setCustomProfileAccent(accentArgb)
            if (chromeStyle != null) repo.setCustomProfileChrome(chromeStyle)
            repo.setDashboardProfileId(be.appmire.gpsinfo.data.model.DashboardProfile.CUSTOM_ID)
        }
    }

    // The off-route-alarm init block used to live here, but it
    // referenced [_navigationTarget] which wasn't declared until
    // further down — so on Main-immediate dispatch the launched
    // coroutine started before that property was initialised and
    // combine() received a null Flow, crashing on first collect.
    // The relocated block lives below the [_navigationTarget]
    // declaration so all properties it captures are non-null by the
    // time the coroutine body runs.

    val dashboardDensity: StateFlow<DashboardDensity> =
        (settings as? SettingsRepository)?.dashboardDensity
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardDensity.Standard)
            ?: MutableStateFlow(DashboardDensity.Standard)

    fun setDashboardDensity(value: DashboardDensity) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setDashboardDensity(value)
        }
    }

    /** Resettable trip-computer totals derived from the trail library.
     *  Folds [trails] against the user's last reset timestamp; emits
     *  a fresh [be.appmire.gpsinfo.util.TripStats] whenever either
     *  list or timestamp changes. */
    val tripStats: StateFlow<be.appmire.gpsinfo.util.TripStats> = run {
        val resetFlow = (settings as? SettingsRepository)?.tripResetMillis
            ?: kotlinx.coroutines.flow.flowOf(0L)
        combine(trailRepo.trails, resetFlow) { trails, since ->
            be.appmire.gpsinfo.util.TripStats.from(trails, since)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            be.appmire.gpsinfo.util.TripStats(0L, 0, 0.0, 0L),
        )
    }

    fun resetTrip() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setTripResetMillis(System.currentTimeMillis())
        }
    }

    private val _navigationTarget = MutableStateFlow<NavigationTarget?>(null)
    /** Active navigation goal — null when the user isn't navigating to
     *  anything. The dashboard's NavigationCard observes this. */
    val navigationTarget: StateFlow<NavigationTarget?> = _navigationTarget.asStateFlow()

    /** Shared heading-source decision across every UI surface that
     *  cares (CompassCard, SpeedCard, NavigationCard, LiveMap …).
     *  Tracked in the VM so two surfaces never disagree at the
     *  3 km/h threshold — see [be.appmire.gpsinfo.util.HeadingModeTracker]. */
    private val headingModeTracker = be.appmire.gpsinfo.util.HeadingModeTracker()
    val headingMode: StateFlow<be.appmire.gpsinfo.util.HeadingMode> = locationStateFlow
        .map { loc ->
            val kmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
            headingModeTracker.update(kmh)
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            be.appmire.gpsinfo.util.HeadingMode.MagneticOnly,
        )

    init {
        // Off-route alarm. Watches (location, navTarget) and fires the
        // audible + vibration cue managers on threshold transitions.
        // Lives in the VM (not a screen) so the alarm follows the user
        // across Dashboard, Sports view, and Trail Map — anywhere they
        // happen to be while a route is active.
        //
        // Declared AFTER [_navigationTarget] so the property is
        // non-null when the coroutine body resolves the combine
        // arguments (Main-immediate dispatch was running the launch
        // synchronously inside the constructor).
        viewModelScope.launch {
            combine(locationStateFlow, _navigationTarget) { loc, target ->
                val route = target as? NavigationTarget.Route
                if (route == null || loc == null) return@combine null
                be.appmire.gpsinfo.util.NavigationMath
                    .minDistanceToRouteMetres(route.points, loc.latitude, loc.longitude)
            }.collect { distance ->
                val d = distance ?: return@collect
                val isOff = d > OFF_ROUTE_THRESHOLD_M
                audibleCues.reportOffRoute(isOff)
                vibrationCues.reportOffRoute(isOff)
            }
        }
    }

    fun setNavigationTarget(target: NavigationTarget) {
        _navigationTarget.value = target
    }

    fun clearNavigation() {
        _navigationTarget.value = null
    }

    /**
     * Set or clear the active pace goal on the current navigation target.
     * No-op when no navigation is active — the goal lives on the target,
     * not as a floating piece of state, so it disappears with the target.
     *
     * [paceSecondsPerUnit] is in the user's currently selected unit
     * system (the caller has already converted from the input control).
     */
    fun setTargetPace(paceSecondsPerUnit: Float?) {
        val current = _navigationTarget.value ?: return
        _navigationTarget.value = current.withTargetPace(paceSecondsPerUnit)
    }

    /**
     * Start a track-back session for the given trail. The starting
     * waypoint is the trail point nearest the user's current fix —
     * handles both the "I just finished, take me back" case (nearest =
     * last point) and the "I walked off the trail, get me back on it"
     * case (nearest = whichever point is geographically closest).
     *
     * No-op when the trail has no points or there's no current fix.
     */
    fun startTrackBack(trail: be.appmire.gpsinfo.data.model.Trail) {
        val pts = trail.points
        if (pts.isEmpty()) return
        val loc = state.value.gnss.location
        val nearestIdx = if (loc != null) {
            var bestIdx = pts.size - 1
            var bestD = Double.MAX_VALUE
            for ((i, p) in pts.withIndex()) {
                val d = NavigationMath.distanceMetres(
                    loc.latitude, loc.longitude, p.latDeg, p.lonDeg,
                )
                if (d < bestD) { bestD = d; bestIdx = i }
            }
            bestIdx
        } else {
            // No fix yet — start at the end of the trail; auto-advance
            // will move the cursor once the first GNSS snapshot arrives.
            pts.size - 1
        }
        _navigationTarget.value = NavigationTarget.Route(
            points = pts,
            currentIdx = nearestIdx,
            trailName = trail.name,
        )
    }

    /**
     * Walks a [NavigationTarget.Route] one step closer to its start when
     * the user is within [ROUTE_PROXIMITY_M] of the current waypoint.
     * Once the index passes the start of the trail, navigation is
     * cleared — track-back has reached its end.
     */
    private fun maybeAdvanceRoute(latDeg: Double, lonDeg: Double) {
        val route = _navigationTarget.value as? NavigationTarget.Route ?: return
        val d = NavigationMath.distanceMetres(
            latDeg, lonDeg, route.targetLatDeg, route.targetLonDeg,
        )
        if (d <= ROUTE_PROXIMITY_M) {
            val nextIdx = route.currentIdx - 1
            _navigationTarget.value =
                if (nextIdx < 0) null else route.copy(currentIdx = nextIdx)
        }
    }

    // Sample the rotation-vector stream down from ~50 Hz to ~30 Hz before
    // it enters combine. Compose can't render faster than the display
    // refresh, and the saving multiplies through the whole state graph.
    private val compassFlow = sensorRepo.readings { locationStateFlow.value }.sample(33L)

    /** Compass sub-state, exposed independently of the main dashboard
     *  state. Splitting it off means screens that don't show a heading
     *  (the satellite list, the About screen) don't recompose at 30 Hz
     *  when the user rotates the phone.
     *
     *  Combined with [gnssState] so that observing the compass also keeps
     *  the GNSS subscription alive — that's how [locationStateFlow] stays
     *  fed for the declination lookup. */
    val compass: StateFlow<CompassReading> = combine(
        gnssState,
        compassFlow,
    ) { _, c -> c }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompassReading())

    /** Live G-force for the G-meter card — its own ~30 Hz flow (sampled
     *  from the game-rate accelerometer) so only screens showing it pay
     *  the cost. WhileSubscribed stops the sensor when nothing observes. */
    val gForce: StateFlow<be.appmire.gpsinfo.data.model.GForceSample> =
        sensorRepo.gForceStream(
            currentBearingDeg = {
                // Only trust GPS course above a walking pace — below
                // that it's noise; the stream holds the last good value.
                locationStateFlow.value
                    ?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.5f }
                    ?.bearing
            },
        )
            .sample(33L)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                be.appmire.gpsinfo.data.model.GForceSample(0f, 0f, 0f),
            )

    /** 500 ms tick. Used both for the clock readout and for throttling the
     *  sun-position recompute — without this, the calc runs at 50 Hz with
     *  every sensor tick for zero UI gain. */
    private val tick = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(500)
        }
    }

    /** Re-check the system Location toggle every tick so the banner
     *  appears within ~500 ms of the user toggling it. */
    private val locationEnabledFlow = flow {
        while (true) {
            emit(locationRepo.isLocationEnabled())
            delay(500)
        }
    }

    private val themeFlow = settings.themeOverride.stateIn(
        viewModelScope, SharingStarted.Eagerly, ThemeOverride.System
    )

    private val maxSpeedFlow: StateFlow<Float> = settings.maxSpeedKmh.stateIn(
        viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_MAX_SPEED_KMH
    )

    private val unitSystemFlow: StateFlow<UnitSystem> = settings.unitSystem.stateIn(
        viewModelScope, SharingStarted.Eagerly, UnitSystem.defaultFor(java.util.Locale.getDefault())
    )

    // Cache: only recompute the sun position when the *minute* changes or
    // location moves materially. Keyed by (minute-of-day, latitude rounded
    // to 4 decimals, longitude rounded to 4 decimals).
    private var lastSunKey: Triple<Long, Double, Double>? = null
    private var lastSun: SunInfo? = null

    val state: StateFlow<DashboardUiState> = combine(
        listOf<Flow<*>>(gnssState, tick, _permission, locationEnabledFlow, themeFlow, maxSpeedFlow, unitSystemFlow)
    ) { values ->
        val gnss = values[0] as GnssSnapshot
        val now = values[1] as Long
        val perm = values[2] as Boolean
        val locEnabled = values[3] as Boolean
        val theme = values[4] as ThemeOverride
        val maxSpeed = values[5] as Float
        val unit = values[6] as UnitSystem

        val loc = gnss.location
        val sun = computeSun(now, loc)

        // Adapt the speed ceiling. The check is cheap; the DataStore write
        // is only fired when the value actually changes, so this won't
        // hammer the IO dispatcher.
        val measuredKmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f) ?: 0f
        val newMax = adaptMaxSpeed(measuredKmh, maxSpeed)
        if (newMax != maxSpeed) {
            viewModelScope.launch { settings.setMaxSpeedKmh(newMax) }
        }

        DashboardUiState(
            gnss = gnss,
            sun = sun,
            nowMillis = now,
            hasPermission = perm,
            locationEnabled = locEnabled,
            maxSpeedKmh = newMax,
            themeOverride = theme,
            unitSystem = unit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun setThemeOverride(value: ThemeOverride) {
        viewModelScope.launch { settings.setThemeOverride(value) }
    }

    fun setUnitSystem(value: UnitSystem) {
        viewModelScope.launch { settings.setUnitSystem(value) }
    }

    // ---------- Trail recording ----------

    /** Live state of the in-flight recording. Sourced from the process-
     *  wide controller so the FAB reflects what the background service
     *  is doing while the activity is paused. */
    val recordingState: StateFlow<RecordingState> = TrailRecordingController.state

    /** Live list of saved trails for the trails list screen. */
    val trails: StateFlow<List<TrailSummary>> = trailRepo.trails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Start a recording — the controller fires up the foreground service
     * so capture survives the activity going to background. Requires a
     * Context so we can hand it to the service intent; pass the activity
     * or any non-null Context.
     */
    fun startRecording(context: Context) = TrailRecordingController.startRecording(context)

    /**
     * Stop the recording and persist whatever was captured. If no points
     * were captured, nothing is saved. Returns the new trail id (or null
     * if the recording was empty).
     */
    suspend fun stopRecording(context: Context, name: String): String? {
        // Capture the active pace target before stopping — the
        // controller clears state on stop, but we want the run that
        // just finished to carry its target into the GPX.
        val target = _navigationTarget.value?.targetPaceSecondsPerUnit
        val unit = state.value.unitSystem
        val targetPerKm: Float? = target?.let {
            // Normalise to seconds per km for storage. The display layer
            // re-converts to whatever unit the user is showing.
            when (unit) {
                UnitSystem.Metric -> it
                UnitSystem.Imperial -> it / 1.609344f
                UnitSystem.Nautical -> it / 1.852f
            }
        }
        val result = TrailRecordingController.stopRecording(context)
        if (result.points.isEmpty()) return null
        return trailRepo.save(
            name = name.ifBlank { "Untitled trail" },
            points = result.points,
            targetPaceSecondsPerKm = targetPerKm,
            laps = result.laps,
        )
    }

    /** Mark a lap split at the current instant. Returns the new
     *  marker (for a toast / chip), or null if not recording. */
    fun markLap(): be.appmire.gpsinfo.data.model.LapMarker? =
        TrailRecordingController.recordLap()

    /** Manually pause the recorder. Auto-resume on movement still
     *  applies — the user is opting in to a temporary freeze, not
     *  disabling the smart-pause heuristic. */
    fun pauseRecording() = TrailRecordingController.pauseRecording()

    /** Cancel a pause immediately. */
    fun resumeRecording() = TrailRecordingController.resumeRecording()

    suspend fun setTrailTags(id: String, tags: List<String>): Boolean =
        trailRepo.setTags(id, tags)

    /** Drop a recording in progress without saving. The ghost
     *  selection persists for the next run. */
    fun discardRecording(context: Context) {
        TrailRecordingController.stopRecording(context)
    }

    suspend fun loadTrail(id: String) = trailRepo.load(id)

    suspend fun deleteTrail(id: String) = trailRepo.delete(id)

    /** GPX file on disk for the given trail, ready for FileProvider. */
    fun trailFile(id: String): java.io.File? = trailRepo.gpxFile(id)

    /** Materialise the trail as a FIT activity file in cache and
     *  return the path. See [TrailDataSource.fitFile]. */
    suspend fun trailFitFile(id: String): java.io.File? = trailRepo.fitFile(id)

    /** Parse an externally-sourced GPX (e.g. picked via SAF) and store it. */
    suspend fun importGpx(input: java.io.InputStream, suggestedName: String): String? =
        trailRepo.importGpx(input, suggestedName)

    /** Simplify a stored trail. See [TrailDataSource.simplify]. */
    suspend fun simplifyTrail(id: String, epsilonMeters: Double, replace: Boolean): String? =
        trailRepo.simplify(id, epsilonMeters, replace)

    suspend fun renameTrail(id: String, newName: String): Boolean =
        trailRepo.rename(id, newName)

    /** Persist updated per-point data — used by the per-segment pace
     *  targets editor when the user saves their edits. */
    suspend fun updateTrailPoints(
        id: String,
        newPoints: List<be.appmire.gpsinfo.data.model.TrailPoint>,
    ): Boolean = trailRepo.updatePoints(id, newPoints)

    /** Flow that emits true once the user has been shown the onboarding
     *  tour. Initial value `true` — the dialog only opens once the
     *  DataStore has been read at least once and reports `false`. That's
     *  fine on a real device: the read completes within a handful of
     *  milliseconds, and the alternative (default `false`) would briefly
     *  show the tour to returning users every cold start. */
    val onboardingSeen: StateFlow<Boolean> = settings.onboardingSeen
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun markOnboardingSeen() {
        viewModelScope.launch { settings.setOnboardingSeen(true) }
    }

    fun resetOnboarding() {
        viewModelScope.launch { settings.setOnboardingSeen(false) }
    }

    /** New onboarding: the Language/Units/Theme prefs are written by the
     *  screen as they're picked; this just seeds the default dashboard
     *  profile and marks onboarding done (→ lands on the Dashboard). */
    fun finishOnboarding() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setDashboardProfileId("default")
            settings.setOnboardingSeen(true)
        }
    }

    /** Per-activity Simple/Pro detail level (absent → Simple). */
    val detailLevels: StateFlow<Map<be.appmire.gpsinfo.ui.activity.Activity, be.appmire.gpsinfo.ui.activity.DetailLevel>> =
        ((settings as? SettingsRepository)?.activityDetailLevels
            ?: kotlinx.coroutines.flow.flowOf(emptyMap()))
            .map { raw ->
                raw.mapNotNull { (k, v) ->
                    val a = runCatching { be.appmire.gpsinfo.ui.activity.Activity.valueOf(k) }.getOrNull()
                    val d = runCatching { be.appmire.gpsinfo.ui.activity.DetailLevel.valueOf(v) }.getOrNull()
                    if (a != null && d != null) a to d else null
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun detailLevelOf(activity: be.appmire.gpsinfo.ui.activity.Activity): be.appmire.gpsinfo.ui.activity.DetailLevel =
        detailLevels.value[activity] ?: be.appmire.gpsinfo.ui.activity.DetailLevel.SIMPLE

    fun setActivityDetail(
        activity: be.appmire.gpsinfo.ui.activity.Activity,
        level: be.appmire.gpsinfo.ui.activity.DetailLevel,
    ) {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setActivityDetailLevel(activity.name, level.name)
        }
    }

    // ---------- Play Store rating nudge ----------

    /** Cold-start count, persisted. Drives [showRateNudge]; bumped once
     *  per process via [registerColdStart]. */
    private val appLaunchCount: StateFlow<Int> =
        (settings as? SettingsRepository)?.appLaunchCount
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
            ?: MutableStateFlow(0)

    /** True once the user has launched the app enough times, hasn't
     *  permanently dismissed the prompt, and any "Not now" snooze has
     *  elapsed. The dashboard observes this to show its one-shot
     *  [be.appmire.gpsinfo.ui.rating.RateNudgeDialog]. */
    val showRateNudge: StateFlow<Boolean> =
        (settings as? SettingsRepository)?.let { s ->
            combine(
                appLaunchCount,
                s.rateNudgeDismissed,
                s.rateNudgeSnoozeUntilLaunch,
            ) { count, dismissed, snoozeUntil ->
                !dismissed && count >= maxOf(RATE_NUDGE_FIRST_LAUNCH, snoozeUntil)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
        } ?: MutableStateFlow(false)

    /** Count this process launch toward the rating-nudge threshold.
     *  Caller is responsible for invoking exactly once per cold start. */
    fun registerColdStart() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.incrementAppLaunchCount()
        }
    }

    /** User tapped "Rate" — never prompt again. Opening the Play listing
     *  is the UI's job. */
    fun onRateNudgeAccepted() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setRateNudgeDismissed(true)
        }
    }

    /** "Not now" — push the prompt [RATE_NUDGE_SNOOZE_LAUNCHES] cold
     *  starts into the future. */
    fun onRateNudgeSnoozed() {
        viewModelScope.launch {
            val s = settings as? SettingsRepository ?: return@launch
            s.setRateNudgeSnoozeUntilLaunch(appLaunchCount.value + RATE_NUDGE_SNOOZE_LAUNCHES)
        }
    }

    /** "Don't ask again" — permanent dismissal. */
    fun onRateNudgeDeclined() {
        viewModelScope.launch {
            (settings as? SettingsRepository)?.setRateNudgeDismissed(true)
        }
    }

    // ---------- Update nudge (GitHub Releases) ----------

    /** This build's version name (e.g. "2.0.1"), read once from the
     *  package manager. Empty string if it can't be resolved — which
     *  makes every release look "newer", so we fail toward showing the
     *  banner rather than hiding a real update. */
    private val installedVersionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: ""

    /** The newer release version to advertise, or null when we're already
     *  current or the user dismissed this version. Drives the dashboard's
     *  [be.appmire.gpsinfo.ui.dashboard.UpdateAvailableBanner]. */
    val updateAvailable: StateFlow<String?> =
        (settings as? SettingsRepository)?.let { s ->
            combine(s.updateLatestVersion, s.updateDismissedVersion) { latest, dismissed ->
                if (latest != null &&
                    latest != dismissed &&
                    be.appmire.gpsinfo.util.VersionCompare.isNewer(latest, installedVersionName)
                ) latest else null
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
        } ?: MutableStateFlow(null)

    /** Probe GitHub for a newer release, at most once per
     *  [UPDATE_CHECK_INTERVAL_MS]. Network runs on IO and any failure is
     *  silent — the timestamp advances regardless so a flaky connection
     *  can't turn this into a per-launch hammer. Safe to call on every
     *  cold start. */
    fun maybeCheckForUpdate() {
        val s = settings as? SettingsRepository ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (now - s.updateCheckLastMillis.first() < UPDATE_CHECK_INTERVAL_MS) return@launch
            s.setUpdateCheckLastMillis(now)
            val latest = withContext(Dispatchers.IO) {
                be.appmire.gpsinfo.data.GithubReleaseChecker.fetchLatestVersionName(GITHUB_REPO)
            }
            if (latest != null) s.setUpdateLatestVersion(latest)
        }
    }

    /** Hide the update banner for the current latest version. A later
     *  release (newer than what was dismissed) brings it back. */
    fun dismissUpdate() {
        val s = settings as? SettingsRepository ?: return
        viewModelScope.launch {
            val latest = s.updateLatestVersion.first() ?: return@launch
            s.setUpdateDismissedVersion(latest)
        }
    }

    /**
     * Save the latest GPS fix as a one-point trail. Useful for marking a
     * specific spot without leaving the dashboard. Returns the new id,
     * or null when there is no fix to save.
     */
    suspend fun saveCurrentAsWaypoint(name: String): String? {
        val loc = state.value.gnss.location ?: return null
        val point = be.appmire.gpsinfo.data.model.TrailPoint(
            timeMillis = if (loc.time > 0) loc.time else System.currentTimeMillis(),
            latDeg = loc.latitude,
            lonDeg = loc.longitude,
            eleMeters = if (loc.hasAltitude()) loc.altitude else null,
            hAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
            vAccuracyM = if (android.os.Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy())
                loc.verticalAccuracyMeters else null,
            satellitesInFix = state.value.gnss.satellitesInUse.takeIf { it > 0 },
        )
        return trailRepo.save(name = name.ifBlank { "Waypoint" }, points = listOf(point))
    }

    private fun computeSun(nowMillis: Long, loc: Location?): SunInfo? {
        if (loc == null) {
            lastSunKey = null
            lastSun = null
            return null
        }
        val minuteKey = nowMillis / 60_000L
        val latKey = (loc.latitude * 10_000.0).toLong() / 10_000.0
        val lonKey = (loc.longitude * 10_000.0).toLong() / 10_000.0
        val key = Triple(minuteKey, latKey, lonKey)
        val cached = lastSun
        if (cached != null && key == lastSunKey) return cached
        val fresh = SunPositionCalculator.compute(nowMillis, loc.latitude, loc.longitude)
        lastSunKey = key
        lastSun = fresh
        return fresh
    }

    companion object {
        /** Off-route alarm threshold. Anything beyond this from every
         *  route point is treated as "off route" and fires a one-shot
         *  audible + vibration cue. Generous on purpose — GPS jitter
         *  in urban canyons can spike 20-30 m even when the runner is
         *  on the line. */
        private const val OFF_ROUTE_THRESHOLD_M = 75.0

        /** Track-back auto-advance threshold. Distance under which the
         *  user is considered to have reached the current route point and
         *  the index steps to the previous one. 20 m is wider than GPS
         *  jitter (~5 m good fix, ~10 m mediocre) but tight enough that
         *  the user isn't told "you've arrived" from across the road. */
        private const val ROUTE_PROXIMITY_M = 20.0

        /** Cold-start count at which the Play Store rating prompt first
         *  appears. Five launches is enough that the user has formed an
         *  opinion of the app without being nagged on day one. */
        private const val RATE_NUDGE_FIRST_LAUNCH = 5

        /** A "Not now" tap pushes the next prompt this many cold starts
         *  into the future. */
        private const val RATE_NUDGE_SNOOZE_LAUNCHES = 5

        /** Minimum gap between GitHub-releases update checks. */
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** "owner/name" of the public repo whose releases we compare
         *  against. Matches the GitHub link on the About screen. */
        private const val GITHUB_REPO = "jeroentrappers/gpsinfo"

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(
                    app = application,
                    // Honour any test override set before the activity launched;
                    // otherwise use the real implementations. In production
                    // every field of [TestDataSourceOverride] is always null
                    // — the only cost is one nullable lookup at startup.
                    locationRepo = TestDataSourceOverride.location
                        ?: LocationRepository(application),
                    sensorRepo = TestDataSourceOverride.sensor
                        ?: SensorRepository(application),
                    settings = TestDataSourceOverride.settings
                        ?: SettingsRepository(application),
                    trailRepo = TestDataSourceOverride.trails
                        ?: TrailRepository(application),
                )
            }
        }
    }
}

/**
 * If [measuredKmh] exceeds [currentMaxKmh], adapt the ceiling to
 * `measured × 1.3` rounded up to the next 10 km/h. Otherwise returns
 * [currentMaxKmh] unchanged. Never shrinks.
 *
 * Pure function, extracted for unit testability.
 */
fun adaptMaxSpeed(measuredKmh: Float, currentMaxKmh: Float): Float {
    if (measuredKmh <= currentMaxKmh) return currentMaxKmh
    return (ceil(measuredKmh.toDouble() * 1.3 / 10.0) * 10.0).toFloat()
}
