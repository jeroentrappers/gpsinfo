package be.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

/**
 * One accelerometer reading mapped to vehicle/device G forces, gravity
 * removed (TYPE_LINEAR_ACCELERATION). Units are multiples of standard
 * gravity (g = 9.80665 m/s²).
 *
 * Axes are the device frame with the phone held/cradled upright:
 *   - [lateralG]      device X — cornering (left −, right +)
 *   - [longitudinalG] device Y — braking/acceleration (up the screen +)
 *   - [verticalG]     device Z — out of the screen (bumps)
 *
 * The G-meter card plots ([lateralG], [longitudinalG]) on the circle
 * and shows [horizontalMagnitudeG] as the headline value.
 */
@Immutable
data class GForceSample(
    val lateralG: Float,
    val longitudinalG: Float,
    val verticalG: Float,
) {
    /** Combined in-plane G — the number drivers watch in a corner. */
    val horizontalMagnitudeG: Float
        get() = kotlin.math.hypot(lateralG, longitudinalG)
}
