package be.appmire.gpsinfo.data.model

/**
 * Identifier for one dashboard card. Used by [DashboardProfile] to
 * describe which cards a persona surfaces and in what order. Order in
 * the enum is not significant — profiles re-order freely.
 *
 * Conditional sections (HeartRate, Navigation, TripComputer, AutoPause,
 * banners) are NOT in this enum: the dashboard renders them above the
 * profile-ordered cards based on their own activation conditions, so a
 * profile that doesn't list HeartRate doesn't gate the card when a
 * monitor is actually paired.
 */
enum class DashboardSection {
    Status,
    Position,
    Speed,
    Sky,
    Compass,
    World,
    TimeSun,
    GForce,
}

/**
 * A named ordering of dashboard cards plus the visual chrome the
 * persona wants. Presets ship hard-coded for known personas; the user
 * can switch between them via the dashboard top app bar.
 *
 * Later iterations (R2.b) will let the user fork a built-in into a
 * customised profile and drag-reorder the cards.
 */
/**
 * Chrome variants for a dashboard profile. `Normal` follows the
 * system / user-selected light or dark theme. `NightDimRed` overrides
 * with a low-luminance dim-red palette — preserves dark adaptation
 * for sailors at the helm, photographers at the eyepiece, anyone
 * reading the dashboard in a dark environment without burning their
 * night vision.
 */
enum class ChromeStyle(val key: String) {
    Normal("normal"),
    NightDimRed("night_dim_red");

    companion object {
        fun fromString(s: String?): ChromeStyle =
            entries.firstOrNull { it.key == s } ?: Normal
    }
}

data class DashboardProfile(
    val id: String,
    val displayName: String,
    val cards: List<DashboardSection>,
    /**
     * Accent colour as a packed ARGB int. Drives the profile chip in
     * the switcher and the brand-mark tint on the dashboard. Null
     * means "use Material theme primary" — the visual identity
     * disappears, app behaves like the original single-theme app.
     *
     * Stored as Int rather than `Color` because the model layer is
     * Compose-free; the UI lifts to `Color` at render time.
     */
    val accentArgb: Int? = null,
    /** Visual chrome — Normal follows the user's theme; NightDimRed
     *  applies a low-luminance red overlay across the dashboard and
     *  the Sports view for dark-adapted use cases. */
    val chromeStyle: ChromeStyle = ChromeStyle.Normal,
) {
    companion object {
        // Persona-fit defaults: orange = default brand, red = runner,
        // blue = cyclist (road bib), green = hiker (forest), navy =
        // sailor, amber = motorcyclist (gear-yellow), purple =
        // geocacher (Geocaching.com's signature), teal = ham (radio
        // wave). All hand-picked for legibility against both the
        // light and dark themes; the dashboard uses tertiary surface
        // colours by default so the accent stands out.
        const val COLOR_ORANGE = 0xFFE67635.toInt()
        const val COLOR_RED = 0xFFE53935.toInt()
        const val COLOR_BLUE = 0xFF1976D2.toInt()
        const val COLOR_GREEN = 0xFF388E3C.toInt()
        const val COLOR_NAVY = 0xFF0D47A1.toInt()
        const val COLOR_AMBER = 0xFFFFA000.toInt()
        const val COLOR_PURPLE = 0xFF7B1FA2.toInt()
        const val COLOR_TEAL = 0xFF00897B.toInt()
        // Motorsport: graphite/carbon — distinct from the runner's red.
        const val COLOR_GRAPHITE = 0xFF37474F.toInt()

        val Default = DashboardProfile(
            id = "default",
            displayName = "Default",
            cards = listOf(
                DashboardSection.Status,
                DashboardSection.Position,
                DashboardSection.Speed,
                DashboardSection.Sky,
                DashboardSection.Compass,
                DashboardSection.GForce,
                DashboardSection.World,
                DashboardSection.TimeSun,
            ),
            accentArgb = COLOR_ORANGE,
        )

        val Runner = DashboardProfile(
            id = "runner",
            displayName = "Runner",
            cards = listOf(
                DashboardSection.Status,
                DashboardSection.Speed,
                DashboardSection.Position,
                DashboardSection.TimeSun,
            ),
            accentArgb = COLOR_RED,
        )

        val Cyclist = DashboardProfile(
            id = "cyclist",
            displayName = "Cyclist",
            cards = listOf(
                DashboardSection.Speed,
                DashboardSection.Status,
                DashboardSection.GForce,
                DashboardSection.Position,
                DashboardSection.Compass,
                DashboardSection.TimeSun,
            ),
            accentArgb = COLOR_BLUE,
        )

        val Hiker = DashboardProfile(
            id = "hiker",
            displayName = "Hiker",
            cards = listOf(
                DashboardSection.Position,
                DashboardSection.TimeSun,
                DashboardSection.Compass,
                DashboardSection.Status,
                DashboardSection.Sky,
            ),
            accentArgb = COLOR_GREEN,
        )

        val Sailor = DashboardProfile(
            id = "sailor",
            displayName = "Sailor",
            cards = listOf(
                DashboardSection.Speed,
                DashboardSection.Compass,
                DashboardSection.Position,
                DashboardSection.TimeSun,
                DashboardSection.World,
            ),
            accentArgb = COLOR_NAVY,
        )

        val Motorcyclist = DashboardProfile(
            id = "motorcyclist",
            displayName = "Motorcyclist",
            cards = listOf(
                DashboardSection.Speed,
                DashboardSection.Compass,
                DashboardSection.Position,
                DashboardSection.TimeSun,
            ),
            accentArgb = COLOR_AMBER,
        )

        val Geocacher = DashboardProfile(
            id = "geocacher",
            displayName = "Geocacher",
            cards = listOf(
                DashboardSection.Position,
                DashboardSection.Compass,
                DashboardSection.Status,
                DashboardSection.Sky,
            ),
            accentArgb = COLOR_PURPLE,
        )

        val Ham = DashboardProfile(
            id = "ham",
            displayName = "Ham / SOTA",
            cards = listOf(
                DashboardSection.Position,
                DashboardSection.TimeSun,
                DashboardSection.World,
                DashboardSection.Status,
                DashboardSection.Sky,
            ),
            accentArgb = COLOR_TEAL,
        )

        val Motorsport = DashboardProfile(
            id = "motorsport",
            displayName = "Motorsport",
            cards = listOf(
                DashboardSection.Speed,
                DashboardSection.GForce,
                DashboardSection.Status,
                DashboardSection.Compass,
            ),
            accentArgb = COLOR_GRAPHITE,
        )

        val builtIns: List<DashboardProfile> = listOf(
            Default, Runner, Cyclist, Hiker, Sailor, Motorcyclist, Geocacher, Ham, Motorsport,
        )

        const val CUSTOM_ID = "custom"

        fun fromId(id: String?): DashboardProfile =
            builtIns.firstOrNull { it.id == id } ?: Default

        /** Build the "Custom" profile from a raw card-list string,
         *  optional accent, and optional chrome. Missing/empty inputs
         *  fall back to Default's values. Keeps the Custom entry
         *  usable before the user has actually opened the editor. */
        fun customFrom(
            rawCards: String?,
            accentArgb: Int? = null,
            chromeStyle: ChromeStyle = ChromeStyle.Normal,
        ): DashboardProfile {
            val cards: List<DashboardSection> = rawCards
                ?.split(',')
                ?.mapNotNull { token ->
                    runCatching { DashboardSection.valueOf(token.trim()) }.getOrNull()
                }
                ?.takeIf { it.isNotEmpty() }
                ?: Default.cards
            return DashboardProfile(
                id = CUSTOM_ID,
                displayName = "Custom",
                cards = cards,
                accentArgb = accentArgb ?: COLOR_ORANGE,
                chromeStyle = chromeStyle,
            )
        }

        /** Built-in accent swatches the colour picker offers for the
         *  Custom profile. Ordered for natural row-wise display. */
        val customColorChoices: List<Int> = listOf(
            COLOR_ORANGE, COLOR_RED, COLOR_AMBER, COLOR_GREEN,
            COLOR_TEAL, COLOR_BLUE, COLOR_NAVY, COLOR_PURPLE,
        )

        /** Encode a section list to the comma-separated representation
         *  persisted in DataStore. */
        fun encodeCards(cards: List<DashboardSection>): String =
            cards.joinToString(",") { it.name }
    }
}
