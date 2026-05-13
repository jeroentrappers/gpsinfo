package com.appmire.gpsinfo.util

private val winds16 = arrayOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
)

fun headingToCardinal(deg: Float): String {
    val normalized = ((deg % 360f) + 360f) % 360f
    val idx = ((normalized / 22.5f) + 0.5f).toInt() % 16
    return winds16[idx]
}
