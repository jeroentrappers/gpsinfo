plugins {
    // AGP 9+ ships built-in Kotlin support; no separate
    // `org.jetbrains.kotlin.android` plugin needed.
    id("com.android.application") version "9.2.1" apply false
    id("com.android.test") version "9.2.1" apply false
    // Compose Compiler plugin still applied separately. Its version is
    // the Kotlin version, which AGP 9 manages internally.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // Generates an ART baseline profile for the app's startup + cold-path
    // composables. Applied to both `:app` (consumer) and `:baselineprofile`
    // (producer module).
    id("androidx.baselineprofile") version "1.5.0-alpha06" apply false
}
