package be.appmire.gpsinfo.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Android Auto projection-mode integration.
 *
 * Hosted by the system Android Auto app on the phone (or a compatible
 * third-party head-unit host) — no Google Play Services dependency.
 * We don't ship a separate `:auto` gradle module: the Car App Library
 * itself is the entire delta, and one app module keeps shared
 * `LocationRepository`/`TrailRecordingController` usage trivial.
 *
 * Host validation: we allow the small set of Google-published AA hosts
 * (production, beta, dev). Add custom OEM signatures here if/when a
 * specific aftermarket head unit needs to be allow-listed.
 */
class GpsInfoCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(sessionInfo: SessionInfo): Session = TripDashboardSession()
}
