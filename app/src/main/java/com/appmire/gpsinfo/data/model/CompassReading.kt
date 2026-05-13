package com.appmire.gpsinfo.data.model

enum class MagneticAccuracy(val label: String) {
    UNRELIABLE("Unreliable"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    UNKNOWN("Unknown")
}

data class CompassReading(
    /** Wrapped magnetic heading in [0°, 360°). Use for text readouts and cardinal lookup. */
    val magneticHeadingDeg: Float = 0f,
    /** Cumulative magnetic heading in degrees, NOT wrapped. Each spin past
     *  north monotonically increases (or decreases) this value. Use it to
     *  drive rotation animations so they never reverse direction at the
     *  0°/360° boundary when the input wobbles across it. */
    val continuousMagneticHeadingDeg: Float = 0f,
    val trueHeadingDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val declinationDeg: Float = 0f,
    val inclinationDeg: Float = 0f,
    val fieldStrengthNanoTesla: Float = 0f,
    val accuracy: MagneticAccuracy = MagneticAccuracy.UNKNOWN
) {
    val reciprocalHeadingDeg: Float
        get() = ((magneticHeadingDeg + 180f) % 360f + 360f) % 360f
}
