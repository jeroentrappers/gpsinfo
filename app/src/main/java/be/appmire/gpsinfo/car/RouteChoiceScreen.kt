package be.appmire.gpsinfo.car

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.lifecycleScope
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.nav.NavigationController
import be.appmire.gpsinfo.data.nav.OfflineRoute
import be.appmire.gpsinfo.data.nav.RouteOption
import be.appmire.gpsinfo.data.nav.RouteProfile
import kotlinx.coroutines.launch

/**
 * Route preview / chooser: presents the fastest / shortest / economic
 * alternatives (computed online via Valhalla — see nav-engine-v2) for the
 * driver to pick, then starts navigation with the chosen profile.
 *
 * If no alternatives come back (offline, or before the first GPS fix), it
 * skips the chooser and navigates directly — BRouter handles the offline
 * case in [NavigationController.navigateTo].
 */
class RouteChoiceScreen(
    carContext: CarContext,
    private val destLat: Double,
    private val destLon: Double,
    private val destName: String?,
    private val destDetail: String,
) : Screen(carContext) {

    /** null = still computing; empty handled by routing direct (see init). */
    private var options: List<RouteOption>? = null
    private var selected = 0

    init {
        lifecycleScope.launch {
            val opts = NavigationController.previewOptions(destLat, destLon)
            if (opts.isEmpty()) {
                // No online alternatives (offline / no fix yet) — go straight
                // to navigation; BRouter routes offline.
                NavigationController.navigateTo(carContext, destLat, destLon, destName, destDetail)
                screenManager.popToRoot()
            } else {
                options = opts
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val opts = options
        if (opts == null) {
            return RoutePreviewNavigationTemplate.Builder()
                .setTitle(carContext.getString(R.string.car_routes_title))
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }
        val list = ItemList.Builder()
            .setOnSelectedListener { index -> selected = index }
        opts.forEach { o ->
            list.addItem(
                Row.Builder()
                    .setTitle(profileLabel(o.profile))
                    .addText(summary(o.route))
                    .build(),
            )
        }
        return RoutePreviewNavigationTemplate.Builder()
            .setTitle(carContext.getString(R.string.car_routes_title))
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .setNavigateAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_action_go))
                    .setOnClickListener {
                        val chosen = opts.getOrElse(selected) { opts.first() }
                        NavigationController.navigateTo(
                            carContext, destLat, destLon, destName, destDetail, chosen.profile,
                        )
                        screenManager.popToRoot()
                    }
                    .build(),
            )
            .build()
    }

    private fun profileLabel(profile: RouteProfile): String = carContext.getString(
        when (profile) {
            RouteProfile.FASTEST -> R.string.car_route_fastest
            RouteProfile.SHORTEST -> R.string.car_route_shortest
            RouteProfile.ECONOMIC -> R.string.car_route_economic
        },
    )

    /** Row text with the required distance + duration spans. The template host
     *  renders each span over its placeholder char, so the string reads
     *  "<distance> · <duration>". A [RoutePreviewNavigationTemplate] rejects any
     *  row lacking one of these spans, so this is mandatory, not cosmetic. */
    private fun summary(route: OfflineRoute): CharSequence {
        val distance = if (route.distanceMeters >= 1000) {
            Distance.create(route.distanceMeters / 1000.0, Distance.UNIT_KILOMETERS)
        } else {
            Distance.create(route.distanceMeters.toDouble(), Distance.UNIT_METERS)
        }
        val s = SpannableString("d · t")
        s.setSpan(DistanceSpan.create(distance), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        s.setSpan(DurationSpan.create(route.durationSeconds.toLong()), 4, 5, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return s
    }
}
