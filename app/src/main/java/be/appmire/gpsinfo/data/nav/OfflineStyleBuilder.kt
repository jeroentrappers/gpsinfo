package be.appmire.gpsinfo.data.nav

import android.content.Context
import java.io.File

/**
 * Assembles the runtime style JSON that renders a downloaded `.pmtiles`
 * region fully offline.
 *
 * MapLibre Native reads a local PMTiles archive through a
 * `pmtiles://file://…` vector source, but glyphs and sprites are separate
 * resource classes that are **not** inside the `.pmtiles` — if they don't
 * resolve on-device the map draws geometry with no labels or icons. So the
 * offline styles are bundled in `assets/maps/` with their `glyphs`/`sprite`
 * already pointing at `asset://` URIs (see the assets built from the server
 * styles); the only thing that varies per region is the vector source `url`,
 * which the template carries as the [PMTILES_SENTINEL] placeholder.
 *
 * This keeps the offline style self-contained: no network is touched to build
 * or render it, which is the whole point of "true" offline packs.
 */
object OfflineStyleBuilder {

    /** Placeholder in the bundled templates, replaced with the region url. */
    const val PMTILES_SENTINEL = "__PMTILES_URL__"

    private const val TEMPLATE_LIBERTY = "maps/style-liberty-offline.json"
    private const val TEMPLATE_DARK = "maps/style-dark-offline.json"

    /** The `pmtiles://file://` source url for a downloaded region file. */
    fun pmtilesUrl(file: File): String = "pmtiles://file://${file.absolutePath}"

    /** Asset path of the bundled offline style template for the theme. */
    fun templateAsset(dark: Boolean): String =
        if (dark) TEMPLATE_DARK else TEMPLATE_LIBERTY

    /**
     * Build the full offline style JSON for [regionFile], ready for
     * `Style.Builder().fromJson(...)`. Reads the bundled [dark]/light
     * template and splices in the region's `pmtiles://file://` url.
     */
    fun buildStyleJson(context: Context, dark: Boolean, regionFile: File): String {
        val template = context.assets.open(templateAsset(dark)).bufferedReader()
            .use { it.readText() }
        return injectPmtilesUrl(template, regionFile)
    }

    /** Pure splice of the region url into a template string (no I/O). */
    internal fun injectPmtilesUrl(template: String, regionFile: File): String =
        template.replace(PMTILES_SENTINEL, pmtilesUrl(regionFile))
}
