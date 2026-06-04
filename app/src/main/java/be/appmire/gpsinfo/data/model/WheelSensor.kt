package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * Live state of the BLE wheel-speed-sensor subsystem (Bluetooth SIG
 * Cycling Speed and Cadence service, `0x1816`). Mirrors
 * [CyclingPowerState]'s shape so the pairing flow shares its UX.
 *
 * In GPSinfo this isn't a bike accessory: a CSC speed sensor strapped
 * to a *car* wheel hub is a wireless wheel probe — the same physical
 * measurement a rally tripmeter (Halda/Brantz/Blunik) takes from its
 * magnetic sensors, feeding the regularity computer's distance.
 */
sealed interface WheelSensorState {

    @Immutable data object Idle : WheelSensorState
    @Immutable data object Scanning : WheelSensorState
    @Immutable data class Connecting(val deviceMac: String) : WheelSensorState

    /**
     * Active connection. [lastCumulativeRevs] is the sensor's
     * monotonically increasing wheel-revolution counter (uint32 on
     * the wire; null before the first sample). [lastSampleAt] is the
     * wall-clock time of that sample — consumers treat stale data as
     * "wheel source lost" and fall back to GPS.
     */
    @Immutable data class Connected(
        val deviceMac: String,
        val deviceName: String?,
        val lastCumulativeRevs: Long?,
        val lastSampleAt: Long,
    ) : WheelSensorState

    @Immutable data class Disconnected(
        val deviceMac: String,
        val deviceName: String?,
    ) : WheelSensorState
}

/** One decoded CSC Measurement (0x2A5B) wheel-revolution sample. */
@Immutable
data class WheelReading(
    /** Cumulative wheel revolutions since the sensor's battery went in
     *  (uint32 — wraps after ~8.4M km on a car tire; handled anyway). */
    val cumulativeRevs: Long,
    /** Sensor-side timestamp of the last wheel event, in 1/1024 s
     *  units (uint16, wraps every 64 s). Unused for distance but kept
     *  for a future speed-from-wheel readout. */
    val lastEventTime1024: Int,
)
