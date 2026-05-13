package com.appmire.gpsinfo.data.model

import android.location.Location
import androidx.compose.runtime.Immutable

/**
 * Note: [Location] is *not* `@Immutable` (it's a Java class with mutable
 * internals). Compose can't skip recompositions purely on parameter
 * stability when a [GnssSnapshot] is passed in. We still annotate the
 * holder so Compose treats *this* class as stable and skipping kicks in
 * when callers compare snapshots by `equals` — which is what we want.
 */
@Immutable
data class GnssSnapshot(
    val location: Location? = null,
    val fix: FixStatus = FixStatus.NO_FIX,
    val satellites: List<SatelliteInfo> = emptyList(),
    val firstFixMillis: Long? = null,
    val lastUpdateElapsedRealtime: Long = 0L
) {
    val satellitesInView: Int get() = satellites.size
    val satellitesInUse: Int get() = satellites.count { it.usedInFix }
    val averageSnr: Float
        get() = satellites
            .filter { it.cn0DbHz > 0f }
            .map { it.cn0DbHz }
            .let { if (it.isEmpty()) 0f else it.average().toFloat() }
}
