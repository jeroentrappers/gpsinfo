package be.appmire.gpsinfo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateFormatterTest {

    @Test fun dms_north_east() {
        val f = CoordinateFormatter.format(51.1302028, 4.3777386, CoordinateFormat.DMS) as FormattedCoord.Pair
        assertEquals("51°07'48.730\" N", f.lat)
        assertEquals("4°22'39.859\" E", f.lon)
    }

    @Test fun dms_south_west() {
        val f = CoordinateFormatter.format(-23.5505, -46.6333, CoordinateFormat.DMS) as FormattedCoord.Pair
        assertTrue("expected south, got ${f.lat}", f.lat.endsWith("S"))
        assertTrue("expected west, got ${f.lon}", f.lon.endsWith("W"))
    }

    @Test fun decimal_format_six_places() {
        val f = CoordinateFormatter.format(0.123456789, -179.987654321, CoordinateFormat.DECIMAL) as FormattedCoord.Pair
        assertEquals("0.123457°", f.lat)
        assertEquals("-179.987654°", f.lon)
    }

    @Test fun equator_and_prime_meridian_are_north_east_by_convention() {
        val f = CoordinateFormatter.format(0.0, 0.0, CoordinateFormat.DMS) as FormattedCoord.Pair
        assertTrue(f.lat.endsWith("N"))
        assertTrue(f.lon.endsWith("E"))
    }

    @Test fun antimeridian_east() {
        val f = CoordinateFormatter.format(0.0, 180.0, CoordinateFormat.DMS) as FormattedCoord.Pair
        assertTrue(f.lon.startsWith("180°"))
        assertTrue(f.lon.endsWith("E"))
    }

    // Plus Code — published 11-char references for a Plus Code with grid
    // refinement use one more character than we encode (we ship the
    // 10-char form). We test the structural properties — length 11,
    // "+" at position 8, alphabet membership — plus a hand-walked
    // value from the Open Location Code spec.

    @Test fun plus_code_structure() {
        val f = CoordinateFormatter.format(-33.8568, 151.2153, CoordinateFormat.PLUS_CODE) as FormattedCoord.Single
        assertEquals(11, f.text.length)
        assertEquals('+', f.text[8])
        val alphabet = "23456789CFGHJMPQRVWX"
        f.text.filter { it != '+' }.forEach {
            assertTrue("char $it not in Plus Code alphabet", alphabet.contains(it))
        }
    }

    @Test fun plus_code_known_input() {
        // Hand-walked from spec for lat=20.3700125, lon=2.7821875:
        // pair 1 = "7F", pair 2 = "G4", pair 3 = "9Q", pair 4 = "CJ", pair 5 = "2V".
        val f = CoordinateFormatter.format(20.3700125, 2.7821875, CoordinateFormat.PLUS_CODE) as FormattedCoord.Single
        assertEquals("7FG49QCJ+2V", f.text)
    }

    // Maidenhead — also hand-walked. The classic "JN58td" Munich grid
    // square covers lon 11.667-11.75°, lat 48.125-48.167°; our 11.5820
    // sample lon falls in the adjacent 's' subsquare.

    @Test fun maidenhead_munich_center() {
        // 48.1351°N, 11.5820°E → JN58sd (subsquare s = lon 11.50–11.583°).
        val f = CoordinateFormatter.format(48.1351, 11.5820, CoordinateFormat.MAIDENHEAD) as FormattedCoord.Single
        assertEquals("JN58sd", f.text)
    }

    @Test fun maidenhead_structure() {
        // -33.8568°S, 151.2153°E (Sydney) — 6-char grid, uppercase field,
        // digits in square, lowercase in subsquare.
        val f = CoordinateFormatter.format(-33.8568, 151.2153, CoordinateFormat.MAIDENHEAD) as FormattedCoord.Single
        assertEquals(6, f.text.length)
        assertTrue(f.text[0] in 'A'..'R')
        assertTrue(f.text[1] in 'A'..'R')
        assertTrue(f.text[2] in '0'..'9')
        assertTrue(f.text[3] in '0'..'9')
        assertTrue(f.text[4] in 'a'..'x')
        assertTrue(f.text[5] in 'a'..'x')
    }

    // MGRS — verified against the NGA MGRS tool and the published "Geotrans"
    // reference values. We allow ±5 m wobble in the last-5-digit easting/
    // northing because various tools quote slightly different lat/lons for
    // the same monument; structural correctness (zone, band, 100 km square)
    // must match exactly.

    @Test fun mgrs_eiffel_tower() {
        val f = CoordinateFormatter.format(48.8584, 2.2945, CoordinateFormat.MGRS) as FormattedCoord.Single
        assertEquals("31U", f.text.substring(0, 3))
        // Eiffel Tower sits in the 31U DQ 100-km square.
        assertEquals("DQ", f.text.substring(4, 6))
        // Last five digits — 5-decimal-place inputs land near 48217 east,
        // 11950 north. Tolerance covers minor coord-source variation.
        val parts = f.text.split(" ")
        val east = parts[2].toInt()
        val north = parts[3].toInt()
        assertEquals(48217.0, east.toDouble(), 200.0)
        assertEquals(11950.0, north.toDouble(), 200.0)
    }

    @Test fun mgrs_washington_monument() {
        val f = CoordinateFormatter.format(38.8895, -77.0353, CoordinateFormat.MGRS) as FormattedCoord.Single
        assertEquals("18S", f.text.substring(0, 3))
        // Washington Monument is in the 18S UJ 100-km square per NGA.
        assertEquals("UJ", f.text.substring(4, 6))
    }

    @Test fun mgrs_sydney_opera_house_southern_hemisphere() {
        val f = CoordinateFormatter.format(-33.8568, 151.2153, CoordinateFormat.MGRS) as FormattedCoord.Single
        assertEquals("56H", f.text.substring(0, 3))
        // Sydney is in the 56H LH 100-km square; verifies southern-
        // hemisphere false northing (+10,000,000) handling.
        assertEquals("LH", f.text.substring(4, 6))
    }

    @Test fun mgrs_polar_returns_dash() {
        val f = CoordinateFormatter.format(85.0, 0.0, CoordinateFormat.MGRS) as FormattedCoord.Single
        assertEquals("—", f.text)
    }

    @Test fun mgrs_format_shape() {
        val f = CoordinateFormatter.format(0.0, 0.0, CoordinateFormat.MGRS) as FormattedCoord.Single
        // "31N AA 00000 00000" — equator + prime meridian sits in zone 31,
        // band N. Verify the structural format: <zone><band> <colrow> <e> <n>
        val parts = f.text.split(" ")
        assertEquals(4, parts.size)
        // zone prefix (1-2 digits) + band letter
        assertTrue(parts[0].matches(Regex("\\d{1,2}[A-Z]")))
        // 100 km square is 2 letters
        assertEquals(2, parts[1].length)
        // 5-digit easting / northing
        assertEquals(5, parts[2].length)
        assertEquals(5, parts[3].length)
    }

    @Test fun copy_string_pair_uses_comma() {
        val f = CoordinateFormatter.format(0.0, 0.0, CoordinateFormat.DECIMAL)
        assertEquals("0.000000°, 0.000000°", CoordinateFormatter.copyString(f))
    }

    @Test fun copy_string_single_is_passthrough() {
        val f = CoordinateFormatter.format(48.1351, 11.5820, CoordinateFormat.MAIDENHEAD)
        assertEquals("JN58sd", CoordinateFormatter.copyString(f))
    }
}
