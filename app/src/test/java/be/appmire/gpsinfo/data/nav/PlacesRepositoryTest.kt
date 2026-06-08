package be.appmire.gpsinfo.data.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesRepositoryTest {

    private fun visit(
        places: List<SavedPlace>,
        lat: Double,
        lon: Double,
        name: String,
        now: Long,
    ) = PlacesRepository.recorded(places, lat, lon, name, "", now)

    @Test
    fun `record adds a recent`() {
        val out = visit(emptyList(), 51.34, 3.28, "Knokke", 100)
        assertEquals(1, out.size)
        assertEquals(PlaceRole.RECENT, out[0].role)
        assertEquals("Knokke", out[0].name)
    }

    @Test
    fun `nearby revisit bumps timestamp, no duplicate`() {
        var p = visit(emptyList(), 51.3400, 3.2800, "Knokke", 100)
        // ~20 m away → same place.
        p = visit(p, 51.34015, 3.28015, "Knokke again", 200)
        assertEquals(1, p.size)
        assertEquals(200L, p[0].updatedAt)
    }

    @Test
    fun `far visit is a separate place`() {
        var p = visit(emptyList(), 51.34, 3.28, "Knokke", 100)
        p = visit(p, 51.21, 3.22, "Brugge", 200)
        assertEquals(2, p.size)
    }

    @Test
    fun `recents are capped, newest kept`() {
        var p = emptyList<SavedPlace>()
        for (i in 1..(PlacesRepository.RECENTS_CAP + 5)) {
            // Spread far apart so each is distinct.
            p = visit(p, 50.0 + i * 0.1, 4.0 + i * 0.1, "P$i", i.toLong())
        }
        assertEquals(PlacesRepository.RECENTS_CAP, p.size)
        // The oldest (P1) must have been trimmed; the newest survive.
        assertTrue(p.none { it.name == "P1" })
        assertTrue(p.any { it.name == "P${PlacesRepository.RECENTS_CAP + 5}" })
    }

    @Test
    fun `home and labelled survive the recents cap`() {
        var p = visit(emptyList(), 50.0, 4.0, "MyHome", 1)
        p = PlacesRepository.sorted(p.map { it.copy(role = PlaceRole.HOME) })
        // Flood with recents.
        for (i in 1..(PlacesRepository.RECENTS_CAP + 5)) {
            p = visit(p, 51.0 + i * 0.1, 5.0 + i * 0.1, "R$i", 100 + i.toLong())
        }
        assertTrue("home must survive", p.any { it.role == PlaceRole.HOME && it.name == "MyHome" })
        assertEquals(PlacesRepository.RECENTS_CAP, p.count { it.role == PlaceRole.RECENT })
    }

    @Test
    fun `sort pins home, work, labelled, then recents newest-first`() {
        val home = SavedPlace("h", 0.0, 0.0, "H", role = PlaceRole.HOME)
        val work = SavedPlace("w", 0.0, 0.0, "W", role = PlaceRole.WORK)
        val gym = SavedPlace("g", 0.0, 0.0, "Gym", role = PlaceRole.LABELED, label = "Gym")
        val r1 = SavedPlace("r1", 0.0, 0.0, "R1", role = PlaceRole.RECENT, updatedAt = 10)
        val r2 = SavedPlace("r2", 0.0, 0.0, "R2", role = PlaceRole.RECENT, updatedAt = 20)
        val sorted = PlacesRepository.sorted(listOf(r1, gym, r2, work, home))
        assertEquals(listOf("h", "w", "g", "r2", "r1"), sorted.map { it.id })
    }

    @Test
    fun `displayTitle reflects role`() {
        assertEquals("Home", SavedPlace("i", 0.0, 0.0, "x", role = PlaceRole.HOME).displayTitle)
        assertEquals(
            "Gym",
            SavedPlace("i", 0.0, 0.0, "x", role = PlaceRole.LABELED, label = "Gym").displayTitle,
        )
        assertEquals("x", SavedPlace("i", 0.0, 0.0, "x", role = PlaceRole.RECENT).displayTitle)
    }
}
