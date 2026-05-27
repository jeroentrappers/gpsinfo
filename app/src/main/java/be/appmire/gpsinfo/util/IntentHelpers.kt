package be.appmire.gpsinfo.util

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import be.appmire.gpsinfo.R
import java.io.File
import java.util.Locale

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
    fun shareLocation(
        context: Context,
        latDeg: Double,
        lonDeg: Double,
        altMeters: Double?,
        navContextLine: String? = null,
        batteryPct: Int? = null,
    ) {
        val dms = CoordinateFormatter.format(latDeg, lonDeg, CoordinateFormat.DMS) as FormattedCoord.Pair
        val mapsUrl = "https://www.google.com/maps?q=$latDeg,$lonDeg"
        val geoUri = "geo:$latDeg,$lonDeg?q=$latDeg,$lonDeg(My location)"

        val payload = buildString {
            append("${dms.lat}, ${dms.lon}\n")
            // Locale.ROOT — this line is technical coordinate data, not a
            // localised display, so we want "51.130203" everywhere even on
            // comma-decimal locales.
            append("%.6f, %.6f".format(Locale.ROOT, latDeg, lonDeg))
            if (altMeters != null) append("\nAltitude: ${altMeters.toInt()} m")
            if (navContextLine != null) append("\n").append(navContextLine)
            if (batteryPct != null) append("\nBattery: ").append(batteryPct).append(" %")
            // Timestamp lets the recipient know how fresh this is —
            // critical for "I'm here right now" semantics on a phone
            // that might be passed around.
            val ts = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            append("\nAt: ").append(ts)
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
        val geoUri = "geo:$latDeg,$lonDeg?q=$latDeg,$lonDeg(My location)".toUri()
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
        val webUri = "https://www.openstreetmap.org/?mlat=$latDeg&mlon=$lonDeg#map=17/$latDeg/$lonDeg".toUri()
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
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Opens this app's Play Store listing so the user can leave a rating.
     *
     * Tries the Play Store app directly via the `market://` scheme; on a
     * Play-less device (or a sideloaded build) that throws, so we fall back
     * to the https listing in whatever browser the user has. Deliberately a
     * plain intent — no Play Core / In-App-Review dependency, which keeps the
     * app's no-Play-Services stance intact (and the In-App-Review flow can't
     * ask for "5 stars" anyway).
     */
    fun openPlayStoreListing(context: Context) {
        val pkg = context.packageName
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$pkg".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Puts the given text on the system clipboard, labelled for the
     * clipboard manager UI. Android 13+ shows a system toast on copy
     * automatically, so we deliberately don't surface our own.
     */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /**
     * Wraps the given GPX file in a `content://` URI via FileProvider and
     * fires the system share sheet. The receiving app gets read-only
     * access for the duration of the intent — we never grant a path,
     * only a URI.
     */
    fun shareGpx(context: Context, file: File, trailName: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, trailName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.trail_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Same wrapper as [shareGpx] but for Garmin FIT binary files. MIME
     * type is `application/vnd.ant.fit` — what Garmin Connect, Strava
     * and TrainingPeaks all advertise their FIT importers under.
     */
    fun shareFit(context: Context, file: File, trailName: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ant.fit"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, trailName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.trail_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
