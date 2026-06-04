package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * Per-device status of one paired BLE wheel-speed sensor (Bluetooth
 * SIG Cycling Speed and Cadence service, `0x1816`).
 *
 * In GPSinfo this isn't a bike accessory: CSC speed sensors strapped
 * to *car* wheel hubs are wireless wheel probes — the same physical
 * measurement a rally tripmeter (Halda/Brantz/Blunik) takes from its
 * magnetic sensors. The rally computer averages every fresh probe:
 * two probes on one axle measure the vehicle-centreline distance
 * (cornering asymmetry cancels), which is why the repository supports
 * any number of simultaneous connections.
 */
@Immutable
data class WheelDeviceStatus(
    val mac: String,
    val name: String?,
    /** GATT link currently established. */
    val connected: Boolean,
    /** The sensor's monotonically increasing wheel-revolution counter
     *  (uint32 on the wire; null before the first sample). */
    val lastCumulativeRevs: Long?,
    /** Wall-clock time of the last sample; 0 = never heard. Consumers
     *  treat stale data as "probe lost" and drop it from the average. */
    val lastSampleAt: Long,
)

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
