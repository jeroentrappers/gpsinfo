import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    namespace = "com.appmire.gpsinfo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appmire.gpsinfo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // minSdk 29 has native VectorDrawable support — no support library needed.

        // Strip everything except the languages we actually ship resources for.
        // This is more aggressive than `resourceConfigurations` because R8 also
        // shrinks transitive resources from libraries (material3, etc.). Keep
        // in sync with res/xml/locales_config.xml and the Language picker.
        resourceConfigurations += listOf(
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
        debug {
            // Speed up dev cycles — no shrinking on debug.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.window:window:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Instrumented test + Fastlane screengrab support.
    //
    // We DELIBERATELY do not pull in androidx.compose.ui:ui-test-junit4 /
    // espresso here: Espresso ≤ 3.6.x reflectively calls
    // `InputManager.getInstance()`, which Android 14+ no longer exposes,
    // and screengrab tests would fail with a `NoSuchMethodException`.
    // For pure screenshot capture, UiAutomator + ActivityScenario is enough.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("tools.fastlane:screengrab:2.1.1")
}
