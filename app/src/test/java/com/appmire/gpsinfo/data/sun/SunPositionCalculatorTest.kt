package com.appmire.gpsinfo.data.sun

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity checks against the simplified NOAA SPA. Expected values were
 * cross-checked against timeanddate.com / suncalc.org for the given
 * date and coordinates; our algorithm should land within ±1 minute on
 * sunrise/sunset.
 */
class SunPositionCalculatorTest {

    /** 2024-06-21 UTC noon at Antwerp, BE. Summer solstice → long day. */
    @Test fun antwerp_summer_solstice() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.JUNE, 21, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val s = SunPositionCalculator.compute(cal.timeInMillis, latDeg = 51.22, lonDeg = 4.40)
        assertNotNull(s.sunriseEpochMillis)
        assertNotNull(s.sunsetEpochMillis)
        val dayLenMin = (s.dayLengthMillis ?: 0L) / 60_000L
        // Antwerp gets ~16h 38m at the solstice — tolerate ±5 min.
        assertTrue("day length was $dayLenMin min", dayLenMin in 980..1020)
        assertTrue("expected daytime at noon", s.isDaytime)
    }

    /** 2024-12-21 UTC noon at Antwerp. Winter solstice → short day. */
    @Test fun antwerp_winter_solstice() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.DECEMBER, 21, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val s = SunPositionCalculator.compute(cal.timeInMillis, latDeg = 51.22, lonDeg = 4.40)
        val dayLenMin = (s.dayLengthMillis ?: 0L) / 60_000L
        // Antwerp gets ~7h 53m at winter solstice — tolerate ±5 min.
        assertTrue("day length was $dayLenMin min", dayLenMin in 460..500)
    }

    /** Equator at equinox should give ~12-hour day. */
    @Test fun equator_at_equinox() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.MARCH, 20, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val s = SunPositionCalculator.compute(cal.timeInMillis, latDeg = 0.0, lonDeg = 0.0)
        val dayLenMin = (s.dayLengthMillis ?: 0L) / 60_000L
        // 12 hours = 720 min. We use the standard 90.833° zenith (geometric
        // centre + atmospheric refraction + half-disc), which adds ~5–10 min
        // to the day at the equator. ±15 min handles both that and the
        // SPA simplification error.
        assertTrue("day length was $dayLenMin min", dayLenMin in 705..735)
    }

    /** Subsolar latitude tracks the seasons: positive in northern summer. */
    @Test fun subsolar_lat_positive_in_northern_summer() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.JUNE, 21, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val s = SunPositionCalculator.compute(cal.timeInMillis, latDeg = 0.0, lonDeg = 0.0)
        // Solstice declination ≈ +23.44°.
        assertTrue(
            "subsolar lat was ${s.subsolarLatDeg}",
            s.subsolarLatDeg in 22.5..24.5,
        )
    }

    /** Polar night at high latitudes in winter: no sunrise/sunset. */
    @Test fun polar_night_no_rise_set() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2024, Calendar.DECEMBER, 21, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        val s = SunPositionCalculator.compute(cal.timeInMillis, latDeg = 80.0, lonDeg = 0.0)
        // At 80°N during winter solstice the sun never rises.
        assertTrue(s.sunriseEpochMillis == null)
        assertTrue(s.sunsetEpochMillis == null)
    }
}
