package be.appmire.gpsinfo.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Rd5TilesTest {

    @Test
    fun `tile names follow brouter convention`() {
        // Knokke: 51.34N, 3.29E → 5° cell starting at E0/N50.
        assertEquals("E0_N50.rd5", Rd5Tiles.tileName(51.346, 3.287))
        // Aix-les-Bains (Tulpenrallye start): 45.69N, 5.91E.
        assertEquals("E5_N45.rd5", Rd5Tiles.tileName(45.69, 5.91))
        // Western hemisphere + negative floor: Lisbon 38.7N, 9.14W
        // lies in the cell starting at W10.
        assertEquals("W10_N35.rd5", Rd5Tiles.tileName(38.7, -9.14))
        // Southern hemisphere: Sydney 33.87S, 151.2E.
        assertEquals("E150_S35.rd5", Rd5Tiles.tileName(-33.87, 151.21))
        // Exactly on a boundary belongs to the cell it starts.
        assertEquals("E5_N50.rd5", Rd5Tiles.tileName(50.0, 5.0))
    }

    @Test
    fun `bbox cover spans all crossed cells`() {
        // Knokke → Strasbourg crosses E0/E5 and N45/N50 cells.
        val tiles = Rd5Tiles.tilesForBoundingBox(51.35, 3.29, 48.58, 7.75)
        assertTrue(tiles.contains("E0_N50.rd5"))
        assertTrue(tiles.contains("E5_N50.rd5"))
        assertTrue(tiles.contains("E0_N45.rd5"))
        assertTrue(tiles.contains("E5_N45.rd5"))
        assertEquals(4, tiles.size)
    }

    @Test
    fun `short route within one cell needs one tile`() {
        val tiles = Rd5Tiles.tilesForBoundingBox(51.35, 3.29, 51.20, 3.22)
        assertEquals(listOf("E0_N50.rd5"), tiles)
    }

    @Test
    fun `margin pulls in the neighbouring cell near an edge`() {
        // Both points just east of the 5°E meridian, but within the
        // 0.1° margin — the western neighbour must be included.
        val tiles = Rd5Tiles.tilesForBoundingBox(51.0, 5.05, 51.2, 5.4)
        assertTrue(tiles.contains("E5_N50.rd5"))
        assertTrue(tiles.contains("E0_N50.rd5"))
    }

    @Test
    fun `download url targets the segments4 server`() {
        assertEquals(
            "https://brouter.de/brouter/segments4/E0_N50.rd5",
            Rd5Tiles.downloadUrl("E0_N50.rd5"),
        )
    }
}
