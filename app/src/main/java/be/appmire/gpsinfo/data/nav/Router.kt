package be.appmire.gpsinfo.data.nav

/**
 * The single seam the engine swap turns on (see
 * docs/design/nav-engine-v2.md). BRouter implements this today; Valhalla
 * slots in behind the same interface, after which the default is a
 * one-line change.
 */
interface Router {
    /** Compute a route for [profile], or null on no-route / missing tiles. */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: RouteProfile,
    ): OfflineRoute?

    /** Segment tiles needed for the route bbox that aren't on disk yet. */
    fun missingTiles(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<String>
}

/**
 * Cost model the driver chooses between in the route preview. BRouter
 * maps these to routing profiles; Valhalla maps them to `costing` +
 * options (`auto`, `auto` with `shortest:true`, an eco cost config).
 */
enum class RouteProfile { FASTEST, SHORTEST, ECONOMIC }

/** One computed alternative, tagged with the profile that produced it. */
data class RouteOption(val profile: RouteProfile, val route: OfflineRoute)

/**
 * Lane guidance for an upcoming maneuver — fed to the Car App Library
 * `Step` so the host draws the lane diagram. Populated by Valhalla
 * (maneuver `lanes`); null on BRouter, which carries no lane data.
 */
data class Lane(
    /** Turn directions this lane permits. */
    val directions: List<TurnCommand>,
    /** True when taking this lane follows the route (highlighted). */
    val active: Boolean,
)
