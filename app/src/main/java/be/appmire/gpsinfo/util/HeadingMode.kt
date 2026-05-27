package be.appmire.gpsinfo.util

/**
 * Picks between the two heading sources we publish — the
 * magnetometer (phone-pointing direction) and GPS course-over-ground
 * (direction of travel) — based on how fast the user is moving.
 *
 * Magnetic always reads cleanly but only tells you which way the
 * **phone** is pointing (useless if it's in a pocket / cradle /
 * cupholder). GPS course is direction-of-travel ground truth, but
 * the chip's Doppler estimate is unstable below walking pace.
 *
 * Hysteresis avoids visual flapping at the boundary: enter
 * `DualWithCourse` at ≥ [ENTER_THRESHOLD_KMH], drop back to
 * `MagneticOnly` only at ≤ [EXIT_THRESHOLD_KMH]. A user wobbling
 * around 3 km/h won't see the UI toggle every second.
 *
 * State is intentionally inside a tiny class instance the VM holds
 * so the threshold's decision is **shared** across every surface
 * (CompassCard, SpeedCard, NavigationCard, LiveMap …). Without that,
 * each surface would re-derive the mode independently and they'd
 * disagree at the boundary, which would look broken.
 */
enum class HeadingMode {
    /** Only magnetic is meaningful (stationary / sub-walking). */
    MagneticOnly,
    /** Both magnetic and GPS course are meaningful — surfaces should
     *  show both, or pick GPS course as the primary. */
    DualWithCourse;
}

class HeadingModeTracker {
    private var current: HeadingMode = HeadingMode.MagneticOnly

    /** Update the tracker with the latest speed (km/h). Returns the
     *  new mode. Caller emits that downstream. */
    fun update(speedKmh: Float?): HeadingMode {
        val kmh = speedKmh ?: 0f
        current = when (current) {
            HeadingMode.MagneticOnly ->
                if (kmh >= ENTER_THRESHOLD_KMH) HeadingMode.DualWithCourse else current
            HeadingMode.DualWithCourse ->
                if (kmh <= EXIT_THRESHOLD_KMH) HeadingMode.MagneticOnly else current
        }
        return current
    }

    companion object {
        const val ENTER_THRESHOLD_KMH = 3.0f
        const val EXIT_THRESHOLD_KMH = 2.7f
    }
}
