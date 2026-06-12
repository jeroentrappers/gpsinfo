package be.appmire.gpsinfo.ui.about

/** A user-selectable app language. `tag` null = follow system. */
data class AppLanguage(val tag: String?, val endonym: String)

/**
 * The app's supported UI languages, shared by the Settings language row
 * and the onboarding language step so the list stays in one place. The
 * endonyms are intentionally untranslated (a language is named in its
 * own tongue). Keep in sync with res/xml/locales_config.xml.
 */
object AppLanguages {
    val all: List<AppLanguage> = listOf(
        AppLanguage(null, ""), // system default
        AppLanguage("en", "English"),
        AppLanguage("cs", "Čeština"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("it", "Italiano"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("pl", "Polski"),
        AppLanguage("pt-BR", "Português (Brasil)"),
        AppLanguage("ru", "Русский"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("ja", "日本語"),
    )

    /** Tag-equality tolerant of case, '_' vs '-', and region subtags. */
    fun matches(option: String?, current: String?): Boolean {
        if (option == null) return current.isNullOrEmpty()
        if (current.isNullOrEmpty()) return false
        val o = option.lowercase().replace('_', '-')
        val c = current.lowercase().replace('_', '-')
        return c == o || c.startsWith("$o-") || o.startsWith("$c-")
    }
}
