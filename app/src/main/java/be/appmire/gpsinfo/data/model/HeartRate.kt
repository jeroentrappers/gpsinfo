package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * Live state of the BLE heart-rate subsystem. Surfaced as a single
 * StateFlow from [be.appmire.gpsinfo.data.HeartRateRepository] so the
 * UI can render a coherent connection / measurement / disconnected
 * sequence without juggling several flags.
 */
sealed interface HeartRateState {

    /** No device paired — feature is dormant. */
    @Immutable data object Idle : HeartRateState

    /** Scan in progress (user is choosing a device). */
    @Immutable data object Scanning : HeartRateState

    /** Paired device is being connected to (initial connect or reconnect). */
    @Immutable data class Connecting(val deviceMac: String) : HeartRateState

    /**
     * Active connection. [lastBpm] is the most recent HR sample (null
     * before the first one arrives). [lastSampleAt] is the wall-clock
     * timestamp of the last sample — UI uses it to dim the readout when
     * samples stop arriving (loose strap, sweat etc.).
     */
    @Immutable data class Connected(
        val deviceMac: String,
        val deviceName: String?,
        val lastBpm: Int?,
        val lastSampleAt: Long,
        /** R-R intervals carried in the most recent measurement, in
         *  milliseconds. Many chest belts emit these alongside BPM;
         *  consumers compute HRV (RMSSD / SDNN) from a rolling window
         *  of them. Empty when the device doesn't report RRs. */
        val lastRrIntervalsMs: List<Int> = emptyList(),
    ) : HeartRateState

    /**
     * Paired but currently not connected (out of range, device off).
     * The repository will keep trying to reconnect in the background.
     */
    @Immutable data class Disconnected(
        val deviceMac: String,
        val deviceName: String?,
    ) : HeartRateState
}

/** A single HR measurement reading. Surfaces the BPM plus optional
 *  RR-interval samples when the device reports them (we don't use RRs
 *  yet, but parsing them now keeps the door open for HRV). */
@Immutable
data class HeartRateReading(
    val bpm: Int,
    val rrIntervalsMs: List<Int> = emptyList(),
    val sensorContact: SensorContact = SensorContact.NotSupported,
) {
    enum class SensorContact { NotSupported, NotInContact, InContact }
}
