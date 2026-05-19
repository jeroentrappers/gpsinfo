package com.appmire.gpsinfo.screengrab

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.appmire.gpsinfo.MainActivity
import com.appmire.gpsinfo.data.TestDataSourceOverride
import org.junit.After
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Drives MainActivity to every top-level screen and takes a screenshot
 * at each step.
 *
 * Permission handling:
 *   [MainActivity]'s LaunchedEffect calls `ContextCompat.checkSelfPermission`
 *   directly against the OS, NOT the fake data source — so the permission
 *   dialog would otherwise pop over the dashboard and our taps would land
 *   on it. [GrantPermissionRule] grants ACCESS_FINE_LOCATION before the
 *   test method runs, so the dialog never appears.
 *
 * Locating UI elements:
 *   Coordinate-based taps were too fragile across aspect ratios + locales.
 *   The dashboard exposes a [testTagsAsResourceId][androidx.compose.ui.semantics.testTagsAsResourceId]
 *   semantics root, and each clickable card sets a [Modifier.testTag]:
 *   "card_speed", "card_satellites", "card_compass", "footer_about".
 *   Those test tags become Android resource-ids that UiAutomator can find
 *   via `By.res(packageName, tag)`.
 *
 * No Espresso / no Compose test rule:
 *   Espresso ≤ 3.6.x reflectively calls `InputManager.getInstance()`,
 *   which Android 14+ no longer exposes. `createAndroidComposeRule` pulls
 *   Espresso in transitively. Pure [ActivityScenario] + [UiDevice]
 *   sidesteps it entirely.
 *
 * Run via: `bundle exec fastlane screenshots` (requires a running emulator).
 */
@RunWith(AndroidJUnit4::class)
class ScreengrabTest {

    companion object {
        @ClassRule @JvmField
        val localeTestRule = LocaleTestRule()

        /** Generous so even slow emulators settle. */
        private const val FIND_TIMEOUT_MS = 5_000L

        /** Time to give Compose to lay out after a navigation event before
         *  the screenshot fires. */
        private const val SETTLE_MS = 600L
    }

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val packageName: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.packageName

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
            .getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        ActivityScenario.launch<MainActivity>(launch).use {
            // Wait for the dashboard to lay out — finding the first card
            // is also our "activity is ready" signal.
            awaitTag("card_speed")
            screenshot("01_dashboard")

            // 2 — Speed gauge
            clickTag("card_speed")
            settle()
            screenshot("02_speed")
            device.pressBack()

            // 3 — Sky view / satellites list
            awaitTag("card_satellites")
            clickTag("card_satellites")
            settle()
            screenshot("03_satellites")
            device.pressBack()

            // 4 — Compass detail
            awaitTag("card_compass")
            clickTag("card_compass")
            settle()
            screenshot("04_compass")
            device.pressBack()

            // 5 — About (scroll to footer first; on tablets it's already visible)
            awaitTag("card_compass") // dashboard is back
            scrollUntilTagVisible("footer_about")
            clickTag("footer_about")
            settle()
            screenshot("05_about")
        }
    }

    /** Block until a Compose node with [tag] is on-screen, fail loudly otherwise. */
    private fun awaitTag(tag: String) {
        val found = device.wait(Until.findObject(By.res(packageName, tag)), FIND_TIMEOUT_MS)
        requireNotNull(found) { "didn't find element with testTag '$tag' within ${FIND_TIMEOUT_MS} ms" }
    }

    private fun clickTag(tag: String) {
        val target = device.wait(Until.findObject(By.res(packageName, tag)), FIND_TIMEOUT_MS)
        requireNotNull(target) { "didn't find element with testTag '$tag' to click" }
        target.click()
        device.waitForIdle()
    }

    /** Swipe the dashboard up until the requested tag is visible (handy
     *  for the About footer, which sits below the fold on phones). */
    private fun scrollUntilTagVisible(tag: String) {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(6) {
            if (device.findObject(By.res(packageName, tag)) != null) return
            device.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.22).toInt(), 30)
            device.waitForIdle()
        }
        awaitTag(tag) // fail with the standard error if still missing
    }

    private fun settle() {
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    private fun screenshot(name: String) {
        Screengrab.screenshot(name)
    }
}
