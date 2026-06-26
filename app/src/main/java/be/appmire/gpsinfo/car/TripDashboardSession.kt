package be.appmire.gpsinfo.car

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.data.nav.GeocodingRepository
import be.appmire.gpsinfo.data.nav.NavigationController
import java.net.URLDecoder
import kotlinx.coroutines.launch

/**
 * One Session per head-unit projection cycle. Owns the [CarMapRenderer]
 * — the surface callback registration is per-CarContext, so it must
 * outlive screen pushes/pops (RecentTrails draws its template over the
 * same surface) — and hands it to the root trip-dashboard screen.
 *
 * Also the navigation-intent entry point: the host delivers
 * [CarContext.ACTION_NAVIGATE] (Assistant "navigate to X", or another
 * app's `geo:` deep link) here — cold start via [onCreateScreen], warm
 * via [onNewIntent]. We parse the `geo:` URI (coordinates, or a
 * free-form query geocoded with Photon) and kick off an offline route
 * through [NavigationController], which the dashboard then draws.
 * Handling this is mandatory for the NAVIGATION category (rule NF-6).
 */
class TripDashboardSession : Session() {

    private var renderer: CarMapRenderer? = null

    /** Feeds turn-by-turn to the instrument cluster via NavigationManager
     *  for the whole projection cycle (it must outlive screen pushes and
     *  own the host's single nav session, so it lives on the Session, not
     *  the screen). Constructed once; tears itself down with [lifecycle]. */
    private var clusterReporter: ClusterNavReporter? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val mapRenderer = CarMapRenderer(carContext, lifecycle)
        renderer = mapRenderer
        clusterReporter = ClusterNavReporter(carContext, lifecycle)
        // A cold start may itself be a navigate intent.
        startNavigationFromIntent(intent)
        return TripDashboardScreen(carContext, mapRenderer)
    }

    override fun onNewIntent(intent: Intent) {
        if (startNavigationFromIntent(intent)) {
            // Surface the map (and the route we just started) by popping
            // any list screen — Places / RecentTrails — off the top.
            carContext.getCarService(ScreenManager::class.java).popToRoot()
        }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        // Day/night flips arrive here — repaint so the map scrim and
        // HUD palette follow the head unit immediately.
        renderer?.repaint()
    }

    /** Handle a [CarContext.ACTION_NAVIGATE] `geo:` intent. Returns true
     *  if it was a navigate intent we took on (so the caller can bring
     *  the map to front), false otherwise. */
    private fun startNavigationFromIntent(intent: Intent): Boolean {
        if (intent.action != CarContext.ACTION_NAVIGATE) return false
        val uri = intent.data ?: return false
        if (!uri.scheme.equals("geo", ignoreCase = true)) return false
        // Respond on the surface the instant we accept the intent — the
        // banner shows "Finding…" before geocoding/origin resolve, so the
        // app visibly reacts to "navigate to X" even while the route is
        // still pending.
        NavigationController.indicateSearching(searchLabel(uri))
        // Resolve + route off the main thread (geocoding may hit the
        // network); the dashboard shows progress meanwhile.
        lifecycleScope.launch {
            val dest = resolveDestination(uri)
            if (dest == null) {
                NavigationController.reportUnresolved("Couldn't find that destination")
                return@launch
            }
            NavigationController.navigateTo(carContext, dest.lat, dest.lon, dest.label)
        }
        return true
    }

    private data class Dest(val lat: Double, val lon: Double, val label: String?)

    /** Best-effort human label from a `geo:` URI's `q` parameter, for the
     *  immediate "Finding …" banner. Strips a trailing `(label)` coord
     *  annotation and any bare lat,lng so the banner reads naturally. */
    private fun searchLabel(uri: Uri): String? {
        val query = uri.schemeSpecificPart?.substringAfter('?', "") ?: return null
        val q = query.split('&')
            .firstOrNull { it.startsWith("q=") }
            ?.substringAfter("q=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.substringBefore('(')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        // A bare "lat,lng" isn't a friendly label — skip it.
        return if (parseLatLng(q) != null) null else q
    }

    /**
     * Parse a `geo:` URI per the Assistant navigation-intent contract:
     *   - `geo:lat,lng` (optionally `?q=label&mode=…&intent=…`)
     *   - `geo:0,0?q=<free-form address or place>`
     *   - `geo:0,0?q=lat,lng(label)`
     * Coordinates in the path win when non-zero; otherwise the `q`
     * parameter is used — as coordinates if it parses as such, else
     * geocoded to the top hit.
     */
    private suspend fun resolveDestination(uri: Uri): Dest? {
        // geo: URIs are opaque (scheme:part), so Uri.getQueryParameter
        // doesn't work — split the scheme-specific part by hand.
        val ssp = uri.schemeSpecificPart ?: return null
        val pathPart = ssp.substringBefore('?')
        val query = ssp.substringAfter('?', "")
        val q = query.split('&')
            .firstOrNull { it.startsWith("q=") }
            ?.substringAfter("q=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        // 1. Non-zero coordinates in the path.
        parseLatLng(pathPart)?.let { (lat, lon) ->
            if (lat != 0.0 || lon != 0.0) return Dest(lat, lon, q?.substringBefore('(')?.trim())
        }
        if (q == null) return null

        // 2. q itself as "lat,lng" or "lat,lng(label)".
        parseLatLng(q.substringBefore('('))?.let { (lat, lon) ->
            val label = q.substringAfter('(', "").substringBefore(')').ifBlank { null }
            return Dest(lat, lon, label)
        }

        // 3. Free-form query → geocode.
        val outcome = GeocodingRepository(carContext).search(q)
        val hit = (outcome as? GeocodingRepository.SearchOutcome.Hits)
            ?.results?.firstOrNull() ?: return null
        return Dest(hit.lat, hit.lon, hit.label)
    }

    /** "lat,lng" → coordinate pair, validated to earth bounds. */
    private fun parseLatLng(s: String): Pair<Double, Double>? {
        val parts = s.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
