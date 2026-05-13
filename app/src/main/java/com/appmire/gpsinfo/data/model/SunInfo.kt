package com.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class SunInfo(
    val sunAzimuthDeg: Double,
    val sunElevationDeg: Double,
    val subsolarLatDeg: Double,
    val subsolarLonDeg: Double,
    val sunriseEpochMillis: Long?,
    val sunsetEpochMillis: Long?,
    val solarNoonEpochMillis: Long?,
    val dayLengthMillis: Long?,
    val isDaytime: Boolean
)
