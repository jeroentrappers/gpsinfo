package com.appmire.gpsinfo.data

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.view.Display
import android.view.Surface
import com.appmire.gpsinfo.data.model.CompassReading
import com.appmire.gpsinfo.data.model.MagneticAccuracy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tilt-corrected magnetic heading using rotation vector + magnetometer accuracy callback.
 * Declination/inclination computed from GeomagneticField against the most-recent fix.
 *
 * Stability strategy:
 *   1. Display-rotation remap — the rotation matrix is re-expressed in the
 *      *display's* frame (via [SensorManager.remapCoordinateSystem]) so
 *      pitch/roll/heading match what the user perceives, whether the phone
 *      is held portrait, landscape, or reverse-portrait.
 *   2. Adaptive azimuth — heading is derived from whichever display-frame
 *      axis has the largest horizontal projection: top edge (+Y) when the
 *      device is flat, back of the device (−Z) when held upright. A single
 *      fixed convention is gimbal-locked at the extreme of its range; this
 *      cross-fades smoothly between the two regimes.
 *   3. Sample at SENSOR_DELAY_GAME (~50 Hz) so the EMA has enough samples.
 *   4. Circular exponential moving average on sin/cos of azimuth — avoids
 *      the 0°/360° wrap-around glitch a naive scalar EMA produces.
 *   5. EMA alpha adapts to magnetic accuracy: less smoothing when the
 *      magnetometer is well-calibrated, more when it's noisy.
 *   6. Cumulative (unwrapped) heading is tracked alongside the wrapped one
 *      so rotation animations never reverse direction crossing north.
 */
class SensorRepository(private val context: Context) {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Current display rotation read fresh each sensor sample. Cheap, and
     *  the display rotation can change without the repository being
     *  re-collected (especially with our activity's configChanges flag). */
    private fun displayRotation(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    fun readings(currentLocation: () -> Location?): Flow<CompassReading> = callbackFlow {
        val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        var lastHeading = 0f
        var lastContinuousHeading = 0f
        var lastPitch = 0f
        var lastRoll = 0f
        var lastAccuracy = MagneticAccuracy.UNKNOWN
        var fieldStrength = 0f

        fun emit() {
            val loc = currentLocation()
            val geo: GeomagneticField? = loc?.let {
                GeomagneticField(
                    it.latitude.toFloat(),
                    it.longitude.toFloat(),
                    if (it.hasAltitude()) it.altitude.toFloat() else 0f,
                    System.currentTimeMillis()
                )
            }
            val declination = geo?.declination ?: 0f
            val inclination = geo?.inclination ?: 0f
            val trueHeading = ((lastHeading + declination) % 360f + 360f) % 360f

            trySend(
                CompassReading(
                    magneticHeadingDeg = lastHeading,
                    continuousMagneticHeadingDeg = lastContinuousHeading,
                    trueHeadingDeg = trueHeading,
                    pitchDeg = lastPitch,
                    rollDeg = lastRoll,
                    declinationDeg = declination,
                    inclinationDeg = inclination,
                    fieldStrengthNanoTesla = fieldStrength,
                    accuracy = lastAccuracy
                )
            )
        }

        val rotationListener = object : SensorEventListener {
            private val rotMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            // Circular EMA state — heading is stored as filtered sin/cos so
            // the 0°/360° wrap never produces a long-way jump in the output.
            private var filtSin = 0.0
            private var filtCos = 0.0
            private var filtPitch = 0.0
            private var filtRoll = 0.0
            private var initialized = false

            // Continuous (unwrapped) heading — integrates the shortest angular
            // delta each sample so tiny wobble across 0°/360° cannot flip the
            // sign of the next animation step. Can grow unbounded over many
            // physical rotations; that is fine for rotate().
            private var prevWrappedDeg = Double.NaN
            private var continuousDeg = 0.0

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                // Re-express the rotation matrix in the *display's* frame.
                // The sensor frame is locked to the device's natural
                // orientation (portrait for most phones), so without this
                // remap "top of the phone" stops matching "top of the
                // screen" the moment the device is rotated, and pitch/roll
                // swap meanings. The mapping below is the canonical one
                // documented by Android for each display rotation.
                val (axisX, axisY) = when (displayRotation()) {
                    Surface.ROTATION_90 ->
                        SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 ->
                        SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 ->
                        SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> // ROTATION_0 (natural)
                        SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(
                    rotMatrix, axisX, axisY, remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientation)

                // Adaptive azimuth. The remapped rotation matrix R maps
                // *display-frame* device vectors into the world frame
                // (X=east, Y=north, Z=up). The horizontal projection of
                // display +Y (top of the screen) lives in (R[1], R[4]);
                // display −Z (back of the screen) lives in (−R[2], −R[5]).
                // Whichever has the larger squared magnitude is well-
                // conditioned in the current pose — pick it.
                //   * Device flat face-up → top projects strongly → +Y wins.
                //   * Device held upright → top points at the sky, its
                //     projection collapses → −Z wins.
                // The cross-fade between regimes is implicit and smooth.
                val r1 = remappedMatrix[1]; val r4 = remappedMatrix[4]
                val r2 = remappedMatrix[2]; val r5 = remappedMatrix[5]
                val topHoriz2 = r1 * r1 + r4 * r4
                val backHoriz2 = r2 * r2 + r5 * r5
                val rawAzRad = if (topHoriz2 >= backHoriz2) {
                    // East over north → bearing clockwise from north.
                    atan2(r1.toDouble(), r4.toDouble())
                } else {
                    atan2((-r2).toDouble(), (-r5).toDouble())
                }
                val rawPitchDeg = Math.toDegrees(orientation[1].toDouble())
                val rawRollDeg = Math.toDegrees(orientation[2].toDouble())

                val alpha = when (lastAccuracy) {
                    MagneticAccuracy.HIGH -> 0.28
                    MagneticAccuracy.MEDIUM -> 0.18
                    MagneticAccuracy.LOW -> 0.10
                    MagneticAccuracy.UNRELIABLE -> 0.06
                    MagneticAccuracy.UNKNOWN -> 0.18
                }

                val s = sin(rawAzRad)
                val c = cos(rawAzRad)
                if (!initialized) {
                    filtSin = s; filtCos = c
                    filtPitch = rawPitchDeg; filtRoll = rawRollDeg
                    initialized = true
                } else {
                    filtSin = (1.0 - alpha) * filtSin + alpha * s
                    filtCos = (1.0 - alpha) * filtCos + alpha * c
                    filtPitch = (1.0 - alpha) * filtPitch + alpha * rawPitchDeg
                    filtRoll = (1.0 - alpha) * filtRoll + alpha * rawRollDeg
                }

                val azDeg = Math.toDegrees(atan2(filtSin, filtCos))
                val wrappedDeg = ((azDeg % 360.0) + 360.0) % 360.0

                // Accumulate the shortest angular delta into the continuous
                // heading. First sample seeds it; thereafter each delta is
                // clamped to (-180, 180] so wrap-around never reverses the
                // animation direction.
                if (prevWrappedDeg.isNaN()) {
                    continuousDeg = wrappedDeg
                } else {
                    var delta = wrappedDeg - prevWrappedDeg
                    if (delta > 180.0) delta -= 360.0
                    if (delta < -180.0) delta += 360.0
                    continuousDeg += delta
                }
                prevWrappedDeg = wrappedDeg

                lastHeading = wrappedDeg.toFloat()
                lastContinuousHeading = continuousDeg.toFloat()
                lastPitch = filtPitch.toFloat()
                lastRoll = filtRoll.toFloat()

                emit()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    lastAccuracy = mapAccuracy(accuracy)
                    emit()
                }
            }
        }

        val magneticListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                fieldStrength = sqrt(x * x + y * y + z * z)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    lastAccuracy = mapAccuracy(accuracy)
                    emit()
                }
            }
        }

        if (rotationVector != null) {
            sm.registerListener(rotationListener, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetic != null) {
            sm.registerListener(magneticListener, magnetic, SensorManager.SENSOR_DELAY_GAME)
        }

        emit()
        awaitClose {
            sm.unregisterListener(rotationListener)
            sm.unregisterListener(magneticListener)
        }
    }

    private fun mapAccuracy(level: Int): MagneticAccuracy = when (level) {
        SensorManager.SENSOR_STATUS_UNRELIABLE -> MagneticAccuracy.UNRELIABLE
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> MagneticAccuracy.LOW
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MagneticAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> MagneticAccuracy.HIGH
        else -> MagneticAccuracy.UNKNOWN
    }
}
