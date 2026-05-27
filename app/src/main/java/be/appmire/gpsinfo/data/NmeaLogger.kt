package be.appmire.gpsinfo.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Raw NMEA-sentence logger. Diagnostic feature for power users
 * debugging GPS chip behaviour. Off by default; the user opts in via
 * Settings, and the service plumbs through. Files live under
 * `cacheDir/nmea/<timestamp>.nmea` — wiped on uninstall, surfaceable
 * via `adb pull` or any system file manager pointed at the app's
 * external-data dir if the user later wants them.
 *
 * Output format is one sentence per line with an epoch-millis prefix:
 *   `1738954201234 $GPGGA,121501.00,5108.123,N,00422.456,E,...`
 *
 * The prefix lets a downstream analysis tool replay sentences at
 * their original cadence — vanilla NMEA doesn't carry sub-second
 * wall-clock, only HHMMSS within $GPRMC / $GPGGA.
 */
class NmeaLogger(private val context: Context) {

    private val lm: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nmea-logger").apply { isDaemon = true }
    }
    private var writer: BufferedWriter? = null
    private var listener: OnNmeaMessageListener? = null
    private var currentFile: File? = null

    /**
     * Begin logging. No-op if [start] has already been called or if
     * the fine-location permission isn't granted. Returns the file
     * path when logging started, null otherwise.
     */
    @SuppressLint("MissingPermission") // explicit permission check below
    fun start(): File? {
        if (listener != null) return currentFile
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        val dir = File(context.cacheDir, "nmea").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(Date(System.currentTimeMillis()))
        val out = File(dir, "$stamp.nmea")
        currentFile = out
        writer = BufferedWriter(FileWriter(out, /* append = */ false))

        val l = OnNmeaMessageListener { message, timestamp ->
            // Each onNmeaMessage callback delivers one complete
            // sentence (already line-terminated by the chip). Strip
            // any trailing newline so our own `\n` is the only
            // separator.
            val clean = message.trimEnd('\r', '\n')
            try {
                writer?.run {
                    write(timestamp.toString())
                    write(" ")
                    write(clean)
                    newLine()
                }
            } catch (_: Exception) {
                // Disk full / file deleted under us — drop this
                // sentence and keep going. Reliability of
                // diagnostic logging is best-effort.
            }
        }
        try {
            lm.addNmeaListener(executor, l)
            listener = l
        } catch (_: SecurityException) {
            // Permission revoked between the explicit check above
            // and the listener registration. Drop everything.
            writer?.runCatching { close() }
            writer = null
            currentFile = null
            return null
        }
        return out
    }

    /** Stop logging and close the file. Safe to call when not
     *  started. */
    fun stop() {
        listener?.let {
            try { lm.removeNmeaListener(it) } catch (_: Exception) {}
        }
        listener = null
        writer?.runCatching {
            flush()
            close()
        }
        writer = null
        currentFile = null
    }
}
