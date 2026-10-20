// app/ — Android entry point.
// Per AGENT.md §109: Hilt, Navigation, MainActivity live here.
// Per AGENT.md §112-135: app may depend on ui, data, agent, terminal, ssh,
//                        but never contains business logic itself.

import java.util.Properties

plugins {
    alias(libs.plugins.dev.drosh.android.application)
    alias(libs.plugins.dev.drosh.android.compose)
    alias(libs.plugins.dev.drosh.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Load signing config from keystore.properties (root of repo) if it exists.
// This file is gitignored for production; for Drosh we keep a stable
// debug signing key checked into git (see .gitignore and keystore.properties)
// so that CI-built APKs and locally-built APKs share a signing identity and
// can be installed over each other without uninstalling.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "dev.drosh"

    defaultConfig {
        // The side-by-side development identity. The application this is
        // installed next to is `dev.drosh`; a different applicationId is what
        // makes the two installable at once, and every hardcoded authority and
        // permission below follows it so the installs do not collide
        // (INSTALL_FAILED_DUPLICATE_PERMISSION is the failure if they do).
        applicationId = "dev.drosh.my"
        // Version code / name sourced from build config injected by convention plugin.
        targetSdk = 28

        // The launcher reads its label from the resource, and two apps called
        // "Drosh" in the drawer is a coin toss; the generated value wins over
        // values/strings.xml.
        resValue("string", "app_name", "Drosh Dev")

        // AndroidJUnitRunner is configured by the convention plugin.
    }

    signingConfigs {
        // Common debug signing config used by both debug and release builds in
        // Phase 1 — keeps a single signing identity across all installers.
        if (keystoreProperties.isNotEmpty()) {
            create("debug-keystore") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // The commit this build was made from, so the About screen can name
        // the exact code rather than a version that covers many commits. A
        // shallow checkout or a missing git answers "unknown" — a build that
        // cannot say where it came from is still a build.
        buildConfigField("String", "GIT_SHA", "\"$gitShortSha\"")
    }

    compileOptions {
        // Required by :editor, which depends on sora-editor's TextMate module:
        // it uses java.time APIs that do not exist below API 33, and this app
        // supports API 26. Must stay in step with :editor's version.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        // Debug stays unshrunk: readable stack traces, fast iteration, and the
        // compose tooling, which reaches for classes by name.
        getByName("debug") {
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("debug-keystore")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("release") {
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("debug-keystore")
            }
            // Release ships what ships: shrinking on. This is a real change in
            // what reaches the device — R8 rewrites, so anything reached by
            // name at runtime has to survive the rules in proguard-rules.pro.
            // Debug stays unshrunk on purpose, which is why the release
            // workflow is dispatched against this branch before it merges:
            // a shrinking break shows up as a failed release build, not as a
            // crash nobody can reproduce on a debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Comprehensiveness for native libs included from terminal module.
            pickFirsts += setOf("lib/**/libtermux.so")
        }
    }

    // Per-ABI APK splits — we ship arm64-v8a only for v1.0 to keep CI artifact
    // count and the APK download size tight. About 99%+ of today's Android
    // devices are arm64 (Pixel / Samsung / OnePlus / Xiaomi / etc.); x86_64 and
    // armeabi-v7a may be added later without changing the workflow — the
    // `include(...)` set is the source of truth.
    //
    // Output artifact produced by `assembleRelease`:
    //   app-arm64-v8a-release-unsigned.apk
    // (No universal APK is created.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

dependencies {
    // Must match :editor's coordinate — see the compileOptions comment there.
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Module dependencies — see AGENT.md data flow diagram (§149-163).
    implementation(project(":core"))
    // The engine rides in every debug APK from R1 on; its .so is asserted in
    // CI so a missing cross-build fails here, not on the device.
    implementation(project(":engine"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":agent"))
    implementation(project(":terminal"))
    implementation(project(":guest"))
    implementation(project(":ssh"))
    implementation(project(":ui"))
    implementation(project(":theme"))
    implementation(project(":editor"))
    implementation(libs.androidx.datastore.preferences)

    // AndroidX entry-point
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    // WorkManager — cron / agent watch scheduled background work.
    implementation(libs.androidx.work.runtime.ktx)



    // Splash screen + exported launcher theme (Material You launch).
    debugImplementation(libs.compose.ui.tooling)

    // Unit / instrumented tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}


/**
 * The commit this build was made from.
 *
 * `providers.exec` rather than a bare `ProcessBuilder`: the configuration
 * cache records the command as an input, so this is a tracked, cached
 * lookup rather than an untracked process at configuration time. Anything
 * that goes wrong — no git, a shallow checkout — answers "unknown"; a build
 * that cannot say where it came from is still a build.
 */
val gitShortSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootDir
}.standardOutput.asText.map { it.trim() }.getOrElse("unknown")
