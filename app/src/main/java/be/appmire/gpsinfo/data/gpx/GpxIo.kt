package be.appmire.gpsinfo.data.gpx

import android.util.Xml
import be.appmire.gpsinfo.data.model.LapMarker
import be.appmire.gpsinfo.data.model.Trail
import be.appmire.gpsinfo.data.model.TrailPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GPX 1.1 serializer + parser, scoped to what GPSinfo records.
 *
 * Speed and course don't have first-class slots in GPX 1.1 — they live
 * under Garmin's `TrackPointExtension v2` namespace, which Strava, Garmin
 * Connect, OsmAnd and friends all recognise. Vertical accuracy isn't in
 * either schema; we put it under a private `gpsinfo` namespace so files
 * survive a round-trip through this app but won't carry that field
 * elsewhere.
 *
 * Times are ISO-8601 in UTC (the only timezone GPX accepts).
 */
internal object GpxIo {

    private const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    private const val NS_GPXTPX = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
    // Garmin Cycling Power extension — `gpxpx:PowerInWatts` is the
    // de-facto standard slot for per-point cycling power that Strava
    // and Garmin Connect both read. Same shape as TrackPointExtension
    // but lives in its own namespace so older parsers can ignore it.
    private const val NS_GPXPX = "http://www.garmin.com/xmlschemas/PowerExtension/v1"
    private const val NS_GPSINFO = "https://appmire.be/gpsinfo/1"

    private fun isoFormat(): SimpleDateFormat {
        // Locale.US so digit grouping never produces "1,234" instead of
        // "1234"; explicit UTC so the timestamps are tool-portable.
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun write(trail: Trail, sink: OutputStream) {
        val fmt = isoFormat()
        val w = OutputStreamWriter(sink, Charsets.UTF_8)
        val s = Xml.newSerializer()
        s.setOutput(w)
        s.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        s.startDocument("UTF-8", true)

        s.setPrefix("", NS_GPX)
        s.setPrefix("gpxtpx", NS_GPXTPX)
        s.setPrefix("gpxpx", NS_GPXPX)
        s.setPrefix("gpsinfo", NS_GPSINFO)

        s.startTag(NS_GPX, "gpx")
        s.attribute(null, "version", "1.1")
        s.attribute(null, "creator", "GPSinfo (appmire.be)")

        s.startTag(NS_GPX, "metadata")
        s.simpleTag(NS_GPX, "name", trail.name)
        trail.startTimeMillis?.let {
            s.simpleTag(NS_GPX, "time", fmt.format(Date(it)))
        }
        if (trail.tags.isNotEmpty()) {
            // GPX 1.1 standard slot for free-form categorisation. We
            // sanitise commas out of each tag since they're the
            // separator — see the parse path.
            val joined = trail.tags
                .map { it.replace(",", " ") }
                .joinToString(", ")
            s.simpleTag(NS_GPX, "keywords", joined)
        }
        s.endTag(NS_GPX, "metadata")

        s.startTag(NS_GPX, "trk")
        s.simpleTag(NS_GPX, "name", trail.name)
        // Trail-level target pace lives in a custom GPSinfo namespace.
        // Other tools ignore unknown extensions but typically preserve
        // them on re-save, so a GPX edited in Strava and re-imported
        // here keeps its target.
        if (trail.targetPaceSecondsPerKm != null || trail.laps.isNotEmpty()) {
            s.startTag(NS_GPX, "extensions")
            trail.targetPaceSecondsPerKm?.let { target ->
                s.simpleTag(NS_GPSINFO, "target_pace_s_per_km", "%.3f".format(Locale.US, target))
            }
            for (lap in trail.laps) {
                // Attribute-based for tabular data — half the bytes of
                // nested simple-tag children, and the parser stays trivial.
                s.startTag(NS_GPSINFO, "lap")
                s.attribute(null, "n", lap.index.toString())
                s.attribute(null, "time", fmt.format(Date(lap.timeMillis)))
                s.attribute(null, "cum_distance_m", "%.2f".format(Locale.US, lap.cumulativeDistanceM))
                s.attribute(null, "lap_distance_m", "%.2f".format(Locale.US, lap.lapDistanceM))
                s.attribute(null, "lap_duration_ms", lap.lapDurationMs.toString())
                lap.avgHrBpm?.let { s.attribute(null, "avg_hr", it.toString()) }
                s.endTag(NS_GPSINFO, "lap")
            }
            s.endTag(NS_GPX, "extensions")
        }
        s.startTag(NS_GPX, "trkseg")
        for (p in trail.points) writeTrkpt(s, fmt, p)
        s.endTag(NS_GPX, "trkseg")
        s.endTag(NS_GPX, "trk")

        s.endTag(NS_GPX, "gpx")
        s.endDocument()
        w.flush()
    }

    private fun writeTrkpt(s: org.xmlpull.v1.XmlSerializer, fmt: SimpleDateFormat, p: TrailPoint) {
        s.startTag(NS_GPX, "trkpt")
        s.attribute(null, "lat", "%.7f".format(Locale.US, p.latDeg))
        s.attribute(null, "lon", "%.7f".format(Locale.US, p.lonDeg))
        p.eleMeters?.let { s.simpleTag(NS_GPX, "ele", "%.2f".format(Locale.US, it)) }
        s.simpleTag(NS_GPX, "time", fmt.format(Date(p.timeMillis)))
        p.hAccuracyM?.let { s.simpleTag(NS_GPX, "hdop", "%.2f".format(Locale.US, it)) }
        p.satellitesInFix?.let { s.simpleTag(NS_GPX, "sat", it.toString()) }

        val hasExt = p.speedMps != null || p.courseDeg != null ||
            p.vAccuracyM != null || p.heartRateBpm != null ||
            p.targetPaceSecondsPerKm != null || p.powerWatts != null
        if (hasExt) {
            s.startTag(NS_GPX, "extensions")
            // TrackPointExtension wraps speed, course, AND hr — those
            // three are all defined in the same Garmin schema and Strava
            // / Garmin Connect look for them under the same parent tag.
            val needsTpx = p.speedMps != null || p.courseDeg != null || p.heartRateBpm != null
            if (needsTpx) {
                s.startTag(NS_GPXTPX, "TrackPointExtension")
                p.speedMps?.let { s.simpleTag(NS_GPXTPX, "speed", "%.3f".format(Locale.US, it)) }
                p.courseDeg?.let { s.simpleTag(NS_GPXTPX, "course", "%.2f".format(Locale.US, it)) }
                p.heartRateBpm?.let { s.simpleTag(NS_GPXTPX, "hr", it.toString()) }
                s.endTag(NS_GPXTPX, "TrackPointExtension")
            }
            // Cycling power lives in its own Garmin namespace, not in
            // TrackPointExtension. Strava + Garmin Connect both look
            // here for `PowerInWatts`.
            p.powerWatts?.let { s.simpleTag(NS_GPXPX, "PowerInWatts", it.toString()) }
            p.vAccuracyM?.let { s.simpleTag(NS_GPSINFO, "vacc", "%.2f".format(Locale.US, it)) }
            // Per-point target pace lives in our private namespace —
            // other tools will preserve but ignore it.
            p.targetPaceSecondsPerKm?.let {
                s.simpleTag(NS_GPSINFO, "target_pace_s_per_km", "%.3f".format(Locale.US, it))
            }
            s.endTag(NS_GPX, "extensions")
        }

        s.endTag(NS_GPX, "trkpt")
    }

    private fun org.xmlpull.v1.XmlSerializer.simpleTag(ns: String, name: String, text: String) {
        startTag(ns, name); text(text); endTag(ns, name)
    }

    fun parse(input: InputStream): ParsedGpx {
        val fmt = isoFormat()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var name: String? = null
        var targetPaceSecondsPerKm: Float? = null
        var tags: List<String> = emptyList()
        val points = ArrayList<TrailPoint>()
        val laps = ArrayList<LapMarker>()
        // Stack of element names we're currently inside, so a stray <time>
        // inside <metadata> doesn't get attached to a trackpoint.
        val stack = ArrayDeque<String>()
        var current: MutableTrkpt? = null

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name
                    stack.addLast(tag)
                    when (tag) {
                        "trkpt" -> {
                            current = MutableTrkpt(
                                latDeg = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0,
                                lonDeg = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0,
                            )
                        }
                        "name" -> {
                            if (stack.size >= 2 && stack[stack.size - 2] == "metadata" && name == null) {
                                name = parser.nextText().trim()
                                stack.removeLast()
                            }
                        }
                        "keywords" -> {
                            if (stack.size >= 2 && stack[stack.size - 2] == "metadata") {
                                tags = parser.nextText()
                                    .split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                stack.removeLast()
                            }
                        }
                        "ele" -> current?.let { it.eleMeters = parser.nextText().toDoubleOrNull(); stack.removeLast() }
                        "time" -> current?.let { it.timeMillis = parseIso(fmt, parser.nextText()); stack.removeLast() }
                        "hdop" -> current?.let { it.hAcc = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "sat" -> current?.let { it.sats = parser.nextText().toIntOrNull(); stack.removeLast() }
                        "speed" -> current?.let { it.speed = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "course" -> current?.let { it.course = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "hr" -> current?.let { it.hr = parser.nextText().toIntOrNull(); stack.removeLast() }
                        "PowerInWatts" -> current?.let { it.power = parser.nextText().toIntOrNull(); stack.removeLast() }
                        "vacc" -> current?.let { it.vAcc = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "target_pace_s_per_km" -> {
                            // Same tag name lives at two levels:
                            //   trail-level (inside trk/extensions, no
                            //     active trkpt) → overall route target
                            //   point-level (inside trkpt/extensions)
                            //     → per-segment target for navigation
                            val cur = current
                            if (cur == null) {
                                targetPaceSecondsPerKm = parser.nextText().toFloatOrNull()
                            } else {
                                cur.targetPaceKm = parser.nextText().toFloatOrNull()
                            }
                            stack.removeLast()
                        }
                        "lap" -> {
                            // Self-closing element with all data on
                            // attributes. Skip silently if malformed —
                            // a missing lap entry is recoverable, an
                            // exception isn't.
                            val n = parser.getAttributeValue(null, "n")?.toIntOrNull()
                            val t = parser.getAttributeValue(null, "time")
                                ?.let { parseIso(fmt, it) }
                            if (n != null && t != null && t > 0L) {
                                laps.add(
                                    LapMarker(
                                        index = n,
                                        timeMillis = t,
                                        cumulativeDistanceM = parser.getAttributeValue(null, "cum_distance_m")
                                            ?.toDoubleOrNull() ?: 0.0,
                                        lapDistanceM = parser.getAttributeValue(null, "lap_distance_m")
                                            ?.toDoubleOrNull() ?: 0.0,
                                        lapDurationMs = parser.getAttributeValue(null, "lap_duration_ms")
                                            ?.toLongOrNull() ?: 0L,
                                        avgHrBpm = parser.getAttributeValue(null, "avg_hr")
                                            ?.toIntOrNull(),
                                    )
                                )
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name
                    if (tag == "trkpt") {
                        current?.toTrailPoint()?.let { points.add(it) }
                        current = null
                    }
                    if (stack.isNotEmpty() && stack.last() == tag) stack.removeLast()
                }
            }
            ev = parser.next()
        }
        return ParsedGpx(name, points, targetPaceSecondsPerKm, laps, tags)
    }

    private fun parseIso(fmt: SimpleDateFormat, text: String): Long =
        try { fmt.parse(text.trim())?.time ?: 0L } catch (_: Exception) { 0L }
}

internal data class ParsedGpx(
    val name: String?,
    val points: List<TrailPoint>,
    val targetPaceSecondsPerKm: Float? = null,
    val laps: List<LapMarker> = emptyList(),
    val tags: List<String> = emptyList(),
)

private class MutableTrkpt(
    var latDeg: Double,
    var lonDeg: Double,
    var timeMillis: Long = 0L,
    var eleMeters: Double? = null,
    var speed: Float? = null,
    var course: Float? = null,
    var hAcc: Float? = null,
    var vAcc: Float? = null,
    var sats: Int? = null,
    var hr: Int? = null,
    var power: Int? = null,
    var targetPaceKm: Float? = null,
) {
    fun toTrailPoint() = TrailPoint(
        timeMillis = timeMillis,
        latDeg = latDeg,
        lonDeg = lonDeg,
        eleMeters = eleMeters,
        speedMps = speed,
        courseDeg = course,
        hAccuracyM = hAcc,
        vAccuracyM = vAcc,
        satellitesInFix = sats,
        heartRateBpm = hr,
        powerWatts = power,
        targetPaceSecondsPerKm = targetPaceKm,
    )
}
