package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** How the map chooses between the online tile server and downloaded
 *  offline `.pmtiles` region packs. Persisted (by name) in SettingsRepository. */
enum class MapStyleMode {
    /** Offline when a downloaded region covers the view and the network is
     *  unavailable; online otherwise. The default. */
    AUTO,

    /** Always render from a downloaded region when one covers the view;
     *  fall back to online only when nothing covers it. */
    FORCE_OFFLINE,

    /** Always render from the online tile server. */
    FORCE_ONLINE;

    companion object {
        fun fromString(s: String?): MapStyleMode =
            entries.firstOrNull { it.name == s } ?: AUTO
    }
}

/**
 * Central online/offline style selection, shared by the phone map
 * ([be.appmire.gpsinfo.ui.livemap.MapLibreMapHost]) and the Android Auto GL
 * surface ([org.maplibre.android.maps.CarGlMap]) so both behave identically.
 *
 * The offline style is *region-specific*: its vector source embeds one
 * downloaded region's file path (see [OfflineStyleBuilder]). Selection
 * therefore takes the current camera centre and picks the covering region,
 * unlike the single-URI online model.
 */
class MapStyleResolver(
    context: Context,
    private val repo: OfflineMapRepository,
) {
    private val appContext = context.applicationContext

    /** A resolved style: either an online URI or an offline region pack.
     *  [key] is cheap to compute every frame; the offline style JSON is built
     *  lazily via [Offline.buildJson] only when an actual (re)load happens. */
    sealed interface Resolved {
        /** A stable identity used to decide whether a reload is needed. */
        val key: String

        data class Online(val uri: String, val dark: Boolean) : Resolved {
            override val key get() = "online:${if (dark) "dark" else "light"}"
        }

        class Offline(
            val regionName: String,
            val dark: Boolean,
            val buildJson: () -> String,
        ) : Resolved {
            override val key get() = "offline:$regionName:${if (dark) "dark" else "light"}"
        }
    }

    /**
     * Pick the style for the given camera centre, theme and [mode].
     * A null centre means the viewport is unknown yet — treated as
     * "no covering region", so AUTO/ONLINE fall back to online and
     * FORCE_OFFLINE uses any largest region if one exists.
     */
    fun resolve(dark: Boolean, mode: MapStyleMode, centerLat: Double?, centerLon: Double?): Resolved {
        val online = Resolved.Online(MapLibreStyle.forDark(dark), dark)
        if (mode == MapStyleMode.FORCE_ONLINE) return online

        val region = when {
            centerLat != null && centerLon != null -> coveringRegion(centerLat, centerLon)
            mode == MapStyleMode.FORCE_OFFLINE -> largestRegion()
            else -> null
        } ?: return online

        // AUTO prefers online while connected; FORCE_OFFLINE always uses the pack.
        if (mode == MapStyleMode.AUTO && isOnline()) return online

        return Resolved.Offline(
            regionName = region.name,
            dark = dark,
            buildJson = { OfflineStyleBuilder.buildStyleJson(appContext, dark, region.file) },
        )
    }

    // resolve() runs every map frame, so the region list (which reads sidecar
    // files off disk) is cached behind a short TTL. Downloads/deletes call
    // [invalidateRegions] to pick up changes immediately.
    @Volatile private var cachedRegions: List<OfflineMapRepository.PmtilesRegion>? = null
    @Volatile private var cachedAtMs = 0L

    private fun regions(): List<OfflineMapRepository.PmtilesRegion> {
        val now = System.currentTimeMillis()
        val cached = cachedRegions
        if (cached != null && now - cachedAtMs < REGION_CACHE_TTL_MS) return cached
        return repo.listPmtilesRegions().also {
            cachedRegions = it
            cachedAtMs = now
        }
    }

    /** Drop the cached region list so the next resolve re-reads from disk.
     *  Call after a region download completes or a region is deleted. */
    fun invalidateRegions() {
        cachedRegions = null
    }

    /** The downloaded region whose bbox contains the point, largest-zoom
     *  first (finest detail wins on overlap); null if none. */
    fun coveringRegion(lat: Double, lon: Double): OfflineMapRepository.PmtilesRegion? =
        regions()
            .filter { bboxContains(it.bbox, lat, lon) }
            .maxByOrNull { it.maxZoom }

    private fun largestRegion(): OfflineMapRepository.PmtilesRegion? =
        regions().maxByOrNull { it.sizeBytes }

    /** True when there's a validated internet connection. */
    fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val REGION_CACHE_TTL_MS = 5_000L

        /** Parse a "west,south,east,north" bbox and test point containment. */
        internal fun bboxContains(bbox: String, lat: Double, lon: Double): Boolean {
            val p = bbox.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (p.size != 4) return false
            val (w, s, e, n) = p
            return lon in w..e && lat in s..n
        }
    }
}
