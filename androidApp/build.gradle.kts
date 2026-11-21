// The Android app. Flavors (CLAUDE.md, plan.md Part F):
//   play: payments from UPI and bank apps' notifications, and the SMS app's notifications (no SMS permission).
//   full: also reads SMS as they arrive (RECEIVE_SMS) and imports the last 30 days (READ_SMS); not for the Play Store.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
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

    // Launch prep: the upload key comes only from the developer's own ~/.gradle/gradle.properties (or -P on the command
    // line), never from the repository. Without it, release builds stay unsigned (CI, and the checks there).
    val signing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("vyaya.signing.$it").orNull }
    if (signing.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(signing.getValue("storeFile")!!)
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
    implementation(project(":shared"))
    implementation(project(":core:data"))
    implementation(project(":core:engine"))
    implementation(project(":core:backup"))
    implementation(libs.room.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.biometric)
    // biometric 1.1.0 brings Fragment 1.2, whose activity results don't reach a FragmentActivity (file pickers).
    implementation(libs.fragment)

    testImplementation(libs.junit)
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

// Coverage gate from CLAUDE.md (branch review 2026-11-18 #4): platform adapters (app.vyaya.android) and the shared
// screens (app.vyaya.ui, app.vyaya.shared) each >= 70 % of lines, measured by the app's unit and screen tests with the
// shared module's own tests. `./gradlew :androidApp:koverVerify`.
dependencies {
    kover(project(":shared"))
}

kover {
    reports {
        filters {
            excludes {
                // Generated code: Compose's singletons and resources, and BuildConfig.
                classes("*ComposableSingletons*", "*.BuildConfig", "*.R", "*.R$*", "vyaya.shared.generated.resources.*")
            }
        }
        verify {
            rule("Platform adapters and screens, each package") {
                groupBy = kotlinx.kover.gradle.plugin.dsl.GroupingEntityType.PACKAGE
                minBound(70, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
    }
}
