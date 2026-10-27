// User simulation (plan.md Part E). Test support only: never shipped and not part of the coverage gate.
// Default run is small so CI stays fast; full run: ./gradlew :core:simulation:jvmTest -Psim.users=10000
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:parser"))
            implementation(project(":core:fixtures"))
            implementation(project(":core:backup"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty("sim.users", providers.gradleProperty("sim.users").getOrElse("200"))
    systemProperty("sim.backup.users", providers.gradleProperty("sim.backup.users").getOrElse("100"))
    systemProperty("sim.backup.big", providers.gradleProperty("sim.backup.big").getOrElse("false"))
    systemProperty("sim.backup.report", layout.buildDirectory.file("simulation/backup-report.txt").get().asFile.absolutePath)
    systemProperty("sim.report", layout.buildDirectory.file("simulation/report.txt").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
}
