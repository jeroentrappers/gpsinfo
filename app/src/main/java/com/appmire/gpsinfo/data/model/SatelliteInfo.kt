package com.appmire.gpsinfo.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class Constellation(val label: String, val color: Long) {
    GPS("GPS", 0xFF4FC3F7),
    GLONASS("GLONASS", 0xFFFFB74D),
    GALILEO("GALILEO", 0xFFAED581),
    BEIDOU("BEIDOU", 0xFFF06292),
    QZSS("QZSS", 0xFFBA68C8),
    SBAS("SBAS", 0xFF90A4AE),
    IRNSS("IRNSS", 0xFFFFD54F),
    UNKNOWN("???", 0xFF9E9E9E)
}

@Immutable
data class SatelliteInfo(
    val svid: Int,
    val constellation: Constellation,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean,
    val hasAlmanac: Boolean,
    /** Carrier frequency in Hz, or 0 when the chip does not expose it. Used to
     *  distinguish multi-band signals from the same PRN (e.g. GPS L1 vs L5). */
    val carrierFrequencyHz: Float = 0f,
) {
    val label: String get() = "${constellation.label[0]}$svid"
}
