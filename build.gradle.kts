plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
}

dependencies {
    // Aggregate coverage of every shared-core module here.
    kover(project(":core:model"))
    kover(project(":core:parser"))
    kover(project(":core:backup"))
}

// Coverage gate from CLAUDE.md: shared core >= 90 % lines and branches. `./gradlew koverVerify` fails below it.
kover {
    reports {
        filters {
            // Plain @Serializable data holders: their branches are compiler-generated serializer code.
            excludes { annotatedBy("kotlinx.serialization.Serializable") }
        }
        verify {
            rule("Shared core coverage") {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
