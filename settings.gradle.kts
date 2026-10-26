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
