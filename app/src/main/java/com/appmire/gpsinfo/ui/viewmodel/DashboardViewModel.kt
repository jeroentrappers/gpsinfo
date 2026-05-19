package com.appmire.gpsinfo.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import com.appmire.gpsinfo.data.LocationDataSource
import com.appmire.gpsinfo.data.LocationRepository
import com.appmire.gpsinfo.data.RecordingState
import com.appmire.gpsinfo.data.SensorDataSource
import com.appmire.gpsinfo.data.SensorRepository
import com.appmire.gpsinfo.data.SettingsDataSource
import com.appmire.gpsinfo.data.SettingsRepository
import com.appmire.gpsinfo.data.TestDataSourceOverride
import com.appmire.gpsinfo.data.ThemeOverride
import com.appmire.gpsinfo.data.TrailDataSource
import com.appmire.gpsinfo.data.TrailRecordingController
import com.appmire.gpsinfo.data.TrailRepository
import com.appmire.gpsinfo.data.TrailSummary
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.SunInfo
import com.appmire.gpsinfo.data.sun.SunPositionCalculator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GnssSnapshot())

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
        val pts = TrailRecordingController.stopRecording(context)
        if (pts.isEmpty()) return null
        return trailRepo.save(name = name.ifBlank { "Untitled trail" }, points = pts)
    }

    /** Drop a recording in progress without saving. */
    fun discardRecording(context: Context) {
        TrailRecordingController.stopRecording(context)
    }

    suspend fun loadTrail(id: String) = trailRepo.load(id)

    suspend fun deleteTrail(id: String) = trailRepo.delete(id)

    /** GPX file on disk for the given trail, ready for FileProvider. */
    fun trailFile(id: String): java.io.File? = trailRepo.gpxFile(id)

    /** Parse an externally-sourced GPX (e.g. picked via SAF) and store it. */
    suspend fun importGpx(input: java.io.InputStream, suggestedName: String): String? =
        trailRepo.importGpx(input, suggestedName)

    /** Simplify a stored trail. See [TrailDataSource.simplify]. */
    suspend fun simplifyTrail(id: String, epsilonMeters: Double, replace: Boolean): String? =
        trailRepo.simplify(id, epsilonMeters, replace)

    suspend fun renameTrail(id: String, newName: String): Boolean =
        trailRepo.rename(id, newName)

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

    /**
     * Save the latest GPS fix as a one-point trail. Useful for marking a
     * specific spot without leaving the dashboard. Returns the new id,
     * or null when there is no fix to save.
     */
    suspend fun saveCurrentAsWaypoint(name: String): String? {
        val loc = state.value.gnss.location ?: return null
        val point = com.appmire.gpsinfo.data.model.TrailPoint(
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
