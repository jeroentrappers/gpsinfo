package com.appmire.gpsinfo.data.sun

import com.appmire.gpsinfo.data.model.SunInfo
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Simplified NOAA Solar Position Algorithm.
 * Accurate to ~1 minute for sunrise/sunset, ~0.1 degree for solar position.
 * All trig in radians internally; inputs/outputs in degrees / epoch millis.
 */
object SunPositionCalculator {

    fun compute(epochMillis: Long, latDeg: Double, lonDeg: Double): SunInfo {
        val jd = julianDay(epochMillis)
        val (azimuth, elevation) = solarPosition(jd, latDeg, lonDeg)

        // subsolar point (latitude = declination, longitude = where sun is overhead now)
        val (decl, eqOfTimeMin) = solarDeclinationAndEqOfTime(jd)
        val utcHours = ((epochMillis % 86_400_000L).toDouble() / 3_600_000.0)
        val subsolarLon = -15.0 * (utcHours - 12.0 + eqOfTimeMin / 60.0)
        val subsolarLat = decl

        val (sunrise, sunset, noon, dayLen) = riseSetNoon(epochMillis, latDeg, lonDeg)
        val isDay = elevation > 0.0

        return SunInfo(
            sunAzimuthDeg = azimuth,
            sunElevationDeg = elevation,
            subsolarLatDeg = subsolarLat,
            subsolarLonDeg = normalizeLon(subsolarLon),
            sunriseEpochMillis = sunrise,
            sunsetEpochMillis = sunset,
            solarNoonEpochMillis = noon,
            dayLengthMillis = dayLen,
            isDaytime = isDay
        )
    }

    private fun julianDay(epochMillis: Long): Double =
        epochMillis / 86_400_000.0 + 2_440_587.5

    /** Returns (declination °, equation-of-time minutes). */
    private fun solarDeclinationAndEqOfTime(jd: Double): Pair<Double, Double> {
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod360()
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mRad = m.toRadians()
        val c = (sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t))
                + sin(2.0 * mRad) * (0.019993 - 0.000101 * t)
                + sin(3.0 * mRad) * 0.000289)
        val trueLon = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLon = trueLon - 0.00569 - 0.00478 * sin(omega.toRadians())
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega.toRadians())
        val decl = Math.toDegrees(asin(sin(eps.toRadians()) * sin(appLon.toRadians())))
        val y = tan((eps / 2.0).toRadians()).let { it * it }
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2.0 * l0.toRadians())
                    - 2.0 * e * sin(mRad)
                    + 4.0 * e * y * sin(mRad) * cos(2.0 * l0.toRadians())
                    - 0.5 * y * y * sin(4.0 * l0.toRadians())
                    - 1.25 * e * e * sin(2.0 * mRad)
        )
        return decl to eqTime
    }

    /** Returns (azimuth °, elevation °). Azimuth measured east-of-north. */
    private fun solarPosition(jd: Double, latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val (decl, eqTimeMin) = solarDeclinationAndEqOfTime(jd)
        val timeOffsetMin = eqTimeMin + 4.0 * lonDeg
        val utcMinutes = ((jd + 0.5) - floor(jd + 0.5)) * 1440.0
        val trueSolarTime = (utcMinutes + timeOffsetMin).mod1440()
        val hourAngle = trueSolarTime / 4.0 - 180.0

        val latRad = latDeg.toRadians()
        val declRad = decl.toRadians()
        val haRad = hourAngle.toRadians()

        val zenith = acos(sin(latRad) * sin(declRad) + cos(latRad) * cos(declRad) * cos(haRad))
        val elevation = 90.0 - Math.toDegrees(zenith)
        val azDenom = cos(latRad) * sin(zenith)
        val azimuth = if (abs(azDenom) > 1e-9) {
            val arg = ((sin(latRad) * cos(zenith)) - sin(declRad)) / azDenom
            val a = Math.toDegrees(acos(arg.coerceIn(-1.0, 1.0)))
            if (hourAngle > 0.0) (a + 180.0).mod360() else (540.0 - a).mod360()
        } else if (latDeg > 0.0) 180.0 else 0.0

        return azimuth to elevation
    }

    private data class RiseSetNoon(
        val sunrise: Long?, val sunset: Long?, val noon: Long?, val dayLen: Long?
    )

    private fun riseSetNoon(epochMillis: Long, latDeg: Double, lonDeg: Double): RiseSetNoon {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMillis }
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val midnightUtc = cal.timeInMillis
        val jdNoon = julianDay(midnightUtc + 43_200_000L)
        val (decl, eqTime) = solarDeclinationAndEqOfTime(jdNoon)

        val latRad = latDeg.toRadians()
        val declRad = decl.toRadians()
        val cosH = (cos(90.833.toRadians()) - sin(latRad) * sin(declRad)) /
                (cos(latRad) * cos(declRad))

        val noonMin = 720.0 - 4.0 * lonDeg - eqTime
        val noonMillis = midnightUtc + (noonMin * 60_000.0).toLong()

        if (cosH > 1.0 || cosH < -1.0) {
            // polar day or polar night — no rise/set
            return RiseSetNoon(null, null, noonMillis, null)
        }
        val hourAngle = Math.toDegrees(acos(cosH))
        val sunriseMin = noonMin - hourAngle * 4.0
        val sunsetMin = noonMin + hourAngle * 4.0
        val sunrise = midnightUtc + (sunriseMin * 60_000.0).toLong()
        val sunset = midnightUtc + (sunsetMin * 60_000.0).toLong()
        return RiseSetNoon(sunrise, sunset, noonMillis, sunset - sunrise)
    }

    private fun Double.mod360(): Double = ((this % 360.0) + 360.0) % 360.0
    private fun Double.mod1440(): Double = ((this % 1440.0) + 1440.0) % 1440.0
    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun normalizeLon(d: Double): Double {
        var x = d
        while (x > 180.0) x -= 360.0
        while (x < -180.0) x += 360.0
        return x
    }
}
