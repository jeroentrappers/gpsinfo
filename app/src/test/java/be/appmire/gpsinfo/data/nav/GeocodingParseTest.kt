package be.appmire.gpsinfo.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingParseTest {

    @Test
    fun `parses photon feature collection into results`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"geometry":{"type":"Point","coordinates":[3.2785,51.3501]},
               "properties":{"name":"","street":"Lippenslaan","housenumber":"200",
                 "postcode":"8300","city":"Knokke-Heist","country":"Belgium"}},
              {"geometry":{"type":"Point","coordinates":[4.4025,51.2194]},
               "properties":{"name":"Grote Markt","city":"Antwerpen","country":"Belgium"}}
            ]}
        """.trimIndent()
        val results = GeocodingRepository.parsePhoton(json)
        assertEquals(2, results.size)
        // coordinates are [lon, lat] on the wire.
        assertEquals(51.3501, results[0].lat, 1e-6)
        assertEquals(3.2785, results[0].lon, 1e-6)
        assertEquals("Lippenslaan 200", results[0].label)
        assertTrue(results[0].detail.contains("Knokke-Heist"))
        // A named POI with no street uses its name.
        assertEquals("Grote Markt", results[1].label)
    }

    @Test
    fun `tolerates missing and malformed features`() {
        assertEquals(0, GeocodingRepository.parsePhoton("""{"features":[]}""").size)
        assertEquals(0, GeocodingRepository.parsePhoton("""{}""").size)
        // A feature missing coordinates is skipped, not crashed on.
        val json = """{"features":[{"properties":{"name":"X"}}]}"""
        assertEquals(0, GeocodingRepository.parsePhoton(json).size)
    }
}
