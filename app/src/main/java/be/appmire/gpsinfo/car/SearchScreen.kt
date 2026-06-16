package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.nav.GeocodeResult
import be.appmire.gpsinfo.data.nav.GeocodingRepository
import be.appmire.gpsinfo.data.nav.NavigationController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-car destination search: a [SearchTemplate] backed by the Photon
 * geocoder ([GeocodingRepository]). The host owns the keyboard and gates
 * it on driving state (text entry is offered parked, suppressed while
 * moving) — using SearchTemplate is the policy-correct way to take a
 * free-form destination, complementing the tap-only saved/recent picker
 * in [PlacesScreen].
 *
 * Picking a result starts offline turn-by-turn through
 * [NavigationController] and returns to the map. Results are biased to
 * the driver's last known position so short queries ("aldi", "shell")
 * resolve nearby first; a query with no connection and nothing cached
 * surfaces a "no connection" hint rather than an error.
 */
class SearchScreen(carContext: CarContext) : Screen(carContext) {

    private val geocoder = GeocodingRepository(carContext.applicationContext)
    private var results: List<GeocodeResult> = emptyList()
    private var loading = false
    private var message: String? = null
    private var searchJob: Job? = null

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) = runSearch(searchText, immediate = false)
        override fun onSearchSubmitted(searchText: String) = runSearch(searchText, immediate = true)
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .setSearchHint(carContext.getString(R.string.car_search_hint))
        if (loading) {
            builder.setLoading(true)
        } else {
            builder.setItemList(resultList())
        }
        return builder.build()
    }

    private fun resultList(): ItemList {
        val b = ItemList.Builder()
        if (results.isEmpty()) {
            b.setNoItemsMessage(message ?: carContext.getString(R.string.car_search_prompt))
            return b.build()
        }
        results.forEach { r ->
            b.addItem(
                Row.Builder()
                    .setTitle(r.label)
                    .apply { if (r.detail.isNotBlank()) addText(r.detail) }
                    .setOnClickListener { drive(r) }
                    .build()
            )
        }
        return b.build()
    }

    /** Geocode [query] off the main thread. Keystroke-driven calls debounce
     *  briefly so we don't fire a request per character; a submit runs at
     *  once. Sub-threshold queries clear the list back to the prompt. */
    private fun runSearch(query: String, immediate: Boolean) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < MIN_QUERY_LEN) {
            results = emptyList()
            message = null
            loading = false
            invalidate()
            return
        }
        searchJob = lifecycleScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            loading = true
            invalidate()
            val bias = NavigationController.lastKnownLatLon
            when (val outcome = geocoder.search(q, bias?.first, bias?.second)) {
                is GeocodingRepository.SearchOutcome.Hits -> {
                    results = outcome.results
                    message = null
                }
                GeocodingRepository.SearchOutcome.Empty -> {
                    results = emptyList()
                    message = carContext.getString(R.string.car_search_empty)
                }
                GeocodingRepository.SearchOutcome.Offline -> {
                    results = emptyList()
                    message = carContext.getString(R.string.car_search_offline)
                }
            }
            loading = false
            invalidate()
        }
    }

    private fun drive(r: GeocodeResult) {
        NavigationController.navigateTo(
            carContext, r.lat, r.lon, destName = r.label, destDetail = r.detail,
        )
        // Back to the map to watch guidance.
        screenManager.popToRoot()
    }

    private companion object {
        const val MIN_QUERY_LEN = 3
        const val DEBOUNCE_MS = 350L
    }
}
