// Root build file.
//
// Plugins are deliberately NOT declared here with `apply false`. Doing so would
// force Gradle to resolve the Android Gradle Plugin even on machines with no
// Android SDK, where `:app` is excluded from the build (see settings.gradle.kts).
// Each module applies what it needs via the version catalog instead.

tasks.register("engineCheck") {
    group = "verification"
    description = "Runs the attendance engine unit tests (no Android SDK required)."
    dependsOn(":core:test")
}
