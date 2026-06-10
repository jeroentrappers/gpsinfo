package be.appmire.gpsinfo.data.nav

/**
 * Shared MapLibre style URL — used by the phone live map, the car
 * snapshotter and both offline downloaders (map regions + route
 * corridors) so everything caches against one style. OpenFreeMap
 * "liberty": free OSM vector, no API key, no usage limits,
 * self-hostable (swap this one constant to point at your own).
 */
object MapLibreStyle {
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"

    /** OpenFreeMap's dark style (same OpenMapTiles schema as Liberty, so
     *  the same annotation layers + offline tiles work). Used on the live
     *  map when the app is in dark mode. */
    const val DARK = "https://tiles.openfreemap.org/styles/dark"

    fun forDark(dark: Boolean): String = if (dark) DARK else LIBERTY
}
