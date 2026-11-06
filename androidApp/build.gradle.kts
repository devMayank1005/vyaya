// The Android app. Flavors (CLAUDE.md, plan.md Part F):
//   play: payments from UPI and bank apps' notifications, and the SMS app's notifications (no SMS permission).
//   full: also reads SMS as they arrive (RECEIVE_SMS) and imports the last 30 days (READ_SMS); not for the Play Store.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.vyaya.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.vyaya"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "channel"
    productFlavors {
        create("play") { dimension = "channel" }
        create("full") {
            dimension = "channel"
            applicationIdSuffix = ".full" // installs beside the play build
            versionNameSuffix = "-full"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.room.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(project(":core:backup"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Compile with a full JDK 17 (javac and jlink), whatever JVM runs Gradle.
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// Robolectric 4.17 needs Java 21 or newer to run.
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("vyaya.testJdk").getOrElse("24").toInt())) })
}
