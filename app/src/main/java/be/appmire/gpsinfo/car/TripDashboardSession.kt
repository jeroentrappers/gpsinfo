package be.appmire.gpsinfo.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * One Session per head-unit projection cycle. Owns the [CarMapRenderer]
 * — the surface callback registration is per-CarContext, so it must
 * outlive screen pushes/pops (RecentTrails draws its template over the
 * same surface) — and hands it to the root trip-dashboard screen.
 */
class TripDashboardSession : Session() {

    private var renderer: CarMapRenderer? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val mapRenderer = CarMapRenderer(carContext, lifecycle)
        renderer = mapRenderer
        return TripDashboardScreen(carContext, mapRenderer)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        // Day/night flips arrive here — repaint so the map scrim and
        // HUD palette follow the head unit immediately.
        renderer?.repaint()
    }
}
