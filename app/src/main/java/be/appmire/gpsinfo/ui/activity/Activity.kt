package be.appmire.gpsinfo.ui.activity

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import be.appmire.gpsinfo.R

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

/** How much an activity shows. Simple = curated essentials; Pro = the
 *  full instrument set (today's screens). Defaulted from the persona,
 *  toggled in the activity's app bar, persisted per activity. */
enum class DetailLevel { SIMPLE, PRO }

/** Presentation metadata for one activity tile. Text is localized — the
 *  UI resolves the @StringRes ids with stringResource. */
data class ActivityInfo(
    val activity: Activity,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    /** One-line "what it's for", shown on the tile. */
    @StringRes val whatRes: Int,
    /** Two-three sentences of "why + how", shown on demand. */
    @StringRes val whyHowRes: Int,
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
            titleRes = R.string.activity_drive_title,
            icon = Icons.Outlined.Navigation,
            whatRes = R.string.activity_drive_what,
            whyHowRes = R.string.activity_drive_whyhow,
            accentArgb = ORANGE,
        ),
        ActivityInfo(
            Activity.TRACK_TRAIN,
            titleRes = R.string.activity_track_title,
            icon = Icons.AutoMirrored.Outlined.DirectionsRun,
            whatRes = R.string.activity_track_what,
            whyHowRes = R.string.activity_track_whyhow,
            accentArgb = RED,
        ),
        ActivityInfo(
            Activity.EXPLORE_ORIENT,
            titleRes = R.string.activity_explore_title,
            icon = Icons.Outlined.Explore,
            whatRes = R.string.activity_explore_what,
            whyHowRes = R.string.activity_explore_whyhow,
            accentArgb = GREEN,
        ),
        ActivityInfo(
            Activity.GPS_LAB,
            titleRes = R.string.activity_gpslab_title,
            icon = Icons.Outlined.SatelliteAlt,
            whatRes = R.string.activity_gpslab_what,
            whyHowRes = R.string.activity_gpslab_whyhow,
            accentArgb = TEAL,
        ),
        ActivityInfo(
            Activity.RALLY,
            titleRes = R.string.activity_rally_title,
            icon = Icons.Outlined.SportsScore,
            whatRes = R.string.activity_rally_what,
            whyHowRes = R.string.activity_rally_whyhow,
            accentArgb = GRAPHITE,
        ),
        ActivityInfo(
            Activity.CUSTOM,
            titleRes = R.string.activity_custom_title,
            icon = Icons.Outlined.Tune,
            whatRes = R.string.activity_custom_what,
            whyHowRes = R.string.activity_custom_whyhow,
            accentArgb = SLATE,
        ),
    )
}
