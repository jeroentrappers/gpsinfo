import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// Release signing — three sources tried in order:
//   1. keystore.properties at the repo root (gitignored; the dev's normal path)
//   2. FASTLANE_GPSINFO_* environment variables (CI-friendly)
//   3. No signing config — gradle will still build, just produce an unsigned APK
//
// Keys expected, in either source:
//   storeFile      / FASTLANE_GPSINFO_KEYSTORE_FILE      — absolute path to .jks
//   storePassword  / FASTLANE_GPSINFO_KEYSTORE_PASSWORD
//   keyAlias       / FASTLANE_GPSINFO_KEY_ALIAS
//   keyPassword    / FASTLANE_GPSINFO_KEY_PASSWORD
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val signingStoreFile = signingValue("storeFile", "FASTLANE_GPSINFO_KEYSTORE_FILE")
val signingStorePassword = signingValue("storePassword", "FASTLANE_GPSINFO_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "FASTLANE_GPSINFO_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "FASTLANE_GPSINFO_KEY_PASSWORD")
val hasReleaseSigning =
    signingStoreFile != null && signingStorePassword != null &&
        signingKeyAlias != null && signingKeyPassword != null

android {
    namespace = "be.appmire.gpsinfo"
    compileSdk = 37

    defaultConfig {
        applicationId = "be.appmire.gpsinfo"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // minSdk 24 has reliable native VectorDrawable support — no support library needed.
    }

    // Strip everything except the languages we actually ship resources for.
    // R8 also shrinks transitive resources from libraries (material3, etc.).
    // Keep in sync with res/xml/locales_config.xml and the Language picker.
    androidResources {
        localeFilters += listOf(
            "en", "cs", "de", "es", "fr", "it", "ja", "nl", "pl", "pt-rBR", "ru", "tr"
        )
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // `debug` keeps the AGP defaults (no minify, no resource shrinking)
        // so dev cycles stay fast.
    }

    buildFeatures {
        compose = true
    }

    // Compose compiler is now configured via the
    // `org.jetbrains.kotlin.plugin.compose` plugin in the root build file —
    // the old `composeOptions.kotlinCompilerExtensionVersion` block is gone.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9's built-in Kotlin support — the old `kotlinOptions { }` block
    // belongs to the standalone Kotlin Android plugin we no longer apply.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            // Future Kotlin default: an annotation on a constructor `val`
            // applies to BOTH the parameter and the backing property/field.
            // Matches the intent of @StringRes on enum value-class params.
            // See KT-73255.
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Android Lint is invoked from CI by `./gradlew lintDebug`. The
    // HTML/XML report is uploaded as an artifact regardless of pass/fail.
    //
    // Bootstrap workflow (one time):
    //   1. `./gradlew :app:updateLintBaseline` locally to generate
    //      `app/lint-baseline.xml` capturing today's findings.
    //   2. Uncomment the `baseline = ...` line below.
    //   3. Commit both — new issues then break the build, existing ones
    //      stay baselined.
    //
    // Until then: `abortOnError = false` keeps CI green on day one, but
    // `MissingTranslation` is still promoted to error severity so the
    // report screams loudly when an 11-locale string drifts.
    lint {
        // baseline = file("lint-baseline.xml")
        abortOnError = false
        warningsAsErrors = false
        checkDependencies = true
        // `MissingTranslation` is the check most worth enforcing here —
        // 11 locales is easy to break with a single new string.
        error += listOf("MissingTranslation")
        // Noisy on Compose-heavy code; we don't want it gating CI.
        disable += listOf("Typos")
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.window)
    // Loads the bundled ART baseline profile at app install / first run.
    // Without this dependency the baseline-prof.txt the plugin produces
    // is dead weight — profileinstaller is what reads it.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    // Tile-based map renderer for the trail-detail screen. Open-source,
    // OSM-backed, no Play Services. The rest of the app still avoids
    // Maps SDKs; this one is scoped to the trail view only.
    implementation(libs.osmdroid.android)
    // Android Auto projection-mode templates. The library is GMS-free —
    // the system Android Auto app (or a compatible third-party head
    // unit) is what hosts the templates we render. No new GMS surface.
    implementation(libs.androidx.car.app)
    // ZXing core for the "share my position" QR encoder. Pure Java,
    // Apache-2.0, no Play Services. We only ever encode here — the
    // scanner side is whatever app the recipient uses.
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented test + Fastlane screengrab support.
    //
    // We DELIBERATELY do not pull in androidx.compose.ui:ui-test-junit4 /
    // espresso here: Espresso ≤ 3.6.x reflectively calls
    // `InputManager.getInstance()`, which Android 14+ no longer exposes,
    // and screengrab tests would fail with a `NoSuchMethodException`.
    // For pure screenshot capture, UiAutomator + ActivityScenario is enough.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.fastlane.screengrab)
}
