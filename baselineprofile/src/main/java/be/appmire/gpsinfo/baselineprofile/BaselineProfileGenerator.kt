package be.appmire.gpsinfo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives MainActivity through its hottest paths so AGP can record an ART
 * baseline profile of the Compose composables and Kotlin classes loaded
 * during startup + dashboard render. The generated `baseline-prof.txt`
 * is bundled into the release APK by the `androidx.baselineprofile`
 * plugin and installed at runtime by `profileinstaller`.
 *
 * Run with a connected device or emulator (API 28+):
 *   `./gradlew :app:generateBaselineProfile`
 *
 * The permission prompt at startup is sidestepped by relying on
 * UiAutomator's `By.text` lookup — if the prompt appears we tap "Allow",
 * otherwise we proceed straight to the dashboard. We don't grant via
 * `GrantPermissionRule` here because that's a JUnit instrumentation
 * test hook, and the baseline-profile run is driving the *app* APK
 * from a separate macrobench APK.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = APP_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()

        // Best-effort dismissal of the runtime permission prompt — it's
        // language-localised, so we match on common labels and fall
        // through if nothing matches.
        val allow = device.wait(
            Until.findObject(By.text(java.util.regex.Pattern.compile(
                "(?i)allow|toestaan|tillåt|autoriser|erlauben|consenti|許可|허용|разрешить|powiel|izinkan|izinā"
            ))),
            2_000,
        )
        allow?.click()

        // Wait for the dashboard to settle so the cards' composables and
        // their transitive code paths get exercised.
        device.wait(Until.hasObject(By.res(APP_PACKAGE, "card_speed")), 5_000)
    }

    companion object {
        private const val APP_PACKAGE = "be.appmire.gpsinfo"
    }
}
