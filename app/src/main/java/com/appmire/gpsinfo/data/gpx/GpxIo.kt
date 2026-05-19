package com.appmire.gpsinfo.data.gpx

import android.util.Xml
import com.appmire.gpsinfo.data.model.Trail
import com.appmire.gpsinfo.data.model.TrailPoint
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
        s.setPrefix("gpsinfo", NS_GPSINFO)

        s.startTag(NS_GPX, "gpx")
        s.attribute(null, "version", "1.1")
        s.attribute(null, "creator", "GPSinfo (appmire.be)")

        s.startTag(NS_GPX, "metadata")
        s.simpleTag(NS_GPX, "name", trail.name)
        trail.startTimeMillis?.let {
            s.simpleTag(NS_GPX, "time", fmt.format(Date(it)))
        }
        s.endTag(NS_GPX, "metadata")

        s.startTag(NS_GPX, "trk")
        s.simpleTag(NS_GPX, "name", trail.name)
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

        val hasExt = p.speedMps != null || p.courseDeg != null || p.vAccuracyM != null
        if (hasExt) {
            s.startTag(NS_GPX, "extensions")
            if (p.speedMps != null || p.courseDeg != null) {
                s.startTag(NS_GPXTPX, "TrackPointExtension")
                p.speedMps?.let { s.simpleTag(NS_GPXTPX, "speed", "%.3f".format(Locale.US, it)) }
                p.courseDeg?.let { s.simpleTag(NS_GPXTPX, "course", "%.2f".format(Locale.US, it)) }
                s.endTag(NS_GPXTPX, "TrackPointExtension")
            }
            p.vAccuracyM?.let { s.simpleTag(NS_GPSINFO, "vacc", "%.2f".format(Locale.US, it)) }
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
        val points = ArrayList<TrailPoint>()
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
                        "ele" -> current?.let { it.eleMeters = parser.nextText().toDoubleOrNull(); stack.removeLast() }
                        "time" -> current?.let { it.timeMillis = parseIso(fmt, parser.nextText()); stack.removeLast() }
                        "hdop" -> current?.let { it.hAcc = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "sat" -> current?.let { it.sats = parser.nextText().toIntOrNull(); stack.removeLast() }
                        "speed" -> current?.let { it.speed = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "course" -> current?.let { it.course = parser.nextText().toFloatOrNull(); stack.removeLast() }
                        "vacc" -> current?.let { it.vAcc = parser.nextText().toFloatOrNull(); stack.removeLast() }
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
        return ParsedGpx(name, points)
    }

    private fun parseIso(fmt: SimpleDateFormat, text: String): Long =
        try { fmt.parse(text.trim())?.time ?: 0L } catch (_: Exception) { 0L }
}

internal data class ParsedGpx(val name: String?, val points: List<TrailPoint>)

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
    )
}
