pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Attendo"

// :core is pure Kotlin/JVM — the domain model and the attendance math engine.
// It has no Android dependencies, so `./gradlew :core:test` runs with only a JDK.
include(":core")

// :app is the Android module and needs the Android SDK. AGP fails during
// *configuration* when it can't locate an SDK, which would take :core:test down
// with it. So only wire :app in once an SDK actually exists — that keeps the
// engine tests runnable on a bare machine.
val androidSdkAvailable: Boolean =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle(
        """
        |Attendo: no Android SDK detected — the :app module is not part of this build.
        |         The engine still builds and tests:  ./gradlew :core:test
        |         To enable :app, open this folder in Android Studio, or set ANDROID_HOME.
        """.trimMargin()
    )
}
