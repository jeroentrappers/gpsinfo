package be.appmire.gpsinfo.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import be.appmire.gpsinfo.R

/**
 * Car-side "hamburger" menu — the single entry point the map's Menu
 * action opens, mirroring the phone's slide-up menu. Keeps the map
 * screen's own action strip lean (search + alternatives + one
 * contextual slot) by parking the less-frequent destinations here:
 * free-form search, the saved/recent places picker, and the recorded
 * trails browser. A plain [ListTemplate] with a back header — the only
 * chrome the template allows, and the standard car list pattern.
 */
class CarMenuScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_menu_search))
                    .setImage(carIcon(R.drawable.ic_car_search))
                    .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_places_title))
                    .setImage(carIcon(R.drawable.ic_car_places))
                    .setOnClickListener { screenManager.push(PlacesScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_recent_title))
                    .setImage(carIcon(R.drawable.ic_car_trails))
                    .setOnClickListener { screenManager.push(RecentTrailsScreen(carContext)) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_menu_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
