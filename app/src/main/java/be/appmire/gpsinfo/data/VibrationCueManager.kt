package be.appmire.gpsinfo.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tactile counterpart to [AudibleCueManager]. Fires distinct vibration
 * patterns on pace-deviation severity transitions and HR-zone changes.
 *
 * Designed to be called *alongside* the audible cues — the user can
 * enable either, both, or neither. The patterns are short and distinct
 * so a runner can identify the cue category without looking:
 *
 *   pace too fast    : ▮ ▮          (two short bumps, descending feel)
 *   pace too slow    : ▮ ▮ ▮        (three short bumps)
 *   HR too high      : ▮▮▮▮▮▮▮      (one long buzz)
 *   HR too low       : ▮            (one short tap)
 *   pace on target   : (silent — no vibration on "everything's fine")
 *
 * Throttling mirrors AudibleCueManager so a noisy threshold doesn't
 * pulse the phone repeatedly.
 */
class VibrationCueManager(context: Context) {

    @Volatile var enabled: Boolean = false

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val mgr = appContext.getSystemService(VibratorManager::class.java)
        mgr?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastPaceSeverity: AudibleCueManager.PaceSeverity? = null
    private var lastPaceAt: Long = 0L
    private var lastHrZone: Int? = null
    private var lastHrAt: Long = 0L
    private var lastOffRoute: Boolean? = null
    private var lastOffRouteAt: Long = 0L

    fun reportPace(severity: AudibleCueManager.PaceSeverity) {
        if (!enabled || vibrator == null || !vibrator.hasVibrator()) return
        if (severity == lastPaceSeverity) return
        val now = System.currentTimeMillis()
        if (now - lastPaceAt < MIN_CUE_INTERVAL_MS) return
        lastPaceSeverity = severity
        lastPaceAt = now
        val pattern = when (severity) {
            AudibleCueManager.PaceSeverity.OnTarget -> return // silence is golden
            AudibleCueManager.PaceSeverity.SlightlySlow,
            AudibleCueManager.PaceSeverity.TooSlow -> longArrayOf(0, 80, 120, 80, 120, 80)
            AudibleCueManager.PaceSeverity.SlightlyFast,
            AudibleCueManager.PaceSeverity.TooFast -> longArrayOf(0, 80, 120, 80)
        }
        vibrate(pattern)
    }

    fun reportHrZone(zone: Int, hrTooHighThreshold: Int = 4) {
        if (!enabled || vibrator == null || !vibrator.hasVibrator()) return
        if (zone == lastHrZone) return
        val now = System.currentTimeMillis()
        if (now - lastHrAt < MIN_CUE_INTERVAL_MS) return
        // Only buzz when crossing into "too high" (≥ threshold) or
        // dropping into Z1. Mid-range zone changes (Z2↔Z3↔Z4) shouldn't
        // pulse the phone — those are normal training-state transitions.
        val pattern = when {
            zone >= hrTooHighThreshold -> longArrayOf(0, 400)
            zone <= 1 && (lastHrZone ?: 0) > 1 -> longArrayOf(0, 80)
            else -> { lastHrZone = zone; return }
        }
        lastHrZone = zone
        lastHrAt = now
        vibrate(pattern)
    }

    /** Off-route transition cue. Long buzz on going off, single tap
     *  on returning. */
    fun reportOffRoute(isOff: Boolean) {
        if (!enabled || vibrator == null || !vibrator.hasVibrator()) return
        if (isOff == lastOffRoute) return
        val now = System.currentTimeMillis()
        if (now - lastOffRouteAt < MIN_CUE_INTERVAL_MS) return
        lastOffRoute = isOff
        lastOffRouteAt = now
        val pattern = if (isOff) longArrayOf(0, 600) else longArrayOf(0, 100)
        vibrate(pattern)
    }

    fun resetTransitions() {
        lastPaceSeverity = null
        lastHrZone = null
        lastOffRoute = null
    }

    private fun vibrate(pattern: LongArray) {
        if (vibrator == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    companion object {
        const val MIN_CUE_INTERVAL_MS: Long = 4_000L

        @Volatile
        private var instance: VibrationCueManager? = null

        fun getInstance(context: Context): VibrationCueManager =
            instance ?: synchronized(this) {
                instance ?: VibrationCueManager(context).also { instance = it }
            }
    }
}
