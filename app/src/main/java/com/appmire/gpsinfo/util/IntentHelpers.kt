package com.appmire.gpsinfo.util

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import com.appmire.gpsinfo.R

object IntentHelpers {

    /**
     * Builds a share payload of the form:
     *
     *     51°07'48.730" N, 4°22'39.859" E
     *     51.130203, 4.377739
     *     Altitude: 45 m
     *
     *     Open in maps:
     *     https://www.google.com/maps?q=51.130203,4.377739
     *     geo:51.130203,4.377739?q=51.130203,4.377739(My location)
     *
     * The https URL is clickable in any messaging app and triggers Android's
     * "Open with…" chooser among installed map apps. The geo: URI is the
     * native Android scheme — tapping it pops the map-app chooser directly
     * (for clients that respect the scheme).
     */
    fun shareLocation(context: Context, latDeg: Double, lonDeg: Double, altMeters: Double?) {
        val dms = CoordinateFormatter.format(latDeg, lonDeg, CoordinateFormat.DMS)
        val mapsUrl = "https://www.google.com/maps?q=$latDeg,$lonDeg"
        val geoUri = "geo:$latDeg,$lonDeg?q=$latDeg,$lonDeg(My location)"

        val payload = buildString {
            append("${dms.lat}, ${dms.lon}\n")
            append("%.6f, %.6f".format(latDeg, lonDeg))
            if (altMeters != null) append("\nAltitude: ${altMeters.toInt()} m")
            append("\n\nOpen in maps:\n")
            append(mapsUrl).append('\n')
            append(geoUri)
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, payload)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.action_share_location))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Opens the current position with the user's chosen map app.
     *
     * Uses ACTION_VIEW on a `geo:` URI inside an explicit chooser so that
     * even if the user has set a default map app, the system still shows
     * all installed candidates. Falls back to a web maps URL when no app
     * on the device handles `geo:`.
     */
    fun openInMaps(context: Context, latDeg: Double, lonDeg: Double) {
        val geoUri = Uri.parse("geo:$latDeg,$lonDeg?q=$latDeg,$lonDeg(My location)")
        val viewIntent = Intent(Intent.ACTION_VIEW, geoUri)

        val handlers: List<ResolveInfo> =
            context.packageManager.queryIntentActivities(viewIntent, 0)

        if (handlers.isNotEmpty()) {
            val chooser = Intent.createChooser(
                viewIntent, context.getString(R.string.action_open_maps)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            return
        }

        // No native geo: handler — fall back to a web URL that any browser
        // (and many map apps via their own intent-filters) will accept.
        val webUri = Uri.parse(
            "https://www.openstreetmap.org/?mlat=$latDeg&mlon=$lonDeg#map=17/$latDeg/$lonDeg"
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Opens an arbitrary URL with the user's preferred browser/app chooser. */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
