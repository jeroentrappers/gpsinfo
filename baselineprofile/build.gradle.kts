plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("androidx.baselineprofile")
}

android {
    namespace = "com.appmire.gpsinfo.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline profile generation requires a device on API 28+; the
        // installer (in :app) back-ports loading to API 24, our minSdk.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // The app under measurement — its `release` (or `nonMinified` if
    // configured) variant is what gets exercised.
    targetProjectPath = ":app"
}

// Use a real connected device/emulator. CI rigs that prefer Gradle-managed
// devices can override this in their environment.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
