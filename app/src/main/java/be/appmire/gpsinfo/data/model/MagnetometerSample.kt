package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * One raw reading from `Sensor.TYPE_MAGNETIC_FIELD`. Values are in
 * micro-Tesla in the device's body frame (X right, Y up, Z out of screen).
 *
 * Earth's magnetic field is ~25–65 µT at the surface. A magnetometer
 * reading magnitudes well outside that range usually means the sensor
 * is being interfered with by a nearby magnet (phone case clip, laptop
 * speaker) and the user needs to move away from it before calibrating.
 *
 * @param timeNanos `SystemClock.elapsedRealtimeNanos()` at sample time
 */
@Immutable
data class MagnetometerSample(
    val xMicroTesla: Float,
    val yMicroTesla: Float,
    val zMicroTesla: Float,
    val timeNanos: Long,
    val accuracy: MagneticAccuracy,
)
