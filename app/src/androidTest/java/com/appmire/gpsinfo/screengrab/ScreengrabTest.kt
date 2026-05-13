package com.appmire.gpsinfo.screengrab

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.appmire.gpsinfo.MainActivity
import com.appmire.gpsinfo.data.TestDataSourceOverride
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Drives MainActivity to every top-level screen and takes a screenshot
 * at each step.
 *
 * Why no Compose test rule / Espresso:
 *   Espresso ≤ 3.6.x reflectively calls `InputManager.getInstance()`,
 *   which Android 14+ no longer exposes. `createAndroidComposeRule` pulls
 *   Espresso in transitively, so even a pure-UiAutomator screenshot test
 *   crashes on `composeRule.waitForIdle()`. Pure
 *   [ActivityScenario] + [UiDevice] sidesteps it entirely — we just sleep
 *   long enough for the next frame to compose, take the shot, move on.
 *
 * Run via: `bundle exec fastlane screenshots` (requires a running emulator).
 */
@RunWith(AndroidJUnit4::class)
class ScreengrabTest {

    companion object {
        @ClassRule @JvmField
        val localeTestRule = LocaleTestRule()

        /** Time to give Compose to settle a screen before we screenshot it.
         *  Generous because slow emulators are common on CI. */
        private const val SETTLE_MS = 750L
    }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun seedFakes() {
        TestDataSourceOverride.location = FakeLocationDataSource()
        TestDataSourceOverride.sensor = FakeSensorDataSource()
        TestDataSourceOverride.settings = FakeSettingsDataSource()
        Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())
    }

    @After
    fun clearFakes() {
        TestDataSourceOverride.clear()
    }

    @Test
    fun take_all_screenshots() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = targetContext.packageManager
            .getLaunchIntentForPackage(targetContext.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        ActivityScenario.launch<MainActivity>(launch).use {
            settle()

            // 1 — Dashboard.
            screenshot("01_dashboard")

            // 2 — Movement card → speed gauge (≈ position 2 from top).
            clickByPosition(2)
            settle()
            screenshot("02_speed")
            device.pressBack()
            settle()

            // 3 — Sky view card → satellites list (≈ position 3).
            clickByPosition(3)
            settle()
            screenshot("03_satellites")
            device.pressBack()
            settle()

            // 4 — Compass card → compass detail (≈ position 4).
            clickByPosition(4)
            settle()
            screenshot("04_compass")
            device.pressBack()
            settle()

            // 5 — About via the footer link.
            scrollToFooterAndOpenAbout()
            settle()
            screenshot("05_about")
        }
    }

    /**
     * Tap roughly where the n-th dashboard card sits. We avoid string
     * lookups so the same test works under every locale; coordinate-based
     * taps land on the right card because our fake data populates all
     * cards uniformly.
     */
    private fun clickByPosition(positionFromTop: Int) {
        val w = device.displayWidth
        val h = device.displayHeight
        // Status bar + position card ≈ first 30 % of the screen; each
        // subsequent card adds ~13 %.
        val y = (h * (0.30f + 0.13f * (positionFromTop - 1))).toInt()
        device.click(w / 2, y)
    }

    private fun scrollToFooterAndOpenAbout() {
        val w = device.displayWidth
        val h = device.displayHeight
        // Two long swipes are enough to reach the footer on any phone, even
        // in a locale whose copy expanded the column height.
        repeat(2) {
            device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.22).toInt(), 30)
            device.waitForIdle()
        }
        device.click(w / 2, (h * 0.80).toInt())
    }

    private fun settle() {
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    private fun screenshot(name: String) {
        Screengrab.screenshot(name)
    }
}
