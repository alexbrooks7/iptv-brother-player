import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Release signing is read from keystore.properties (gitignored) so that a fresh
// clone still builds: if the file is absent we fall back to the debug keystore
// and `assembleRelease` produces a sideloadable — but not store-uploadable —
// APK. Store submission needs a real key; see README "Signing & store builds".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

// Pawns.app bandwidth-sharing API key, read from local.properties (gitignored)
// rather than tracked source. Blank by default and deliberately so: PawnsManager
// treats a blank key as "feature not available", which means a fresh clone
// builds an app that never mentions sharing and never asks for consent, instead
// of shipping a half-configured version of a feature that routes other people's
// traffic through the user's connection.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val pawnsApiKey: String = localProps.getProperty("pawns.apiKey", "")

// PostHog credentials, same reasoning and same file as the Pawns key above —
// not secret (a client-embedded project key has the same threat model as a GA
// measurement ID), but kept out of tracked source so a fresh clone builds with
// analytics simply off instead of every contributor needing to edit tracked
// files to get a working build.
val postHogApiKey: String = localProps.getProperty("posthog.apiKey", "")
val postHogHost: String = localProps.getProperty("posthog.host", "https://us.i.posthog.com")

val appVersionName = "1.0.1"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.iptv.player"
    // 36 because the Pawns SDK depends on androidx.core 1.17.0, which refuses
    // to be consumed below it. Note this is *compileSdk* only — the set of APIs
    // available to compile against. `targetSdk` stays at 35, so none of the
    // API 36 runtime behaviour changes are opted into, and `minSdk` stays at
    // 24, so no device loses support.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iptv.player"
        // 24 rather than the brief's suggested 28: Fire OS 6 devices (Fire TV
        // Stick 2nd gen, some Fire TV Edition sets) report API 25 and are still
        // a real slice of the Fire TV install base. Nothing in the app needs
        // 28+ unconditionally — the handful of newer APIs are version-gated.
        minSdk = 24
        targetSdk = 35
        // Must strictly increase on every published release: Android refuses
        // to install an APK whose versionCode is lower than the installed
        // one, so forgetting to bump this is what turns an update into "you
        // have to uninstall first".
        versionCode = 2
        versionName = appVersionName

        // Some IPTV providers reject or throttle the stock OkHttp/ExoPlayer
        // agent. Overridable per source in Settings; this is just the default.
        buildConfigField("String", "DEFAULT_USER_AGENT", "\"IPTVBrotherPlayer/$appVersionName\"")
        buildConfigField("String", "PAWNS_API_KEY", "\"$pawnsApiKey\"")
        buildConfigField("String", "POSTHOG_API_KEY", "\"$postHogApiKey\"")
        buildConfigField("String", "POSTHOG_HOST", "\"$postHogHost\"")
    }

    // Two ways this app reaches a device, gated on a single axis. Same
    // codebase and same release signing key either way; what differs is
    // which flavour has the Pawns bandwidth-sharing SDK on its classpath at
    // all — see the sideloadImplementation/PawnsManager comments below and
    // README "Store vs sideload builds".
    flavorDimensions += "distribution"
    productFlavors {
        // What is already live: sideloaded only, bandwidth sharing enabled.
        // Unsuffixed applicationId so this flavour is byte-for-byte what the
        // existing GitHub release pipeline and permanent download link
        // already produce — adding the dimension must not silently change
        // what that path builds.
        create("sideload") {
            dimension = "distribution"
        }
        // Built for the Play Store and the Amazon Appstore. Both prohibit
        // apps that route other users' traffic through a device, so this
        // flavour must not merely disable that feature but never link the
        // SDK that provides it — see PawnsManager in each source set.
        //
        // Suffixed applicationId so a store build installs side by side with
        // a sideload build during testing rather than overwriting it. This
        // is the one thing to confirm deliberately before the first Play
        // Console / Amazon Developer Console upload: an applicationId cannot
        // change after a store listing exists, so treat this default as a
        // placeholder to approve, not a foregone conclusion.
        create("store") {
            dimension = "distribution"
            applicationIdSuffix = ".store"
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        /**
         * Release-optimised, but measurable — and installable over the debug
         * app so it profiles against a real imported playlist.
         *
         * Performance cannot be judged from a debug build. `debuggable=true`
         * makes ART skip its optimising compiler entirely, and with no R8 pass
         * every Compose call stays a real megamorphic call. Numbers from that
         * build are inflated several-fold and, worse, inflated *unevenly*, so
         * they mislead about where the time goes rather than merely being
         * pessimistic.
         *
         * Sharing the debug applicationId is deliberate: a playlist of a few
         * thousand channels with a real EPG behind it is the workload worth
         * measuring, and re-importing one into a separate app id before every
         * measurement is how benchmarks end up being run against empty
         * databases instead.
         */
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            // Optionally keep symbols so a simpleperf capture is readable:
            //   ./gradlew assembleBenchmark -PbenchmarkSymbols
            if (project.hasProperty("benchmarkSymbols")) {
                isMinifyEnabled = false
                isShrinkResources = false
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API < 26 (we support Fire OS 6 at API 25).
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    lint {
        // A lint failure should not block a local build; CI runs it explicitly.
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Opt-in reports on which composables are skippable and which are not, written
// to build/compose_reports. A composable that is *restartable but not
// skippable* re-executes every time its parent does, no matter what its
// arguments are — on a screen that recomposes per D-pad press, that is the
// difference between redrawing one panel and redrawing a thousand-row list.
// Enable with: ./gradlew assembleDebug -PcomposeMetrics
if (project.hasProperty("composeMetrics")) {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_reports")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.documentfile)
    // Installs src/main/baseline-prof.txt at first run. The platform does this
    // itself from API 31; this library is what makes the profile take effect on
    // the older Fire OS and AOSP TV builds that make up much of the install
    // base, which are also the slowest devices and the ones that need it most.
    implementation(libs.androidx.profileinstaller)
    // Only on the sideload flavour's classpath. This, not a runtime flag, is
    // what keeps the SDK's classes out of the store build's APK — see
    // PawnsManager in src/sideload and src/store.
    "sideloadImplementation"(libs.pawns.sdk)
    // Plain analytics, not the sharing SDK — no store restriction applies,
    // so this stays common to both flavours.
    implementation(libs.posthog.android)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    testImplementation("junit:junit:4.13.2")
}
