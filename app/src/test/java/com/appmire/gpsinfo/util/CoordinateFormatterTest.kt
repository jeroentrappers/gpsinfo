package com.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateFormatterTest {

    @Test fun dms_north_east() {
        val f = CoordinateFormatter.format(51.1302028, 4.3777386, CoordinateFormat.DMS)
        assertEquals("51°07'48.730\" N", f.lat)
        assertEquals("4°22'39.859\" E", f.lon)
    }

    @Test fun dms_south_west() {
        val f = CoordinateFormatter.format(-23.5505, -46.6333, CoordinateFormat.DMS)
        assertTrue("expected south, got ${f.lat}", f.lat.endsWith("S"))
        assertTrue("expected west, got ${f.lon}", f.lon.endsWith("W"))
    }

    @Test fun decimal_format_six_places() {
        val f = CoordinateFormatter.format(0.123456789, -179.987654321, CoordinateFormat.DECIMAL)
        assertEquals("0.123457°", f.lat)
        assertEquals("-179.987654°", f.lon)
    }

    @Test fun equator_and_prime_meridian_are_north_east_by_convention() {
        // 0,0 should not crash and should pick a hemisphere — we treat 0 as N/E.
        val f = CoordinateFormatter.format(0.0, 0.0, CoordinateFormat.DMS)
        assertTrue(f.lat.endsWith("N"))
        assertTrue(f.lon.endsWith("E"))
    }

    @Test fun antimeridian_east() {
        val f = CoordinateFormatter.format(0.0, 180.0, CoordinateFormat.DMS)
        assertTrue(f.lon.startsWith("180°"))
        assertTrue(f.lon.endsWith("E"))
    }
}
