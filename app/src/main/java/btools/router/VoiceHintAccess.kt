// Same-package accessor shim for BRouter (MIT). VoiceHint's position
// and command fields are package-private; declaring this file in
// btools.router gives us compile-checked access without reflection.
package btools.router

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
