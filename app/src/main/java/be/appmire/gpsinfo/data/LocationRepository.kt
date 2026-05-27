package be.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import be.appmire.gpsinfo.data.model.Constellation
import be.appmire.gpsinfo.data.model.FixStatus
import be.appmire.gpsinfo.data.model.GnssSnapshot
import be.appmire.gpsinfo.data.model.SatelliteInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps the rawest public Android GNSS surface:
 *   - [LocationManager.GPS_PROVIDER] for fixes (no Play Services dependency).
 *   - [GnssStatus.Callback] for per-satellite az/el/SNR/used-in-fix.
 *
 * Exposes a single Flow<GnssSnapshot> the ViewModel can collect.
 *
 * Notes on what is intentionally NOT here:
 *   - `GnssMeasurementsEvent.Callback` was previously registered as a no-op
 *     placeholder for "future raw pseudorange UI." It kept the GNSS
 *     measurement engine on at high cost for zero UI value; removed until
 *     the feature is actually built.
 *   - We don't use FusedLocationProvider to keep the app Play-Services-free.
 *     Trade-off: cold-start indoors is slower. The repository seeds with
 *     `getLastKnownLocation(NETWORK_PROVIDER)` before the first GPS fix so
 *     the world-map marker lands somewhere plausible immediately.
 */
class LocationRepository(private val context: Context) : LocationDataSource {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /** Whether the system Location toggle is on. Surface this on the UI so
     *  the user gets a "Location services are disabled" prompt instead of
     *  staring at a perpetual NO_FIX. */
    override fun isLocationEnabled(): Boolean =
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    @SuppressLint("MissingPermission")
    override fun snapshots(): Flow<GnssSnapshot> = callbackFlow {
        if (!hasFineLocationPermission()) {
            trySend(GnssSnapshot())
            awaitClose { }
            return@callbackFlow
        }

        val mainHandler = Handler(Looper.getMainLooper())
        // GPS and NETWORK are tracked separately so a coarse network fix
        // can't briefly overwrite a fresh, accurate GPS fix. When both
        // exist, [bestLocation] prefers GPS while it's fresh and falls
        // back to NETWORK once GPS goes stale — that's what keeps the
        // dashboard useful indoors when only NETWORK is available.
        var latestGpsLocation: Location? = null
        var latestNetworkLocation: Location? = null
        var latestSatellites: List<SatelliteInfo> = emptyList()
        var firstFixMillis: Long? = null

        fun bestLocation(): Location? {
            val gps = latestGpsLocation
            val net = latestNetworkLocation
            if (gps == null) return net
            if (net == null) return gps
            val gpsAgeNanos = SystemClock.elapsedRealtimeNanos() - gps.elapsedRealtimeNanos
            return if (gpsAgeNanos < GPS_FRESH_NANOS) gps else net
        }

        fun emit() {
            val loc = bestLocation()
            val fix = computeFix(loc, latestSatellites)
            if (fix != FixStatus.NO_FIX && firstFixMillis == null) {
                firstFixMillis = System.currentTimeMillis()
            }
            trySend(
                GnssSnapshot(
                    location = loc,
                    fix = fix,
                    satellites = latestSatellites,
                    firstFixMillis = firstFixMillis,
                    lastUpdateElapsedRealtime = SystemClock.elapsedRealtime()
                )
            )
        }

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                when (location.provider) {
                    LocationManager.NETWORK_PROVIDER -> latestNetworkLocation = location
                    else -> latestGpsLocation = location  // GPS_PROVIDER (default)
                }
                emit()
            }
            @Deprecated("Required for older API levels")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = emit()
            override fun onProviderDisabled(provider: String) {
                when (provider) {
                    LocationManager.GPS_PROVIDER -> latestGpsLocation = null
                    LocationManager.NETWORK_PROVIDER -> latestNetworkLocation = null
                }
                emit()
            }
        }

        val gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                latestSatellites = status.toSatelliteList()
                emit()
            }
            override fun onFirstFix(ttffMillis: Int) {
                if (firstFixMillis == null) firstFixMillis = System.currentTimeMillis()
            }
        }

        // Register both providers when available. NETWORK_PROVIDER ticks
        // at ~1 Hz at most so we ask for it less aggressively than GPS.
        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            // Permission revoked between check and use — listeners stay
            // unregistered, flow closes via awaitClose when collector cancels.
        } catch (_: IllegalArgumentException) {
            // Provider not available on this device.
        }
        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1_000L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            // Same as above.
        } catch (_: IllegalArgumentException) {
            // No network-location provider on this device.
        }

        lm.registerGnssStatusCallback(gnssStatusCallback, mainHandler)

        // Seed: prefer the most-recent GPS fix; otherwise fall back to a
        // network-cached coarse fix so the world-map pin lands somewhere
        // plausible before the GPS first-fix lands.
        latestGpsLocation =
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        latestNetworkLocation =
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        if (latestGpsLocation != null || latestNetworkLocation != null) emit()

        awaitClose {
            // removeUpdates(listener) detaches it from every provider it
            // was registered against.
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(gnssStatusCallback)
        }
    }

    private fun GnssStatus.toSatelliteList(): List<SatelliteInfo> =
        (0 until satelliteCount).map { idx ->
            val freq = if (android.os.Build.VERSION.SDK_INT >= 26 &&
                hasCarrierFrequencyHz(idx)
            ) getCarrierFrequencyHz(idx) else 0f
            SatelliteInfo(
                svid = getSvid(idx),
                constellation = mapConstellation(getConstellationType(idx)),
                azimuthDeg = getAzimuthDegrees(idx),
                elevationDeg = getElevationDegrees(idx),
                cn0DbHz = getCn0DbHz(idx),
                usedInFix = usedInFix(idx),
                hasEphemeris = hasEphemerisData(idx),
                hasAlmanac = hasAlmanacData(idx),
                carrierFrequencyHz = freq,
            )
        }.sortedWith(
            compareBy({ it.constellation.ordinal }, { -it.cn0DbHz })
        )

    private fun mapConstellation(type: Int): Constellation = when (type) {
        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
        GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
        GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
        GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
        else -> Constellation.UNKNOWN
    }

    /**
     * Derive a fix-type from the [Location] + tracked satellites.
     *
     * Android's [Location] API doesn't expose a "2D vs 3D" field, so we
     * approximate:
     *   - No location at all OR location older than 10 s → [FixStatus.NO_FIX]
     *   - <3 satellites used → [FixStatus.NO_FIX]
     *   - Altitude present AND ≥4 satellites used → [FixStatus.THREE_D]
     *   - Otherwise → [FixStatus.TWO_D]
     *
     * The 10-second freshness window is intentional: at speed the receiver
     * updates rapidly, while stationary it can occasionally idle for a few
     * seconds without that meaning "lost fix."
     */
    private fun computeFix(loc: Location?, sats: List<SatelliteInfo>): FixStatus {
        if (loc == null) return FixStatus.NO_FIX
        val used = sats.count { it.usedInFix }
        val fresh = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) < 10_000_000_000L
        if (!fresh || used < 3) return FixStatus.NO_FIX
        return if (loc.hasAltitude() && used >= 4) FixStatus.THREE_D else FixStatus.TWO_D
    }

    private companion object {
        /** How long a GPS fix stays preferred over a network fix. After
         *  this window we fall back to NETWORK if it has something fresher.
         *  30 s mirrors the practical "I just walked indoors" timescale. */
        const val GPS_FRESH_NANOS = 30_000_000_000L
    }
}
