plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

dependencies {
    // Aggregate coverage of every shared-core module here.
    kover(project(":core:model"))
    kover(project(":core:parser"))
}

// Coverage gate from CLAUDE.md: shared core >= 90 % lines and branches. `./gradlew koverVerify` fails below it.
kover {
    reports {
        verify {
            rule("Shared core coverage") {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
