package be.appmire.gpsinfo.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single place that decides how default trail names and waypoint names
 * are stamped with the current wall-clock time. Previously this format
 * lived as inline `SimpleDateFormat(...)` constructions in two different
 * call sites in the dashboard, which drifted over time.
 *
 * Format is intentionally `Locale.US` + ASCII separators so the resulting
 * filename (after the repository's slug step) is sortable and portable.
 */
object TrailNaming {

    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm"

    /** Returns "Trail yyyy-MM-dd HH:mm" for the given epoch millis. */
    fun defaultTrailName(epochMillis: Long): String =
        "Trail " + formatter().format(Date(epochMillis))

    /** Returns "<prefix> @ yyyy-MM-dd HH:mm" — used for waypoint names. */
    fun timestamped(prefix: String, epochMillis: Long): String =
        "$prefix @ " + formatter().format(Date(epochMillis))

    private fun formatter(): SimpleDateFormat =
        SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
}
