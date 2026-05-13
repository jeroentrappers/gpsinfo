package com.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.appmire.gpsinfo.data.model.Constellation
import com.appmire.gpsinfo.data.model.FixStatus
import com.appmire.gpsinfo.data.model.GnssSnapshot
import com.appmire.gpsinfo.data.model.SatelliteInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps the rawest public Android GNSS surface:
 *   - LocationManager GPS provider (fused not used to avoid Play Services dep)
 *   - GnssStatus.Callback for per-satellite az/el/SNR/used-in-fix
 *   - GnssMeasurementsEvent.Callback for raw pseudorange (logged, future UI)
 *
 * Exposes a single Flow<GnssSnapshot> that the ViewModel can collect.
 */
class LocationRepository(private val context: Context) {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _firstFix = MutableStateFlow<Long?>(null)
    val firstFix = _firstFix.asStateFlow()

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun snapshots(): Flow<GnssSnapshot> = callbackFlow {
        if (!hasFineLocationPermission()) {
            trySend(GnssSnapshot())
            awaitClose { }
            return@callbackFlow
        }

        var latestLocation: Location? = null
        var latestSatellites: List<SatelliteInfo> = emptyList()

        fun emit() {
            val loc = latestLocation
            val fix = computeFix(loc, latestSatellites)
            if (fix != FixStatus.NO_FIX && _firstFix.value == null) {
                _firstFix.value = System.currentTimeMillis()
            }
            trySend(
                GnssSnapshot(
                    location = loc,
                    fix = fix,
                    satellites = latestSatellites,
                    firstFixMillis = _firstFix.value,
                    lastUpdateElapsedRealtime = SystemClock.elapsedRealtime()
                )
            )
        }

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestLocation = location
                emit()
            }
            @Deprecated("Required for older API levels")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = emit()
            override fun onProviderDisabled(provider: String) {
                latestLocation = null
                emit()
            }
        }

        val gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                latestSatellites = status.toSatelliteList()
                emit()
            }
            override fun onFirstFix(ttffMillis: Int) {
                if (_firstFix.value == null) _firstFix.value = System.currentTimeMillis()
            }
        }

        val measurementCallback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                // Raw pseudorange / carrier phase available here.
                // v1: not surfaced in UI — receiving the callback exercises the pipeline
                // so we keep it cheap and intentionally do nothing.
            }
        }

        try {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
                0f,
                locationListener,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            // permission revoked between check and use — ignore, callbackFlow will close
        } catch (_: IllegalArgumentException) {
            // provider not available
        }

        lm.registerGnssStatusCallback(gnssStatusCallback, mainHandler)
        lm.registerGnssMeasurementsCallback(measurementCallback, mainHandler)

        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
            latestLocation = it
            emit()
        }

        awaitClose {
            lm.removeUpdates(locationListener)
            lm.unregisterGnssStatusCallback(gnssStatusCallback)
            lm.unregisterGnssMeasurementsCallback(measurementCallback)
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

    private fun computeFix(loc: Location?, sats: List<SatelliteInfo>): FixStatus {
        if (loc == null) return FixStatus.NO_FIX
        val used = sats.count { it.usedInFix }
        val fresh = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) < 10_000_000_000L
        if (!fresh || used < 3) return FixStatus.NO_FIX
        return if (loc.hasAltitude() && used >= 4) FixStatus.THREE_D else FixStatus.TWO_D
    }
}
