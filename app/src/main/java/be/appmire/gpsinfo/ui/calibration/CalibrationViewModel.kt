package be.appmire.gpsinfo.ui.calibration

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import be.appmire.gpsinfo.data.SensorDataSource
import be.appmire.gpsinfo.data.SensorRepository
import be.appmire.gpsinfo.data.TestDataSourceOverride
import be.appmire.gpsinfo.data.calibration.CalibrationEstimator
import be.appmire.gpsinfo.data.calibration.CalibrationState
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.MagnetometerSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the in-memory sample buffer that drives the calibration screen.
 * Deliberately NOT a method on [be.appmire.gpsinfo.ui.viewmodel.DashboardViewModel] —
 * the dashboard VM is already three roles deep, and the calibration
 * workflow is a discrete, leaf-level screen with no overlap.
 *
 * The buffer is bounded so a user who leaves the screen open indefinitely
 * doesn't grow an unbounded ArrayList. [BUFFER_CAPACITY] of 600 samples
 * at SENSOR_DELAY_UI (~16 Hz) is about 37 s of history — enough that the
 * centroid estimate has converged on a real device but short enough that
 * the [estimate] call stays under a millisecond.
 */
class CalibrationViewModel(
    app: Application,
    private val sensorRepo: SensorDataSource,
) : AndroidViewModel(app) {

    private val buffer = ArrayDeque<MagnetometerSample>(BUFFER_CAPACITY)

    private val _state = MutableStateFlow(CalibrationUiState.initial())
    val state: StateFlow<CalibrationUiState> = _state.asStateFlow()

    init {
        // Collect raw samples for as long as the VM is alive. The flow
        // returned by [SensorDataSource.magnetometerStream] handles its
        // own listener registration / unregistration via awaitClose, so
        // cancellation when the VM is cleared is automatic.
        viewModelScope.launch {
            sensorRepo.magnetometerStream().collect { sample ->
                pushAndRecompute(sample)
            }
        }
    }

    /** Discard the buffered samples and reset the UI to the initial state.
     *  The next sample to arrive will be the new "first" — used when the
     *  user moves to a less magnetically polluted spot. */
    fun reset() {
        buffer.clear()
        _state.value = CalibrationUiState.initial()
    }

    private fun pushAndRecompute(sample: MagnetometerSample) {
        if (buffer.size >= BUFFER_CAPACITY) buffer.removeFirst()
        buffer.addLast(sample)
        val snapshot = buffer.toList()
        val state = CalibrationEstimator.estimate(snapshot)
        _state.value = CalibrationUiState(
            calibration = state,
            recentSamples = snapshot.takeLast(PLOT_SAMPLE_COUNT),
            latestAccuracy = sample.accuracy,
        )
    }

    companion object {
        /** Number of samples kept in the rolling buffer used for
         *  estimation. ~37 s of history at SENSOR_DELAY_UI. */
        const val BUFFER_CAPACITY = 600

        /** Number of samples handed to the UI for scatter-plot rendering.
         *  Capped well below buffer capacity — drawing more dots doesn't
         *  add information past visual saturation. */
        const val PLOT_SAMPLE_COUNT = 200

        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CalibrationViewModel(
                    app = application,
                    // Same TestDataSourceOverride hook the dashboard VM uses.
                    // Production gets the real repository; previews/tests
                    // can plug in canned data without modifying this class.
                    sensorRepo = TestDataSourceOverride.sensor
                        ?: SensorRepository(application),
                )
            }
        }
    }
}

@Immutable
data class CalibrationUiState(
    val calibration: CalibrationState,
    val recentSamples: List<MagnetometerSample>,
    val latestAccuracy: MagneticAccuracy,
) {
    companion object {
        fun initial(): CalibrationUiState = CalibrationUiState(
            calibration = CalibrationState(
                sampleCount = 0,
                hardIronOffset = be.appmire.gpsinfo.data.calibration.Vec3.ZERO,
                sphereRadiusUt = 0f,
                rmsErrorUt = 0f,
                coveredBins = 0,
                totalBins = CalibrationEstimator.TOTAL_BINS,
                fieldMagnitudeUt = 0f,
            ),
            recentSamples = emptyList(),
            latestAccuracy = MagneticAccuracy.UNKNOWN,
        )
    }
}
