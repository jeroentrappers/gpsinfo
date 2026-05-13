package com.appmire.gpsinfo.ui.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.appmire.gpsinfo.data.LocationRepository
import com.appmire.gpsinfo.data.SensorRepository
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
import kotlin.math.ceil

/** Where the speed dial's scale starts at app launch. Bumps upward
 *  automatically when the measured speed climbs above the current max. */
private const val INITIAL_MAX_SPEED_KMH = 180f

data class DashboardUiState(
    val gnss: GnssSnapshot = GnssSnapshot(),
    val compass: CompassReading = CompassReading(),
    val sun: SunInfo? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val hasPermission: Boolean = false,
    /** Current ceiling of the speed dial. Adapts upward only — never
     *  shrinks back down, so the dial doesn't flicker if the user briefly
     *  slows after pushing past the old max. */
    val maxSpeedKmh: Float = INITIAL_MAX_SPEED_KMH,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val locationRepo = LocationRepository(app)
    private val sensorRepo = SensorRepository(app)

    private val _permission = MutableStateFlow(locationRepo.hasFineLocationPermission())
    val hasPermission: StateFlow<Boolean> = _permission.asStateFlow()

    fun onPermissionGranted() {
        _permission.value = locationRepo.hasFineLocationPermission()
    }

    private val gnssFlow = locationRepo.snapshots()
    private var lastLocation: Location? = null
    private val compassFlow = sensorRepo.readings { lastLocation }

    private val tick = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(500)
        }
    }

    /** Held outside [DashboardUiState] so we can read-modify-write inside
     *  the [combine] block without re-deriving it every recomposition. */
    private var maxSpeedKmh: Float = INITIAL_MAX_SPEED_KMH

    val state: StateFlow<DashboardUiState> = combine(
        gnssFlow, compassFlow, tick, _permission
    ) { gnss, compass, now, perm ->
        lastLocation = gnss.location
        val loc = gnss.location

        // Adapt the dial ceiling. When the measured speed exceeds the
        // current max, bump the max to measuredSpeed × 1.3, rounded up to
        // the next 10 km/h. The ceiling never shrinks, so the dial stays
        // stable once it has expanded.
        val kmh = loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f) ?: 0f
        if (kmh > maxSpeedKmh) {
            maxSpeedKmh = (ceil(kmh.toDouble() * 1.3 / 10.0) * 10.0).toFloat()
        }

        val sun = loc?.let { SunPositionCalculator.compute(now, it.latitude, it.longitude) }
        DashboardUiState(
            gnss = gnss,
            compass = compass,
            sun = sun,
            nowMillis = now,
            hasPermission = perm,
            maxSpeedKmh = maxSpeedKmh,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                DashboardViewModel(app)
            }
        }
    }
}
