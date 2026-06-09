package be.appmire.gpsinfo.ui.activity

/**
 * First-run persona options (see docs/design/activity-dashboard-ux.md §3).
 * Each maps to a built-in [be.appmire.gpsinfo.data.model.DashboardProfile]
 * by [profileId] and pins one or more [Activity]s (primary first). The
 * picker is multi-select; the first selected persona is "primary" and
 * sets the active dashboard profile + accent, while the pinned activities
 * are the union across all selected personas.
 */
data class PersonaOption(
    val profileId: String,
    val displayName: String,
    /** Activities this persona pins, primary first. */
    val activities: List<Activity>,
)

object Personas {
    val all: List<PersonaOption> = listOf(
        PersonaOption("default", "Driver", listOf(Activity.DRIVE_NAVIGATE, Activity.EXPLORE_ORIENT)),
        PersonaOption("runner", "Runner", listOf(Activity.TRACK_TRAIN)),
        PersonaOption("cyclist", "Cyclist", listOf(Activity.TRACK_TRAIN, Activity.DRIVE_NAVIGATE)),
        PersonaOption("hiker", "Hiker", listOf(Activity.EXPLORE_ORIENT, Activity.TRACK_TRAIN)),
        PersonaOption("sailor", "Sailor", listOf(Activity.EXPLORE_ORIENT, Activity.GPS_LAB)),
        PersonaOption(
            "motorcyclist", "Motorcyclist",
            listOf(Activity.DRIVE_NAVIGATE, Activity.RALLY),
        ),
        PersonaOption("geocacher", "Geocacher", listOf(Activity.EXPLORE_ORIENT)),
        PersonaOption("ham", "Ham / SOTA", listOf(Activity.GPS_LAB, Activity.EXPLORE_ORIENT)),
        PersonaOption("motorsport", "Motorsport", listOf(Activity.RALLY, Activity.DRIVE_NAVIGATE)),
        PersonaOption(
            "custom", "Just GPS stuff",
            // The everything-person: pins all activities, Custom first.
            Activity.entries.toList(),
        ),
    )

    fun byId(id: String): PersonaOption? = all.firstOrNull { it.profileId == id }

    /** Union of the pinned activities of [profileIds], order preserved
     *  (primary activities of earlier-selected personas come first). */
    fun pinnedActivitiesFor(profileIds: Collection<String>): List<Activity> {
        val out = LinkedHashSet<Activity>()
        profileIds.forEach { id -> byId(id)?.activities?.forEach(out::add) }
        return out.toList()
    }
}
