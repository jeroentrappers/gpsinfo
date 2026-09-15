package be.appmire.gpsinfo.data.nav

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStyleBuilderTest {

    private val region = File("/data/user/0/be.appmire.gpsinfo/files/offline-pmtiles/be.pmtiles")

    @Test
    fun `pmtiles url uses the file scheme and absolute path`() {
        assertEquals(
            "pmtiles://file:///data/user/0/be.appmire.gpsinfo/files/offline-pmtiles/be.pmtiles",
            OfflineStyleBuilder.pmtilesUrl(region),
        )
    }

    @Test
    fun `template asset differs by theme`() {
        assertEquals("maps/style-liberty-offline.json", OfflineStyleBuilder.templateAsset(false))
        assertEquals("maps/style-dark-offline.json", OfflineStyleBuilder.templateAsset(true))
    }

    @Test
    fun `inject replaces the sentinel and leaves no placeholder`() {
        val template = """{"sources":{"openmaptiles":{"type":"vector","url":"__PMTILES_URL__"}}}"""
        val out = OfflineStyleBuilder.injectPmtilesUrl(template, region)
        assertFalse(out.contains(OfflineStyleBuilder.PMTILES_SENTINEL))
        assertTrue(out.contains(OfflineStyleBuilder.pmtilesUrl(region)))
    }
}
