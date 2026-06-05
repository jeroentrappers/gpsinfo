package be.appmire.gpsinfo.data.nav

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.hints
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end proof that the BRouter engine routes on a real rd5 tile,
 * using the exact profile + lookups files the app ships in assets.
 *
 * Needs the Benelux tile downloaded once to ~/.cache/brouter-test:
 *   curl -L https://brouter.de/brouter/segments4/E0_N50.rd5 \
 *        -o ~/.cache/brouter-test/E0_N50.rd5
 * The test self-skips when the tile is absent, so CI stays light.
 */
class BRouterIntegrationTest {

    private val segmentsDir = File(System.getProperty("user.home"), ".cache/brouter-test")
    private val profilesDir = File("src/main/assets/brouter")

    @Test
    fun `routes Knokke to Brugge on the real network`() {
        assumeTrue(
            "E0_N50.rd5 not present — see class doc",
            File(segmentsDir, "E0_N50.rd5").exists(),
        )

        val rc = RoutingContext()
        rc.localFunction = File(profilesDir, "car-vario.brf").absolutePath
        rc.turnInstructionMode = 1

        val waypoints = listOf(
            node("from", 51.346, 3.287), // Knokke
            node("to", 51.209, 3.225),   // Brugge
        )
        val engine = RoutingEngine(null, null, segmentsDir, waypoints, rc)
        engine.quite = true
        engine.doRun(60_000)

        assertNull("routing error: ${engine.errorMessage}", engine.errorMessage)
        val track = engine.foundTrack
        assertNotNull("no track found", track)
        // Knokke→Brugge is ~17 km by road; accept a generous band so
        // OSM data churn doesn't flake the test.
        assertTrue("distance ${track.distance}", track.distance in 12_000..35_000)
        assertTrue("too few nodes: ${track.nodes.size}", track.nodes.size > 50)
        assertTrue("duration ${track.totalSeconds}", track.totalSeconds in 10 * 60..90 * 60)
        val turns = track.voiceHints?.hints ?: emptyList()
        assertTrue("expected turn hints, got ${turns.size}", turns.isNotEmpty())
        println(
            "BRouter OK: ${track.distance} m, ${track.totalSeconds} s, " +
                "${track.nodes.size} nodes, ${turns.size} turn hints",
        )
    }

    private fun node(label: String, lat: Double, lon: Double): OsmNodeNamed =
        OsmNodeNamed().apply {
            name = label
            ilat = ((lat + 90.0) * 1e6).toInt()
            ilon = ((lon + 180.0) * 1e6).toInt()
        }
}
