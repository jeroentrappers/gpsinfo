package com.appmire.gpsinfo.data.model

import android.location.Location

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
