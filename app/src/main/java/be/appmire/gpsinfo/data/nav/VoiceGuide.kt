package be.appmire.gpsinfo.data.nav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import be.appmire.gpsinfo.R
import be.appmire.gpsinfo.data.SettingsRepository
import be.appmire.gpsinfo.data.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Spoken guidance on Android's built-in [TextToSpeech] — offline,
 * dependency-free, and routed as navigation audio so head units duck
 * media instead of cutting it.
 *
 * Announcement ladder per turn: far (~650 m), near (~170 m), now
 * (~38 m). Each rung fires once; the ladder resets when the upcoming
 * turn changes or a new route installs. Phrases are deliberately
 * terse — driving guidance, not conversation.
 *
 * Fully localized: turn cues reuse the same `car_nav_*` strings the
 * on-screen maneuver card uses (so screen and voice always agree), and
 * the engine's voice is set to the app's current locale. Spoken
 * distances follow the user's [UnitSystem] (metric ⇄ imperial;
 * nautical falls back to metric for road guidance).
 */
class VoiceGuide(context: Context) {

    private val ctx = context.applicationContext

    private var ready = false
    private lateinit var tts: TextToSpeech

    /** Distance unit for spoken guidance, tracked live from settings. */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile
    private var unit: UnitSystem = UnitSystem.Metric

    /** Master mute (settings / nav-screen toggle). */
    @Volatile
    private var enabled: Boolean = true

    /** Detailed mode — adds "continue for X" on long straights. */
    @Volatile
    private var verbose: Boolean = false

    /** Chosen spoken-instruction locale, or null to follow the app/system. */
    @Volatile
    private var voiceLocale: Locale? = null

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Pronounce the localized phrases in the chosen (or app's)
                // locale; the engine falls back to its default voice if that
                // language pack isn't installed.
                applyLocale()
                ready = true
            }
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        val settings = SettingsRepository(ctx)
        settings.unitSystem.onEach { unit = it }.launchIn(scope)
        settings.voiceGuidanceEnabled.onEach { enabled = it }.launchIn(scope)
        settings.voiceVerbose.onEach { verbose = it }.launchIn(scope)
        settings.voiceLanguageTag
            .onEach {
                voiceLocale = it?.takeIf { t -> t.isNotBlank() }?.let { t -> Locale.forLanguageTag(t) }
                applyLocale()
            }
            .launchIn(scope)
    }

    private fun applyLocale() {
        if (!::tts.isInitialized) return
        runCatching { tts.language = voiceLocale ?: appLocale() }
    }

    /** Identity of the turn the ladder currently tracks. */
    private var trackedTurnIndex = -1
    private var announcedFar = false
    private var announcedNear = false
    private var announcedNow = false
    private var announcedContinue = false

    fun resetAnnouncements() {
        trackedTurnIndex = -1
        announcedFar = false
        announcedNear = false
        announcedNow = false
        announcedContinue = false
    }

    fun announceStart(route: OfflineRoute) {
        speak(ctx.getString(R.string.voice_route_computed, spokenDistance(route.distanceMeters.toDouble())))
    }

    fun announceReroute() = speak(ctx.getString(R.string.voice_recalculated))

    fun announceArrival() = speak(ctx.getString(R.string.voice_arrived))

    fun maybeAnnounceTurn(turn: TurnHint, distanceM: Double) {
        if (turn.trackIndex != trackedTurnIndex) {
            trackedTurnIndex = turn.trackIndex
            announcedFar = false
            announcedNear = false
            announcedNow = false
            announcedContinue = false
        }
        val cue = cue(turn) ?: return
        // Detailed mode: a "continue for X" heads-up on long straights,
        // before the regular distance rungs kick in.
        if (verbose && !announcedContinue && distanceM > CONTINUE_M) {
            announcedContinue = true
            speak(ctx.getString(R.string.voice_continue_for, spokenDistance(distanceM)))
        }
        when {
            distanceM <= NOW_M && !announcedNow -> {
                announcedNow = true
                speak(cue)
            }
            distanceM in NOW_M..NEAR_M && !announcedNear -> {
                announcedNear = true
                speak(ctx.getString(R.string.voice_in_distance, spokenDistance(distanceM), cue))
            }
            distanceM in NEAR_M..FAR_M && !announcedFar -> {
                announcedFar = true
                speak(ctx.getString(R.string.voice_in_distance, spokenDistance(distanceM), cue))
            }
        }
    }

    fun shutdown() {
        scope.cancel()
        tts.stop()
        tts.shutdown()
    }

    /** Localized turn cue — the same string the maneuver card shows.
     *  Null for non-maneuvers (straight / off-route / unknown), which
     *  aren't worth a spoken cue. */
    private fun cue(turn: TurnHint): String? {
        val res = when (turn.command) {
            TurnCommand.TURN_LEFT -> R.string.car_nav_turn_left
            TurnCommand.TURN_SLIGHT_LEFT -> R.string.car_nav_slight_left
            TurnCommand.TURN_SHARP_LEFT -> R.string.car_nav_sharp_left
            TurnCommand.TURN_RIGHT -> R.string.car_nav_turn_right
            TurnCommand.TURN_SLIGHT_RIGHT -> R.string.car_nav_slight_right
            TurnCommand.TURN_SHARP_RIGHT -> R.string.car_nav_sharp_right
            TurnCommand.KEEP_LEFT -> R.string.car_nav_keep_left
            TurnCommand.KEEP_RIGHT -> R.string.car_nav_keep_right
            TurnCommand.U_TURN -> R.string.car_nav_u_turn
            TurnCommand.ROUNDABOUT ->
                return if (turn.exitNumber > 0) {
                    ctx.getString(R.string.car_nav_roundabout_exit, turn.exitNumber)
                } else {
                    ctx.getString(R.string.car_nav_roundabout)
                }
            TurnCommand.STRAIGHT, TurnCommand.OFF_ROUTE, TurnCommand.UNKNOWN -> return null
        }
        return ctx.getString(res)
    }

    /** Distance phrase in the user's unit, with the number rounded to a
     *  natural spoken step and pronounced in the app's locale. */
    private fun spokenDistance(meters: Double): String = when (unit) {
        UnitSystem.Imperial -> {
            val feet = meters * FEET_PER_METER
            if (feet >= FEET_PER_MILE) {
                ctx.getString(
                    R.string.voice_dist_miles,
                    String.format(appLocale(), "%.1f", meters / METERS_PER_MILE),
                )
            } else {
                ctx.getString(R.string.voice_dist_feet, roundedStep(feet))
            }
        }
        // Metric, and nautical (road guidance in nautical miles makes no
        // sense) → metres / kilometres.
        else -> {
            if (meters >= 1000) {
                ctx.getString(
                    R.string.voice_dist_kilometers,
                    String.format(appLocale(), "%.1f", meters / 1000.0),
                )
            } else {
                ctx.getString(R.string.voice_dist_meters, roundedStep(meters))
            }
        }
    }

    /** Round to a natural spoken step: 100s far out, 50s mid, 10s close. */
    private fun roundedStep(v: Double): Int = when {
        v > 400 -> (v / 100).roundToInt() * 100
        v > 100 -> (v / 50).roundToInt() * 50
        else -> (v / 10).roundToInt() * 10
    }

    private fun appLocale(): Locale = ctx.resources.configuration.locales[0]

    private fun speak(text: String) {
        if (!ready || !enabled) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${System.currentTimeMillis()}")
    }

    private companion object {
        const val FAR_M = 650.0
        const val NEAR_M = 170.0
        const val NOW_M = 38.0
        /** Above this distance to the next turn, detailed mode says
         *  "continue for X" once. */
        const val CONTINUE_M = 1500.0

        const val FEET_PER_METER = 3.28084
        const val FEET_PER_MILE = 5280.0
        const val METERS_PER_MILE = 1609.344
    }
}
