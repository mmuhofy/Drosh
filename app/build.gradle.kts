// app/ — Android entry point.
// Per AGENT.md §109: Hilt, Navigation, MainActivity live here.
// Per AGENT.md §112-135: app may depend on ui, data, agent, terminal, ssh,
//                        but never contains business logic itself.

import java.util.Properties

plugins {
    alias(libs.plugins.iris.android.application)
    alias(libs.plugins.iris.android.compose)
    alias(libs.plugins.iris.android.hilt)
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
    namespace = "com.iris.irisshell"

    defaultConfig {
        applicationId = "com.iris.irisshell"
        // Version code / name sourced from build config injected by convention plugin.
        targetSdk = 28

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

    buildTypes {
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
            isMinifyEnabled = false
            isShrinkResources = false
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
    // Module dependencies — see AGENT.md data flow diagram (§149-163).
    implementation(project(":core"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":agent"))
    implementation(project(":terminal"))
    implementation(project(":ssh"))
    implementation(project(":ui"))
    implementation(project(":design-system"))

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
