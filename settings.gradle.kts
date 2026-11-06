rootProject.name = "vyaya"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// Shared core: pure Kotlin, no Android or iOS APIs (CLAUDE.md rule 8.6).
include(":core:model")
include(":core:parser")
include(":core:backup")
include(":core:engine")
include(":core:filters")
include(":core:dedup")

// The on-device database (Room). Shared by the Android and iOS apps.
include(":core:data")

// The Android app (flavors: play = notifications only, full = also SMS). Kept out of the shared-core coverage gate.
include(":androidApp")

// Test support (synthetic messages) and the user simulation; never shipped.
include(":core:fixtures")
include(":core:simulation")
