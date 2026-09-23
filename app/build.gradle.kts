import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // The community DTOs are @Serializable classes; until now the app module only
    // *ran* the JSON library (decoding maps), which the runtime jar alone could do.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // Processes app/google-services.json — the Firebase config downloaded from the console —
    // into build resources. Without it the Firebase SDK initialises with no project and
    // analytics quietly goes nowhere.
    alias(libs.plugins.google.services)
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

// The community feature's Supabase endpoint. Both values live in local.properties —
// git-ignored, never in source — and are baked into BuildConfig at configuration time.
// A checkout without them still builds: the fields fall back to empty strings and the
// community client reports itself unavailable at runtime, leaving attendance untouched
// (see CommunitySupabaseConfig).
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val supabaseUrl = localProperties.getProperty("SUPABASE_URL").takeIf { !it.isNullOrBlank() } ?: ""
val supabasePublishableKey =
    localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY").takeIf { !it.isNullOrBlank() } ?: ""

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
        versionCode = 3
        versionName = "1.2"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabasePublishableKey\"")
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
                // v1 (JAR) signing alongside v2: the in-app updater reads the signer out of a
                // *downloaded* APK before installing it, and on Android 8/8.1 the platform's
                // archive parser only understands JAR signatures — a v2-only APK would come
                // back "unsigned" and be refused. Both schemes sign with the same key, so
                // this changes nothing about the app's identity.
                enableV1Signing = true
                enableV2Signing = true
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
        // The community feature reads its endpoint from BuildConfig fields above.
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests {
            // Robolectric inflates nothing here, but Room's generated code and the
            // `androidx.test` Context both need the merged resources on the unit-test
            // classpath rather than the stubbed `android.jar` alone.
            isIncludeAndroidResources = true
        }
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

    // Firebase Analytics, versioned by the BoM. Aggregate, anonymous usage information
    // only — see com.attendo.data.analytics.UsageAnalytics for the whole vocabulary of
    // events, and the README for what is deliberately never collected.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // The update system caches the last successful check's manifest to SharedPreferences as
    // JSON; the codec is the same one :core's backup format uses.
    implementation(libs.kotlinx.serialization.json)

    // Community: the Supabase client and its WebSocket-capable engine. The BOM pins the
    // module versions; the publishable key only — the service-role/secret key never
    // exists in this app. Realtime requires an engine with WebSocket capability, which is
    // why OkHttp joins HttpURLConnection (the update system's client, untouched).
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    // Attendance Sync's regression tests open the real database — see the catalog comment on
    // the version. `room-testing` is what lets a schema created by the production
    // `AttendoDatabase` be opened in memory with its migrations and indexes intact.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
