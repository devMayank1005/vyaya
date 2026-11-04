// The on-device database (Room KMP): each row stores a record as its backup v3 line, plus indexed key columns.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") } // Room's generated database constructor
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "app.vyaya.core.data"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:dedup"))
            implementation(project(":core:backup"))
            implementation(libs.room.runtime)
        }
        // The bundled SQLite on JVM and iOS; Android uses the system's (AndroidSQLiteDriver), which its tests can run on.
        jvmMain.dependencies { implementation(libs.sqlite.bundled) }
        iosMain.dependencies { implementation(libs.sqlite.bundled) }
        androidMain.dependencies { api(libs.sqlite.framework) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspJvm", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}
