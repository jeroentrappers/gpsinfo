package be.appmire.gpsinfo.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken cues for live coaching. Driven from the Sports Dashboard when
 * the pace-deviation band changes or the HR zone transitions.
 *
 * Implementation deliberately uses on-device [TextToSpeech] only — no
 * cloud voice, no API keys, no telemetry, fits the app's stance.
 *
 * Throttling: at most one cue per category per [MIN_CUE_INTERVAL_MS]
 * window. Without this, fluctuating around a threshold would produce a
 * constant babble of "too slow / on target / too fast" announcements.
 *
 * Lifecycle: instantiated once per process, shut down with [release].
 * Callers must check [enabled] before relying on cues firing — the
 * user-facing toggle lives in Settings.
 */
class AudibleCueManager(context: Context) {

    enum class PaceSeverity { OnTarget, SlightlySlow, SlightlyFast, TooSlow, TooFast }

    @Volatile var enabled: Boolean = false

    private val appContext = context.applicationContext
    private var initialized: Boolean = false

    /**
     * Navigation-guidance audio attributes — this is the same usage tag
     * Google Maps and Waze use. The Android OS routes audio with this
     * tag through the ducking pipeline: any music app currently playing
     * will dip its own volume for the duration of our cue, then resume
     * automatically. The user keeps listening to their playlist while
     * getting the occasional spoken nudge.
     */
    private val cueAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusRequest: AudioFocusRequest? =
        if (Build.VERSION.SDK_INT >= 26) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(cueAttributes)
                .setOnAudioFocusChangeListener { /* nothing — TTS is fire-and-forget */ }
                .build()
        } else null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        initialized = (status == TextToSpeech.SUCCESS)
        if (initialized) {
            // Match system locale; falls back to engine default if
            // unavailable. Audio attributes hand the OS the ducking
            // hint without us having to manage stream volume ourselves.
            tts.language = Locale.getDefault()
            tts.setAudioAttributes(cueAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { abandonFocus() }
                @Deprecated("API 21 deprecation — overload required for older versions")
                override fun onError(utteranceId: String?) { abandonFocus() }
                override fun onError(utteranceId: String?, errorCode: Int) { abandonFocus() }
            })
        }
    }

    private var lastPaceSeverity: PaceSeverity? = null
    private var lastPaceCueAt: Long = 0L
    private var lastHrZone: Int? = null
    private var lastHrCueAt: Long = 0L
    private var lastOffRoute: Boolean? = null
    private var lastOffRouteAt: Long = 0L

    /** Report the current pace-deviation severity. Fires a cue only on
     *  band transitions, and at most once per [MIN_CUE_INTERVAL_MS]. */
    fun reportPace(severity: PaceSeverity) {
        if (!enabled || !initialized) return
        if (severity == lastPaceSeverity) return
        val now = System.currentTimeMillis()
        if (now - lastPaceCueAt < MIN_CUE_INTERVAL_MS) return
        lastPaceSeverity = severity
        lastPaceCueAt = now
        val resId = when (severity) {
            PaceSeverity.OnTarget -> be.appmire.gpsinfo.R.string.cue_on_target
            PaceSeverity.SlightlySlow -> be.appmire.gpsinfo.R.string.cue_slightly_slow
            PaceSeverity.SlightlyFast -> be.appmire.gpsinfo.R.string.cue_slightly_fast
            PaceSeverity.TooSlow -> be.appmire.gpsinfo.R.string.cue_too_slow
            PaceSeverity.TooFast -> be.appmire.gpsinfo.R.string.cue_too_fast
        }
        speak(appContext.getString(resId), utteranceId = "pace-${severity.name}")
    }

    /** Report the current HR zone. Fires only on zone transitions and
     *  honours the same throttle as pace cues. */
    fun reportHrZone(zone: Int) {
        if (!enabled || !initialized) return
        if (zone == lastHrZone) return
        val now = System.currentTimeMillis()
        if (now - lastHrCueAt < MIN_CUE_INTERVAL_MS) return
        lastHrZone = zone
        lastHrCueAt = now
        speak(appContext.getString(be.appmire.gpsinfo.R.string.cue_zone, zone), utteranceId = "hr-zone-$zone")
    }

    /** Off-route transition cue. Fires "Off route" the first time the
     *  runner crosses the threshold and "Back on route" when they
     *  return. No-op while the state hasn't changed. */
    fun reportOffRoute(isOff: Boolean) {
        if (!enabled || !initialized) return
        if (isOff == lastOffRoute) return
        val now = System.currentTimeMillis()
        if (now - lastOffRouteAt < MIN_CUE_INTERVAL_MS) return
        lastOffRoute = isOff
        lastOffRouteAt = now
        val resId = if (isOff) be.appmire.gpsinfo.R.string.cue_off_route
        else be.appmire.gpsinfo.R.string.cue_back_on_route
        speak(appContext.getString(resId), utteranceId = "off-route-${isOff}")
    }

    /** Reset transition memory — call when the user leaves the
     *  Sports Dashboard so re-entry replays the appropriate cue. */
    fun resetTransitions() {
        lastPaceSeverity = null
        lastHrZone = null
        lastOffRoute = null
    }

    fun release() {
        tts.stop()
        tts.shutdown()
    }

    private fun speak(text: String, utteranceId: String) {
        // Ask the system for transient may-duck focus so any music app
        // currently playing dips its volume for the duration of our cue.
        // We abandon focus on utterance completion / error (see TTS
        // listener wired in init).
        val granted = requestFocus()
        if (!granted) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            val result = focusRequest?.let { audioManager.requestAudioFocus(it) }
                ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                /* listener = */ null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        /** Don't announce the same category more than once in this
         *  window. Tuned so a runner crossing a zone boundary at a hard
         *  threshold hears one announcement, not a wobble of three. */
        private const val MIN_CUE_INTERVAL_MS = 8_000L

        @Volatile private var instance: AudibleCueManager? = null
        fun getInstance(context: Context): AudibleCueManager =
            instance ?: synchronized(this) {
                instance ?: AudibleCueManager(context).also { instance = it }
            }
    }
}
