package be.appmire.gpsinfo.data.nav

import android.content.Context
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.command
import btools.router.distanceToNextMeters
import btools.router.hints
import btools.router.iLat
import btools.router.iLon
import btools.router.trackIndex
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fully offline turn-by-turn route computation on BRouter (MIT, pure
 * Java — no JNI, no Play Services, no network once the rd5 segment
 * tiles are on disk).
 *
 * Inputs: the road network as 5°×5° rd5 tiles under
 * `filesDir/brouter/segments` (see [RoutingDataRepository]) and a
 * routing profile + lookups table installed from assets on first use
 * (BRouter resolves `lookups.dat` from the profile's directory).
 *
 * One [route] call spins a BRouter [RoutingEngine] (it *is* a Thread;
 * we run it synchronously on the IO dispatcher) and converts the
 * resulting OsmTrack into plain geometry + [TurnHint]s. BRouter's
 * integer coordinates are offset microdegrees: ilon = (lon+180)·10⁶,
 * ilat = (lat+90)·10⁶.
 */
class OfflineRouter(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir = File(appContext.filesDir, "brouter")
    val segmentsDir = File(baseDir, "segments").apply { mkdirs() }
    private val profilesDir = File(baseDir, "profiles2").apply { mkdirs() }

    /** Compute a car route. Returns null with no throw on "no route
     *  found" (missing tiles, unreachable target); throws only on
     *  programming errors. */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): OfflineRoute? = withContext(Dispatchers.IO) {
        installProfilesIfNeeded()

        val rc = RoutingContext()
        rc.localFunction = File(profilesDir, PROFILE_FILE).absolutePath
        // 1 = auto: generates the VoiceHint list without committing to
        // a specific export dialect.
        rc.turnInstructionMode = 1

        val waypoints = listOf(
            namedNode("from", fromLat, fromLon),
            namedNode("to", toLat, toLon),
        )
        val engine = RoutingEngine(null, null, segmentsDir, waypoints, rc)
        engine.quite = true
        engine.doRun(MAX_ROUTING_MILLIS)

        if (engine.errorMessage != null) return@withContext null
        val track = engine.foundTrack ?: return@withContext null

        val points = track.nodes.map { n ->
            RoutePoint(
                lat = n.iLat / 1e6 - 90.0,
                lon = n.iLon / 1e6 - 180.0,
            )
        }
        val turns = track.voiceHints?.hints?.map { h ->
            TurnHint(
                lat = h.iLat / 1e6 - 90.0,
                lon = h.iLon / 1e6 - 180.0,
                command = TurnCommand.fromBRouter(h.command),
                exitNumber = h.exitNumber,
                distanceToNextMeters = h.distanceToNextMeters,
                trackIndex = h.trackIndex,
            )
        } ?: emptyList()

        OfflineRoute(
            points = points,
            distanceMeters = track.distance,
            durationSeconds = track.totalSeconds,
            turns = turns,
        )
    }

    /** Which segment tiles a route between these points needs, and
     *  which of them are still missing locally. */
    fun missingTiles(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<String> =
        Rd5Tiles.tilesForBoundingBox(fromLat, fromLon, toLat, toLon)
            .filterNot { File(segmentsDir, it).exists() }

    private fun namedNode(label: String, lat: Double, lon: Double): OsmNodeNamed =
        OsmNodeNamed().apply {
            name = label
            ilat = ((lat + 90.0) * 1e6).toInt()
            ilon = ((lon + 180.0) * 1e6).toInt()
        }

    /** Copy the routing profile + lookups table from assets. The pair
     *  must sit in one directory — BRouter resolves lookups.dat from
     *  the profile's parent. Re-copied when the asset changes size
     *  (cheap freshness check; profiles are versioned with the app). */
    private fun installProfilesIfNeeded() {
        for (asset in listOf(LOOKUPS_FILE, PROFILE_FILE)) {
            val target = File(profilesDir, asset)
            appContext.assets.open("brouter/$asset").use { input ->
                val bytes = input.readBytes()
                if (!target.exists() || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
            }
        }
    }

    private companion object {
        /** BRouter's kinematic car-routing profile — time-optimal at
         *  the default target speed, the closest thing upstream ships
         *  to "car-fast". */
        const val PROFILE_FILE = "car-vario.brf"
        const val LOOKUPS_FILE = "lookups.dat"
        const val MAX_ROUTING_MILLIS = 60_000L
    }
}

/** A computed offline route. */
data class OfflineRoute(
    val points: List<RoutePoint>,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val turns: List<TurnHint>,
)

data class RoutePoint(val lat: Double, val lon: Double)

/** One guidance event along the route. */
data class TurnHint(
    val lat: Double,
    val lon: Double,
    val command: TurnCommand,
    /** Roundabout exit number, 0 when not a roundabout. */
    val exitNumber: Int,
    /** Metres from this turn to the next one (or the destination). */
    val distanceToNextMeters: Double,
    /** Index of the turn's node in [OfflineRoute.points]. */
    val trackIndex: Int,
)

/** BRouter command ids mapped to a stable app-side vocabulary. */
enum class TurnCommand {
    STRAIGHT,
    TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT,
    TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    OFF_ROUTE,
    UNKNOWN;

    companion object {
        /** BRouter VoiceHint commands (btools.router.VoiceHint):
         *  1=C 2=TL 3=TSLL 4=TSHL 5=TR 6=TSLR 7=TSHR 8=KL 9=KR
         *  10=TLU 11=TRU 12=OFFR 13=RNDB 14=RNLB 15=TU. */
        fun fromBRouter(cmd: Int): TurnCommand = when (cmd) {
            1 -> STRAIGHT
            2 -> TURN_LEFT
            3 -> TURN_SLIGHT_LEFT
            4 -> TURN_SHARP_LEFT
            5 -> TURN_RIGHT
            6 -> TURN_SLIGHT_RIGHT
            7 -> TURN_SHARP_RIGHT
            8 -> KEEP_LEFT
            9 -> KEEP_RIGHT
            10, 11, 15 -> U_TURN
            12 -> OFF_ROUTE
            13, 14 -> ROUNDABOUT
            else -> UNKNOWN
        }
    }
}
