package com.appmire.gpsinfo.data

import java.util.Locale

/**
 * Display unit system the user has selected. Storage is always SI
 * (km/h, m); conversion happens at the screen boundary. The default is
 * inferred from the device locale on first launch via [defaultFor].
 *
 * - [Metric]   km/h + m. Default for everyone outside the US/UK/LR/MM.
 * - [Imperial] mph + ft. Road users in the US, UK, Liberia, Myanmar.
 * - [Nautical] kn + ft. Aviation / marine — opt-in regardless of locale.
 */
enum class UnitSystem(val key: String) {
    Metric("metric"),
    Imperial("imperial"),
    Nautical("nautical");

    companion object {
        fun fromString(s: String?): UnitSystem =
            entries.firstOrNull { it.key == s } ?: defaultFor(Locale.getDefault())

        fun defaultFor(locale: Locale): UnitSystem = when (locale.country.uppercase()) {
            "US", "LR", "MM", "GB" -> Imperial
            else -> Metric
        }
    }
}
