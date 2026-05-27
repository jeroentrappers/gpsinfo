package be.appmire.gpsinfo.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.UnitSystem

private const val KMH_TO_MPH = 0.621371f
private const val KMH_TO_KN = 0.539957f
private const val M_TO_FT = 3.28084f

/**
 * Storage is always SI (km/h, meters). These helpers convert at the
 * screen boundary so the persistence layer never has to care about the
 * user's display preference.
 */
object UnitConverter {
    fun speedFromKmh(kmh: Float, to: UnitSystem): Float = when (to) {
        UnitSystem.Metric -> kmh
        UnitSystem.Imperial -> kmh * KMH_TO_MPH
        UnitSystem.Nautical -> kmh * KMH_TO_KN
    }

    fun lengthFromMeters(m: Float, to: UnitSystem): Float = when (to) {
        UnitSystem.Metric -> m
        UnitSystem.Imperial, UnitSystem.Nautical -> m * M_TO_FT
    }

    fun lengthFromMeters(m: Double, to: UnitSystem): Double = when (to) {
        UnitSystem.Metric -> m
        UnitSystem.Imperial, UnitSystem.Nautical -> m * M_TO_FT.toDouble()
    }
}

@Composable
fun speedUnitLabel(unit: UnitSystem): String = stringResource(
    when (unit) {
        UnitSystem.Metric -> R.string.unit_kmh
        UnitSystem.Imperial -> R.string.unit_mph
        UnitSystem.Nautical -> R.string.unit_knots
    }
)

@Composable
fun lengthUnitLabel(unit: UnitSystem): String = stringResource(
    when (unit) {
        UnitSystem.Metric -> R.string.unit_m
        UnitSystem.Imperial, UnitSystem.Nautical -> R.string.unit_ft
    }
)

/**
 * Pace in minutes per chosen distance unit, derived from speed.
 *
 * Pace is the running/walking convention — inverse of speed. We return
 * `null` when the speed is too low to produce a useful number (stationary,
 * or stationary-with-jitter slower than ~0.5 km/h ≈ 120 min/km). At very
 * low speeds the displayed pace would swing wildly across recompositions
 * and the user just wants a "—".
 */
fun paceSecondsPerUnit(speedKmh: Float?, unit: UnitSystem): Float? {
    if (speedKmh == null || speedKmh < MIN_PACE_SPEED_KMH) return null
    val speedInUnit = UnitConverter.speedFromKmh(speedKmh, unit)  // km/h, mph, kn
    if (speedInUnit < 0.001f) return null
    // 60 min per hour ÷ speed → minutes per unit-of-distance. ×60 → seconds.
    return 3_600f / speedInUnit
}

/** "5:30" formatting for a pace in seconds-per-unit. Caps display at
 *  59:59 — anything slower than that isn't really "pace" any more. */
fun formatPace(secondsPerUnit: Float?): String {
    if (secondsPerUnit == null) return "—"
    val capped = secondsPerUnit.coerceAtMost(59 * 60 + 59f)
    val minutes = (capped / 60f).toInt()
    val seconds = (capped - minutes * 60).toInt().coerceIn(0, 59)
    return "%d:%02d".format(java.util.Locale.ROOT, minutes, seconds)
}

@Composable
fun paceUnitLabel(unit: UnitSystem): String = stringResource(
    when (unit) {
        UnitSystem.Metric -> R.string.unit_pace_per_km
        UnitSystem.Imperial -> R.string.unit_pace_per_mi
        UnitSystem.Nautical -> R.string.unit_pace_per_nm
    }
)

/** Below this speed, pace flips into "stopped" territory and we hide it
 *  rather than show a jittery 30:00+ readout. ~0.5 km/h ≈ slow shuffle. */
private const val MIN_PACE_SPEED_KMH = 0.5f
