package com.appmire.gpsinfo.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.appmire.gpsinfo.R
import com.appmire.gpsinfo.data.UnitSystem

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
