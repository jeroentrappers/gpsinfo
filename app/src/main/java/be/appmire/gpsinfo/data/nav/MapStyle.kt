package be.appmire.gpsinfo.data.nav

import be.appmire.gpsinfo.BuildConfig

/**
 * Shared MapLibre style URLs. Two distinct roles:
 *
 *  - **Live rendering** ([LIBERTY] / [DARK]) — used by the phone live map
 *    and the car snapshotter. When a self-hosted tile server is configured
 *    (`tilesBaseUrl` build property, see app/build.gradle.kts) these point
 *    at our own styles, which reference a `pmtiles://` vector source served
 *    statically off the box. With no base configured they fall back to
 *    OpenFreeMap's public endpoint, so dev builds need no setup.
 *
 *  - **Offline pre-download** ([OFFLINE_DOWNLOAD]) — used by the region and
 *    route-corridor downloaders, which go through MapLibre's `OfflineManager`.
 *    `OfflineManager` cannot read a `pmtiles://` source, so this deliberately
 *    stays on the XYZ OpenFreeMap style until the offline path is migrated to
 *    downloaded `.pmtiles` region files (a `file://` source). Keeping it
 *    separate is what lets the live map cut over to self-hosting without
 *    breaking offline downloads.
 *
 * The key (when self-hosting) is passed as a `?key=` query param, checked by
 * the Caddy front-end. It is a coarse gate, not a secret — a client-shipped
 * key is always extractable; abuse is bounded server-side by rate limiting.
 */
object MapLibreStyle {
    private val base = BuildConfig.TILES_BASE_URL.trimEnd('/')
    private val keyQuery =
        if (base.isNotEmpty() && BuildConfig.TILES_API_KEY.isNotEmpty())
            "?key=${BuildConfig.TILES_API_KEY}"
        else ""

    private const val OFM_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    private const val OFM_DARK = "https://tiles.openfreemap.org/styles/dark"

    /** Live render style (light). Self-hosted PMTiles style when configured. */
    val LIBERTY: String =
        if (base.isEmpty()) OFM_LIBERTY else "$base/styles/liberty.json$keyQuery"

    /** Live render style (dark). Same OpenMapTiles schema as [LIBERTY], so the
     *  same annotation layers work. */
    val DARK: String =
        if (base.isEmpty()) OFM_DARK else "$base/styles/dark.json$keyQuery"

    fun forDark(dark: Boolean): String = if (dark) DARK else LIBERTY

    /** Style used for MapLibre `OfflineManager` pre-downloads (regions +
     *  route corridors). Stays on OpenFreeMap until offline moves to
     *  `.pmtiles` region files — see the class comment. */
    const val OFFLINE_DOWNLOAD = OFM_LIBERTY
}
