package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * Live state of the BLE Cycling Power subsystem. Mirrors
 * [HeartRateState]'s shape so the pairing flow can share UI code.
 *
 * Cycling Power Service is Bluetooth SIG UUID `0x1818`; the
 * mandatory characteristic is `0x2A63` (Cycling Power Measurement)
 * which contains instantaneous power in watts plus a handful of
 * optional fields (crank / wheel revolutions, balance, accumulated
 * energy). We surface only the instantaneous watts here — most
 * cyclists' primary metric — and leave the optional fields for a
 * future revision.
 */
sealed interface CyclingPowerState {

    @Immutable data object Idle : CyclingPowerState
    @Immutable data object Scanning : CyclingPowerState
    @Immutable data class Connecting(val deviceMac: String) : CyclingPowerState

    /**
     * Active connection. [lastWatts] is the most recent
     * instantaneous-power sample (null before the first one arrives).
     * [lastSampleAt] is the wall-clock timestamp of that sample — the
     * UI dims the readout when samples stop arriving.
     */
    @Immutable data class Connected(
        val deviceMac: String,
        val deviceName: String?,
        val lastWatts: Int?,
        val lastSampleAt: Long,
    ) : CyclingPowerState

    @Immutable data class Disconnected(
        val deviceMac: String,
        val deviceName: String?,
    ) : CyclingPowerState
}

/** A single Cycling Power Measurement reading. */
@Immutable
data class CyclingPowerReading(
    /** Instantaneous power in watts (signed 16-bit; negative values
     *  are theoretically permitted by the spec but in practice every
     *  meter clamps to ≥ 0). */
    val watts: Int,
)
