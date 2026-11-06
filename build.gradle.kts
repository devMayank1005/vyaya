plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

dependencies {
    // Aggregate coverage of every shared-core module here.
    kover(project(":core:model"))
    kover(project(":core:parser"))
    kover(project(":core:backup"))
    kover(project(":core:engine"))
    kover(project(":core:filters"))
    kover(project(":core:dedup"))
    kover(project(":core:data"))
}

// Coverage gate from CLAUDE.md: shared core >= 90 % lines and branches. `./gradlew koverVerify` fails below it.
kover {
    reports {
        filters {
            // Plain @Serializable data holders: their branches are compiler-generated serializer code.
            excludes {
                annotatedBy("kotlinx.serialization.Serializable")
                // Room's generated implementations (DAOs, the database class): generated code, like the serializers.
                classes("*_Impl", "*_Impl$*", "app.vyaya.core.data.VyayaDatabaseConstructor")
            }
        }
        verify {
            rule("Shared core coverage") {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH)
            }
        }
    }
}
