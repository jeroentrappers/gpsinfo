package be.appmire.gpsinfo.ui.activity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's top-level **activities** — the "what do you want to do"
 * model that fronts the app's many modes (see
 * docs/design/activity-dashboard-ux.md). Each activity groups a set of
 * existing screens; the Activity Hub is the launch screen and every
 * activity is a labelled front door that explains why and how to use it.
 *
 * Phase 1: the registry + the Hub + routing into today's screens. The
 * presentation here is route-free — MainActivity owns the
 * activity → nav-route mapping — so this stays decoupled from the
 * (private) Routes table. Per-activity Simple/Pro layouts and the
 * persona-seeded ordering come in later phases.
 */
enum class Activity {
    DRIVE_NAVIGATE,
    TRACK_TRAIN,
    EXPLORE_ORIENT,
    GPS_LAB,
    RALLY,
    CUSTOM,
}

/** Presentation metadata for one activity tile. Copy is English for
 *  now; it moves to string resources in the localization phase. */
data class ActivityInfo(
    val activity: Activity,
    val title: String,
    val icon: ImageVector,
    /** One-line "what it's for", shown on the tile. */
    val what: String,
    /** Two-three sentences of "why + how", shown on demand. */
    val whyHow: String,
    /** Tile accent, packed ARGB (reuses the persona palette). */
    val accentArgb: Int,
)

object Activities {
    // Persona-palette accents (see DashboardProfile).
    private const val ORANGE = 0xFFE67635.toInt()
    private const val RED = 0xFFE53935.toInt()
    private const val GREEN = 0xFF388E3C.toInt()
    private const val TEAL = 0xFF00897B.toInt()
    private const val GRAPHITE = 0xFF37474F.toInt()
    private const val SLATE = 0xFF5F6B76.toInt()

    /** Registry in display order. Persona-driven pinning/reordering is a
     *  later phase; for now the order is fixed. */
    val all: List<ActivityInfo> = listOf(
        ActivityInfo(
            Activity.DRIVE_NAVIGATE,
            title = "Drive & Navigate",
            icon = Icons.Outlined.Navigation,
            what = "Get there — address search, route, live map.",
            whyHow = "Search for an address or place and follow an offline " +
                "turn-by-turn route on a live vector map, with your speed at a " +
                "glance. Tap to open the map, then search or pick a saved place.",
            accentArgb = ORANGE,
        ),
        ActivityInfo(
            Activity.TRACK_TRAIN,
            title = "Track & Train",
            icon = Icons.AutoMirrored.Outlined.DirectionsRun,
            what = "Record a run or ride and pace yourself.",
            whyHow = "Record a GPX/FIT trail with distance, time and pace; add a " +
                "ghost runner, pace targets and heart-rate or power sensors. Start " +
                "recording from the dashboard and review trails afterwards.",
            accentArgb = RED,
        ),
        ActivityInfo(
            Activity.EXPLORE_ORIENT,
            title = "Explore & Orient",
            icon = Icons.Outlined.Explore,
            what = "Where am I, where's that, mark this.",
            whyHow = "A precise compass, your coordinates in any format, waypoints " +
                "to mark and share spots, plus sun/moon and a world view. Open the " +
                "compass, then reach waypoints and sharing from there.",
            accentArgb = GREEN,
        ),
        ActivityInfo(
            Activity.GPS_LAB,
            title = "GPS Lab",
            icon = Icons.Outlined.SatelliteAlt,
            what = "See the raw signal — satellites, NMEA, fix quality.",
            whyHow = "Inspect the sky plot, per-satellite signal strength, fix " +
                "status and accuracy, and the raw NMEA stream. For the curious, " +
                "for surveying, and for diagnosing reception.",
            accentArgb = TEAL,
        ),
        ActivityInfo(
            Activity.RALLY,
            title = "Rally / Regularity",
            icon = Icons.Outlined.SportsScore,
            what = "Hold an exact average over a stage.",
            whyHow = "Run a regularity stage to a target average speed with a " +
                "live early/late delta, wheel-sensor distance and ±recalibration. " +
                "Arm the stage, then start on the marshal's go.",
            accentArgb = GRAPHITE,
        ),
        ActivityInfo(
            Activity.CUSTOM,
            title = "Custom / Everything",
            icon = Icons.Outlined.Tune,
            what = "Your full, editable dashboard — all cards on one screen.",
            whyHow = "The classic all-in-one dashboard: pick exactly which cards " +
                "show and in what order. Everything the app can do, on a single " +
                "screen, the way you arrange it.",
            accentArgb = SLATE,
        ),
    )
}
