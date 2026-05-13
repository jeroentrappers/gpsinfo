package com.appmire.gpsinfo.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.appmire.gpsinfo.data.LocationDataSource
import com.appmire.gpsinfo.data.LocationRepository
import com.appmire.gpsinfo.data.SensorDataSource
import com.appmire.gpsinfo.data.SensorRepository
import com.appmire.gpsinfo.data.SettingsDataSource
import com.appmire.gpsinfo.data.SettingsRepository
import com.appmire.gpsinfo.data.TestDataSourceOverride
import com.appmire.gpsinfo.data.ThemeOverride
import com.appmire.gpsinfo.data.UnitSystem
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.SunInfo
import com.appmire.gpsinfo.data.sun.SunPositionCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

data class DashboardUiState(
    val gnss: GnssSnapshot = GnssSnapshot(),
    val compass: CompassReading = CompassReading(),
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

class DashboardViewModel(
    app: Application,
    private val locationRepo: LocationDataSource,
    private val sensorRepo: SensorDataSource,
    private val settings: SettingsDataSource,
) : AndroidViewModel(app) {

    private val _permission = MutableStateFlow(locationRepo.hasFineLocationPermission())
    val hasPermission: StateFlow<Boolean> = _permission.asStateFlow()

    fun onPermissionGranted() {
        _permission.value = locationRepo.hasFineLocationPermission()
    }

    private val gnssFlow = locationRepo.snapshots()
    private var lastLocation: Location? = null
    private val compassFlow = sensorRepo.readings { lastLocation }

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
        listOf(gnssFlow, compassFlow, tick, _permission, locationEnabledFlow, themeFlow, maxSpeedFlow, unitSystemFlow)
    ) { values ->
        val gnss = values[0] as GnssSnapshot
        val compass = values[1] as CompassReading
        val now = values[2] as Long
        val perm = values[3] as Boolean
        val locEnabled = values[4] as Boolean
        val theme = values[5] as ThemeOverride
        val maxSpeed = values[6] as Float
        val unit = values[7] as UnitSystem

        lastLocation = gnss.location
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
            compass = compass,
            sun = sun,
            nowMillis = now,
            hasPermission = perm,
            locationEnabled = locEnabled,
            maxSpeedKmh = newMax,
            themeOverride = theme,
            unitSystem = unit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun setThemeOverride(value: ThemeOverride) {
        viewModelScope.launch { settings.setThemeOverride(value) }
    }

    fun setUnitSystem(value: UnitSystem) {
        viewModelScope.launch { settings.setUnitSystem(value) }
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
