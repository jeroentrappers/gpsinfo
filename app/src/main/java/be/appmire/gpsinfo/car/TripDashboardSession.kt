package be.appmire.gpsinfo.car

import androidx.car.app.Screen
import androidx.car.app.Session
import android.content.Intent

/**
 * One Session per head-unit projection cycle. Returns the live
 * trip-dashboard screen — there's only one root screen for now, with
 * recording controls living inside it as an action strip. A future
 * Recent Trails browser (parked-only) would push another Screen onto
 * the stack from here.
 */
class TripDashboardSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = TripDashboardScreen(carContext)
}
