package be.appmire.gpsinfo.data.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleResolverTest {

    // bbox is "west,south,east,north"; Belgium ~ 2.51,49.49,6.41,51.51
    private val be = "2.51,49.49,6.41,51.51"

    @Test
    fun `point inside bbox is contained`() {
        // Brussels
        assertTrue(MapStyleResolver.bboxContains(be, 50.85, 4.35))
    }

    @Test
    fun `point outside bbox is not contained`() {
        // Paris — west and south of Belgium
        assertFalse(MapStyleResolver.bboxContains(be, 48.85, 2.35))
    }

    @Test
    fun `malformed bbox is never contained`() {
        assertFalse(MapStyleResolver.bboxContains("", 50.85, 4.35))
        assertFalse(MapStyleResolver.bboxContains("1,2,3", 50.85, 4.35))
    }

    @Test
    fun `mode name round-trips and defaults to AUTO`() {
        assertTrue(MapStyleMode.fromString("FORCE_OFFLINE") == MapStyleMode.FORCE_OFFLINE)
        assertTrue(MapStyleMode.fromString(null) == MapStyleMode.AUTO)
        assertTrue(MapStyleMode.fromString("garbage") == MapStyleMode.AUTO)
    }
}
