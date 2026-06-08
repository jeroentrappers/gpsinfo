package be.appmire.gpsinfo.data

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import be.appmire.gpsinfo.data.model.CompassReading
import be.appmire.gpsinfo.data.model.MagneticAccuracy
import be.appmire.gpsinfo.data.model.MagnetometerSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tilt-corrected magnetic heading from [Sensor.TYPE_ROTATION_VECTOR],
 * plus [GeomagneticField]-derived declination/inclination relative to the
 * most-recent fix.
 *
 * Stability strategy (in this order):
 *   1. **Display-rotation remap** — rotation matrix re-expressed in the
 *      display's frame via [SensorManager.remapCoordinateSystem] so
 *      pitch/roll/heading match what the user perceives in any orientation.
 *   2. **Adaptive azimuth** — heading is derived from whichever display-
 *      frame axis has the largest horizontal projection: top edge (+Y)
 *      when the device is flat, back of the device (−Z) when held upright.
 *   3. **SENSOR_DELAY_GAME** sampling (~50 Hz) so the EMA has samples.
 *   4. **Circular EMA on sin/cos** — avoids the 0°/360° wrap-around glitch
 *      a naive scalar EMA produces.
 *   5. **Accuracy-adaptive alpha** — less smoothing when the magnetometer
 *      is well-calibrated, more when it's noisy.
 *   6. **Continuous (unwrapped) heading** alongside the wrapped value so
 *      rotation animations never reverse direction crossing north.
 *
 * Caching:
 *   - [GeomagneticField] is rebuilt only when location moves by more than
 *     [GEO_RECOMPUTE_METERS] — it doesn't change measurably below that.
 *   - Display rotation is cached and refreshed by a [DisplayManager.DisplayListener]
 *     rather than read on every sensor tick.
 */
class SensorRepository(private val context: Context) : SensorDataSource {

    private val sm: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val displayManager: DisplayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    override fun readings(currentLocation: () -> Location?): Flow<CompassReading> = callbackFlow {
        val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val mainHandler = Handler(Looper.getMainLooper())

        // Cached display rotation — kept in sync via DisplayListener instead
        // of re-read on every sensor tick. IPC saved: ~50/sec → 0.
        var cachedRotation = displayManager
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    cachedRotation = displayManager
                        .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
                }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(displayListener, mainHandler)

        // Cached GeomagneticField — invalidated when the user moves more
        // than GEO_RECOMPUTE_METERS from the last computation point.
        var cachedGeo: GeomagneticField? = null
        var cachedGeoLat = Double.NaN
        var cachedGeoLon = Double.NaN
        var cachedGeoAlt = Double.NaN

        fun geomagneticFor(loc: Location?): GeomagneticField? {
            if (loc == null) {
                cachedGeo = null
                cachedGeoLat = Double.NaN
                cachedGeoLon = Double.NaN
                cachedGeoAlt = Double.NaN
                return null
            }
            val alt = if (loc.hasAltitude()) loc.altitude else 0.0
            val moved = cachedGeoLat.isNaN() ||
                metresBetween(cachedGeoLat, cachedGeoLon, loc.latitude, loc.longitude) >
                    GEO_RECOMPUTE_METERS ||
                abs(alt - cachedGeoAlt) > GEO_RECOMPUTE_ALTITUDE_METERS
            if (moved) {
                cachedGeo = GeomagneticField(
                    loc.latitude.toFloat(),
                    loc.longitude.toFloat(),
                    alt.toFloat(),
                    System.currentTimeMillis()
                )
                cachedGeoLat = loc.latitude
                cachedGeoLon = loc.longitude
                cachedGeoAlt = alt
            }
            return cachedGeo
        }

        var lastHeading = 0f
        var lastContinuousHeading = 0f
        var lastPitch = 0f
        var lastRoll = 0f
        // Rotation-vector accuracy = the meaningful one for compass UI.
        // We keep magnetic-field sensor accuracy separately so they don't
        // overwrite each other.
        var rotationAccuracy = MagneticAccuracy.UNKNOWN
        var magneticAccuracy = MagneticAccuracy.UNKNOWN
        var fieldStrength = 0f

        fun emit() {
            val loc = currentLocation()
            val geo = geomagneticFor(loc)
            val declination = geo?.declination ?: 0f
            val inclination = geo?.inclination ?: 0f
            val trueHeading = ((lastHeading + declination) % 360f + 360f) % 360f
            // The rotation-vector accuracy is the one driving the heading
            // smoothing alpha, and it's the more useful number for the UI.
            // Worst of the two when both are known so we don't claim "High"
            // confidence when the bare magnetometer is unreliable.
            val combinedAccuracy = worstOf(rotationAccuracy, magneticAccuracy)
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
                    accuracy = combinedAccuracy,
                )
            )
        }

        val rotationListener = object : SensorEventListener {
            private val rotMatrix = FloatArray(9)
            private val remappedMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            // Circular EMA state — stored as filtered sin/cos so the 0°/360°
            // wrap never produces a long-way jump in the output.
            private var filtSin = 0.0
            private var filtCos = 0.0
            private var filtPitch = 0.0
            private var filtRoll = 0.0
            private var initialized = false

            // Continuous (unwrapped) heading — integrates the shortest
            // angular delta each sample so tiny wobble across 0°/360°
            // cannot flip the sign of the next animation step.
            private var prevWrappedDeg = Double.NaN
            private var continuousDeg = 0.0

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                val (axisX, axisY) = when (cachedRotation) {
                    Surface.ROTATION_90 ->
                        SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 ->
                        SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 ->
                        SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> // ROTATION_0
                        SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(
                    rotMatrix, axisX, axisY, remappedMatrix
                )
                SensorManager.getOrientation(remappedMatrix, orientation)

                val r1 = remappedMatrix[1]; val r4 = remappedMatrix[4]
                val r2 = remappedMatrix[2]; val r5 = remappedMatrix[5]
                val topHoriz2 = r1 * r1 + r4 * r4
                val backHoriz2 = r2 * r2 + r5 * r5
                val rawAzRad = if (topHoriz2 >= backHoriz2) {
                    atan2(r1.toDouble(), r4.toDouble())
                } else {
                    atan2((-r2).toDouble(), (-r5).toDouble())
                }
                val rawPitchDeg = Math.toDegrees(orientation[1].toDouble())
                val rawRollDeg = Math.toDegrees(orientation[2].toDouble())

                val alpha = when (rotationAccuracy) {
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
                val (newContinuous, newPrev) =
                    integrateContinuousHeading(prevWrappedDeg, wrappedDeg, continuousDeg)
                continuousDeg = newContinuous
                prevWrappedDeg = newPrev

                lastHeading = wrappedDeg.toFloat()
                lastContinuousHeading = continuousDeg.toFloat()
                lastPitch = filtPitch.toFloat()
                lastRoll = filtRoll.toFloat()

                emit()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    rotationAccuracy = mapAccuracy(accuracy)
                    emit()
                }
            }
        }

        val magneticListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val newStrength = sqrt(x * x + y * y + z * z)
                // Emit only when it changes meaningfully — the bare
                // magnetometer ticks at ~50 Hz too and we don't need to
                // multiply that by the rotation-vector rate.
                if (abs(newStrength - fieldStrength) > 0.5f) {
                    fieldStrength = newStrength
                    emit()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    magneticAccuracy = mapAccuracy(accuracy)
                    emit()
                }
            }
        }

        if (rotationVector != null) {
            sm.registerListener(rotationListener, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetic != null) {
            sm.registerListener(magneticListener, magnetic, SensorManager.SENSOR_DELAY_UI)
        }

        emit()
        awaitClose {
            sm.unregisterListener(rotationListener)
            sm.unregisterListener(magneticListener)
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    /**
     * Hardware step counter (Sensor.TYPE_STEP_COUNTER). Emits the device's
     * cumulative step count *since boot*, monotonically increasing —
     * callers compute deltas from a baseline they capture at the moment
     * they care about (e.g. recording start). Returns immediately
     * without emissions and closes when the sensor is missing on the
     * device, so the caller can render "no step data" without timing out.
     *
     * The runtime permission (ACTIVITY_RECOGNITION, API 29+) is the
     * caller's responsibility. If it isn't granted the OS silently
     * withholds events; this flow just sits idle in that case.
     */
    fun stepCounterStream(): Flow<Long> = callbackFlow {
        val stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
                // Sensor delivers a float for historical reasons; the
                // value is integral and we cast to Long for clarity.
                trySend(event.values[0].toLong())
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // SENSOR_DELAY_NORMAL (~5 Hz) is overkill — the step counter
        // itself ticks once per step (1-3 Hz when running). The OS
        // throttles to that internally either way.
        sm.registerListener(listener, stepCounter, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sm.unregisterListener(listener) }
    }

    override fun magnetometerStream(): Flow<MagnetometerSample> = callbackFlow {
        val magnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetic == null) {
            // No magnetometer on this device — close the flow immediately
            // so the calibration UI can render "unsupported" instead of
            // waiting for samples that will never arrive.
            close()
            return@callbackFlow
        }
        var currentAccuracy = MagneticAccuracy.UNKNOWN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
                trySend(
                    MagnetometerSample(
                        xMicroTesla = event.values[0],
                        yMicroTesla = event.values[1],
                        zMicroTesla = event.values[2],
                        // Sensor `event.timestamp` is in the elapsed-realtime
                        // domain on Android — match it here so the buffer
                        // can sort/de-dupe by sample time later.
                        timeNanos = event.timestamp,
                        accuracy = currentAccuracy,
                    ),
                )
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    currentAccuracy = mapAccuracy(accuracy)
                }
            }
        }
        // SENSOR_DELAY_UI (~16 Hz) is enough for the calibration scatter
        // plot — going faster would flood the channel without giving the
        // user a visual difference at 60 fps.
        sm.registerListener(listener, magnetic, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    override fun gForceStream(): Flow<be.appmire.gpsinfo.data.model.GForceSample> = callbackFlow {
        // Prefer the fused linear-acceleration sensor (gravity already
        // removed). Fall back to the raw accelerometer with a simple
        // high-pass gravity filter on devices that lack the virtual one.
        val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val raw = if (linear == null) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val sensor = linear ?: raw
        if (sensor == null) {
            trySend(be.appmire.gpsinfo.data.model.GForceSample(0f, 0f, 0f))
            awaitClose { }
            return@callbackFlow
        }
        val gravity = FloatArray(3) // running gravity estimate (fallback path)
        // Light EMA so the dot glides instead of jittering on engine buzz.
        val smoothed = FloatArray(3)
        var seeded = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val ax: Float
                val ay: Float
                val az: Float
                if (raw != null) {
                    // High-pass: isolate gravity, subtract it.
                    val a = 0.8f
                    for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * event.values[i]
                    ax = event.values[0] - gravity[0]
                    ay = event.values[1] - gravity[1]
                    az = event.values[2] - gravity[2]
                } else {
                    ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                }
                val raw3 = floatArrayOf(ax, ay, az)
                if (!seeded) { System.arraycopy(raw3, 0, smoothed, 0, 3); seeded = true }
                val s = SMOOTHING
                for (i in 0..2) smoothed[i] = smoothed[i] + s * (raw3[i] - smoothed[i])
                trySend(
                    be.appmire.gpsinfo.data.model.GForceSample(
                        lateralG = smoothed[0] / G,
                        longitudinalG = smoothed[1] / G,
                        verticalG = smoothed[2] / G,
                    )
                )
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    private fun mapAccuracy(level: Int): MagneticAccuracy = when (level) {
        SensorManager.SENSOR_STATUS_UNRELIABLE -> MagneticAccuracy.UNRELIABLE
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> MagneticAccuracy.LOW
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MagneticAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> MagneticAccuracy.HIGH
        else -> MagneticAccuracy.UNKNOWN
    }

    private fun worstOf(a: MagneticAccuracy, b: MagneticAccuracy): MagneticAccuracy {
        if (a == MagneticAccuracy.UNKNOWN) return b
        if (b == MagneticAccuracy.UNKNOWN) return a
        return if (a.ordinal < b.ordinal) a else b
    }

    private companion object {
        /** Lat/lon move tolerance before we recompute the magnetic field
         *  (declination changes by ~0.1° per 100 km mid-latitude, so 100 m
         *  is well below any perceptible change). */
        const val GEO_RECOMPUTE_METERS = 100.0
        const val GEO_RECOMPUTE_ALTITUDE_METERS = 200.0
        /** Standard gravity — m/s² per 1 G. */
        const val G = 9.80665f
        /** EMA factor for the G readout (0..1, higher = snappier). */
        const val SMOOTHING = 0.25f
    }
}

/**
 * Integrates a new wrapped-heading sample into the running continuous
 * (unwrapped) heading. Returns `(newContinuous, newPrev)`.
 *
 * The delta from `prevWrapped` to `newWrapped` is clamped to (-180, 180]
 * so wrap-around at 0°/360° never causes the rose to reverse direction.
 * First sample (when prevWrapped is NaN) seeds the continuous value.
 *
 * Pure function, extracted for unit testability.
 */
internal fun integrateContinuousHeading(
    prevWrapped: Double,
    newWrapped: Double,
    prevContinuous: Double,
): Pair<Double, Double> {
    if (prevWrapped.isNaN()) return newWrapped to newWrapped
    var delta = newWrapped - prevWrapped
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return (prevContinuous + delta) to newWrapped
}

/**
 * Approximate distance in metres between two lat/lon points using the
 * equirectangular projection. Good to better than 1% for sub-100 km
 * distances — plenty for "did the user move >100 m?"
 *
 * Pure function, extracted for unit testability.
 */
internal fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    if (lat1.isNaN() || lon1.isNaN()) return Double.POSITIVE_INFINITY
    val rEarth = 6_371_000.0
    val midLatRad = Math.toRadians((lat1 + lat2) / 2.0)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1) * cos(midLatRad)
    return rEarth * sqrt(dLat * dLat + dLon * dLon)
}
