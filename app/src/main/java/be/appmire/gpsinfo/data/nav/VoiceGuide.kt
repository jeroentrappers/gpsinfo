package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Spoken guidance on Android's built-in [TextToSpeech] — offline,
 * dependency-free, and routed as navigation audio so head units duck
 * media instead of cutting it.
 *
 * Announcement ladder per turn: far (~600 m), near (~150 m), now
 * (~35 m). Each rung fires once; the ladder resets when the upcoming
 * turn changes or a new route installs. Phrases are deliberately
 * terse — driving guidance, not conversation.
 */
class VoiceGuide(context: Context) {

    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /** Identity of the turn the ladder currently tracks. */
    private var trackedTurnIndex = -1
    private var announcedFar = false
    private var announcedNear = false
    private var announcedNow = false

    fun resetAnnouncements() {
        trackedTurnIndex = -1
        announcedFar = false
        announcedNear = false
        announcedNow = false
    }

    fun announceStart(route: OfflineRoute) {
        speak("Route computed. ${formatKm(route.distanceMeters.toDouble())} to destination.")
    }

    fun announceReroute() = speak("Route recalculated.")

    fun announceArrival() = speak("You have arrived.")

    fun maybeAnnounceTurn(turn: TurnHint, distanceM: Double) {
        if (turn.trackIndex != trackedTurnIndex) {
            trackedTurnIndex = turn.trackIndex
            announcedFar = false
            announcedNear = false
            announcedNow = false
        }
        val phrase = phrase(turn) ?: return
        when {
            distanceM <= NOW_M && !announcedNow -> {
                announcedNow = true
                speak(phrase)
            }
            distanceM in NOW_M..NEAR_M && !announcedNear -> {
                announcedNear = true
                speak("In ${roundedMetres(distanceM)} metres, $phrase")
            }
            distanceM in NEAR_M..FAR_M && !announcedFar -> {
                announcedFar = true
                speak("In ${roundedMetres(distanceM)} metres, $phrase")
            }
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private fun phrase(turn: TurnHint): String? = when (turn.command) {
        TurnCommand.TURN_LEFT -> "turn left"
        TurnCommand.TURN_SLIGHT_LEFT -> "keep slightly left"
        TurnCommand.TURN_SHARP_LEFT -> "turn sharply left"
        TurnCommand.TURN_RIGHT -> "turn right"
        TurnCommand.TURN_SLIGHT_RIGHT -> "keep slightly right"
        TurnCommand.TURN_SHARP_RIGHT -> "turn sharply right"
        TurnCommand.KEEP_LEFT -> "keep left"
        TurnCommand.KEEP_RIGHT -> "keep right"
        TurnCommand.U_TURN -> "make a U turn"
        TurnCommand.ROUNDABOUT ->
            if (turn.exitNumber > 0) "at the roundabout, take exit ${turn.exitNumber}"
            else "enter the roundabout"
        TurnCommand.STRAIGHT, TurnCommand.OFF_ROUTE, TurnCommand.UNKNOWN -> null
    }

    private fun roundedMetres(m: Double): Int = when {
        m > 400 -> (m / 100).roundToInt() * 100
        m > 100 -> (m / 50).roundToInt() * 50
        else -> (m / 10).roundToInt() * 10
    }

    private fun formatKm(m: Double): String =
        if (m >= 1000) String.format(Locale.ROOT, "%.1f kilometres", m / 1000)
        else "${m.roundToInt()} metres"

    private fun speak(text: String) {
        if (!ready) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${System.currentTimeMillis()}")
    }

    private companion object {
        const val FAR_M = 650.0
        const val NEAR_M = 170.0
        const val NOW_M = 38.0
    }
}
