package com.appmire.gpsinfo.util

import kotlin.math.abs
import kotlin.math.floor

enum class CoordinateFormat { DMS, DECIMAL }

data class FormattedCoord(val lat: String, val lon: String)

object CoordinateFormatter {
    fun format(latDeg: Double, lonDeg: Double, fmt: CoordinateFormat): FormattedCoord =
        when (fmt) {
            CoordinateFormat.DMS -> FormattedCoord(toDms(latDeg, true), toDms(lonDeg, false))
            CoordinateFormat.DECIMAL -> FormattedCoord(
                "%.6f°".format(latDeg),
                "%.6f°".format(lonDeg)
            )
        }

    private fun toDms(deg: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && deg >= 0 -> "N"
            isLat -> "S"
            !isLat && deg >= 0 -> "E"
            else -> "W"
        }
        val a = abs(deg)
        val d = floor(a).toInt()
        val mFull = (a - d) * 60.0
        val m = floor(mFull).toInt()
        val s = (mFull - m) * 60.0
        return "%d°%02d'%06.3f\" %s".format(d, m, s, hemi)
    }
}
