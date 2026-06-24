package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.data.nav.PlaceRole
import be.appmire.gpsinfo.data.nav.PlacesRepository
import be.appmire.gpsinfo.data.nav.SavedPlace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Car-side destination picker: the saved + recent places the user
 * built up on the phone, selectable with a single tap — no keyboard
 * (text entry isn't allowed while driving anyway). Tapping a place
 * starts offline turn-by-turn navigation to it and returns to the map.
 *
 * Sectioned: Home / Work / labelled pinned on top ("Saved"), then the
 * recents newest-first. A header action opens the recorded-trails
 * browser, which used to own this strip slot.
 */
class PlacesScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private var places: List<SavedPlace> = emptyList()
    private var collectJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        collectJob = PlacesRepository(carContext.applicationContext).places
            .onEach {
                places = it
                invalidate()
            }
            .launchIn(owner.lifecycleScope)
    }

    override fun onStop(owner: LifecycleOwner) {
        collectJob?.cancel()
        collectJob = null
    }

    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(6).coerceAtLeast(2)

        val builder = ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_places_title))
            .setHeaderAction(Action.BACK)
            // Search (free-form address/place entry) + recorded trails.
            // Both icon-only — the ListTemplate strip (SIMPLE constraints)
            // takes two actions and no custom titles.
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(carIcon(R.drawable.ic_car_search))
                            .setOnClickListener {
                                screenManager.push(SearchScreen(carContext))
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(carIcon(R.drawable.ic_car_trails))
                            .setOnClickListener {
                                screenManager.push(RecentTrailsScreen(carContext))
                            }
                            .build()
                    )
                    .build()
            )

        if (places.isEmpty()) {
            builder.setSingleList(
                ItemList.Builder()
                    .setNoItemsMessage(carContext.getString(R.string.car_places_empty))
                    .build()
            )
            return builder.build()
        }

        val saved = places.filter { it.role != PlaceRole.RECENT }
        val recent = places.filter { it.role == PlaceRole.RECENT }

        if (saved.isNotEmpty()) {
            builder.addSectionedList(
                SectionedItemList.create(
                    listFrom(saved, limit),
                    carContext.getString(R.string.car_places_section_saved),
                )
            )
        }
        if (recent.isNotEmpty()) {
            // Share the host's row budget across both sections.
            val remaining = (limit - saved.size).coerceAtLeast(1)
            builder.addSectionedList(
                SectionedItemList.create(
                    listFrom(recent, remaining),
                    carContext.getString(R.string.car_places_section_recent),
                )
            )
        }
        return builder.build()
    }

    private fun listFrom(items: List<SavedPlace>, limit: Int): ItemList {
        val b = ItemList.Builder()
        items.take(limit).forEach { place ->
            b.addItem(
                Row.Builder()
                    .setTitle(place.displayTitle)
                    .apply {
                        val sub = listOf(place.name, place.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .takeIf { place.role != PlaceRole.RECENT && it.isNotBlank() }
                            ?: place.detail
                        if (sub.isNotBlank()) addText(sub)
                    }
                    .setOnClickListener { drive(place) }
                    .build()
            )
        }
        return b.build()
    }

    private fun drive(place: SavedPlace) {
        // Show the route chooser (fastest/shortest/economic); it navigates
        // directly when offline / no alternatives.
        screenManager.push(
            RouteChoiceScreen(carContext, place.lat, place.lon, place.displayTitle, place.detail),
        )
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
