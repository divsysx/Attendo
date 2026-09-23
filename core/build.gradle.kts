plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM. This module must never depend on the Android SDK — it holds
// the domain model and the attendance math, and its tests are the safety net for
// the fractional-attendance logic. Keeping it Android-free means `./gradlew
// :core:test` runs anywhere a JDK 17 exists.
//
// kotlinx-serialization-json is the one production dependency, and it earns its place:
// the backup format is a file a user can hand us from anywhere, so it is parsed rather
// than trusted. Hand-rolling a JSON reader would mean hand-rolling the parts that bite
// — string escapes, surrogate pairs, number forms, nesting depth — in the one code path
// where a bug loses a semester. It is a Kotlin library with no Android SDK in it, so the
// rule above still holds.
kotlin {
    jvmToolchain(17)
}

// The golden backup-fixture suite (docs/backup-format.md §13) lives at the repository
// root so the future Web implementation can consume the exact same files rather than a
// copy re-exported from Kotlin. Adding the directory to the test source set's resources
// puts the files on the classpath as /backup/*, where the tests read them verbatim —
// never through a Kotlin re-encoding, because a fixture that passed through the
// implementation under test would pin nothing.
sourceSets {
    getByName("test") {
        resources.srcDir(rootProject.file("fixtures"))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
