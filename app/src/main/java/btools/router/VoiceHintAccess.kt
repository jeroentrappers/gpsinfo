// Same-package accessor shim for BRouter (MIT). VoiceHint's position
// and command fields are package-private; declaring this file in
// btools.router gives us compile-checked access without reflection.
package btools.router

import kotlin.math.roundToInt

/** Position of the hint on the track, BRouter integer microdegrees. */
val VoiceHint.iLat: Int get() = ilat
val VoiceHint.iLon: Int get() = ilon

/** Raw BRouter command id (C/TL/TR/KL/RNDB/…). */
val VoiceHint.command: Int get() = cmd

/** Metres from this hint to the next one (or the destination). */
val VoiceHint.distanceToNextMeters: Double get() = distanceToNext

/** Index of the hint's node in OsmTrack.nodes. */
val VoiceHint.trackIndex: Int get() = indexInTrack

/** Turn angle in degrees, negative = left. */
val VoiceHint.turnAngle: Float get() = angle

/** The hints of a computed track, oldest first. */
val VoiceHintList.hints: List<VoiceHint> get() = list ?: emptyList()

/**
 * Posted speed limit (km/h) of the way leading into this track node,
 * parsed from the OSM `maxspeed` tag BRouter carries on each segment's
 * [MessageData.wayKeyValues]. Null when the way is untagged or the value
 * isn't a plain number (implicit country zones like `RO:urban`, or
 * `none` / `walk` / `signals`). `MessageData` fields are package-private,
 * so this accessor must live in `btools.router`.
 */
val OsmPathElement.maxspeedKmh: Int?
    get() = message?.wayKeyValues?.let { parseMaxspeed(it) }

private val MAXSPEED_RE = Regex("""maxspeed=(\S+)""")

private fun parseMaxspeed(wayKeyValues: String): Int? {
    val raw = MAXSPEED_RE.find(wayKeyValues)?.groupValues?.get(1)?.lowercase() ?: return null
    return when {
        raw.endsWith("mph") ->
            raw.removeSuffix("mph").trim().toDoubleOrNull()?.let { (it * 1.609344).roundToInt() }
        else -> raw.toIntOrNull()?.takeIf { it in 1..300 }
    }
}
