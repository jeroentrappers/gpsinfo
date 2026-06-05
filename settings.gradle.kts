pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // BRouter (offline routing engine, MIT) is not on Maven
        // Central — JitPack builds it from the GitHub tags. Scoped so
        // nothing else can silently resolve from JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.abrensch.*") }
        }
    }
}

rootProject.name = "GPSinfo"
include(":app")
include(":baselineprofile")
