import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing credentials. They live in `keystore.properties` at the repo root, which is
// untracked, and the keystore that file points at sits outside the repository entirely — so
// neither the passwords nor the key itself can be committed by accident. Nothing secret
// appears in this build script.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) keystorePropertiesFile.inputStream().use(::load)
}

fun releaseSigningProperty(key: String): String =
    keystoreProperties.getProperty(key)?.takeIf(String::isNotBlank)
        ?: throw GradleException(
            "Release signing is misconfigured: '$key' is missing or empty in " +
                "${keystorePropertiesFile.name}. Required keys: storeFile, storePassword, " +
                "keyAlias, keyPassword."
        )

android {
    namespace = "com.attendo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.attendo"
        // 26 is where java.time is available natively — the engine is built on
        // LocalDate/DayOfWeek throughout, and desugaring it away would be a lot of
        // machinery to support phones this app is unlikely to run on.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Created only when credentials are present, so a checkout without them can still
        // build and test debug. Requesting a release APK without them fails loudly instead
        // (see the guard below the android block).
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Only release is signed with the Attendo key. Debug is left alone and keeps
            // using AGP's own generated debug keystore.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Deliberately no `splits` block, no `ndk { abiFilters }` and no app bundle: one APK
    // carrying every ABI. It is sideloaded from a link passed around a campus, not
    // installed from a store that can pick a variant per device, so a per-ABI split would
    // mean one of them landing on a phone it cannot run on. The only native code here is
    // Compose's tiny path library, so the whole-set cost is a few hundred KB.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// A release build with no credentials must fail before it does any work, rather than quietly
// emitting an unsigned APK that looks like a shippable artifact. This is checked at
// configuration time — a task action closure would capture the build script itself, which the
// configuration cache cannot serialize.
if (!hasReleaseSigning) {
    val wantsRelease = gradle.startParameter.taskNames.any { requested ->
        val task = requested.substringAfterLast(':')
        task.equals("build", ignoreCase = true) || task.equals("assemble", ignoreCase = true) ||
            (task.contains("Release") &&
                listOf("assemble", "bundle", "package", "install").any(task::startsWith))
    }
    if (wantsRelease) {
        throw GradleException(
            "Cannot build a signed release: ${keystorePropertiesFile.path} not found. " +
                "It must define storeFile, storePassword, keyAlias and keyPassword. " +
                "Refusing to produce an unsigned release APK."
        )
    }
}

// Room's generated schema JSON, checked in so a migration can diff against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
}
