// terminal/ — PTY session management, terminal emulator bridge,
// block engine, semantic parser, ghost text engine.
//
// Per AGENT.md §125-128: "Isolated — only data/ and agent/ interact with it."
// Per AGENT.md §143: "terminal/ is isolated — never imported directly by ui/"
// Per MEMORYBANK.md §97-100: engine/, renderer/, input/.

plugins {
    alias(libs.plugins.iris.android.library)
    alias(libs.plugins.iris.android.compose)
    alias(libs.plugins.iris.kotlin.serialization)
}

android {
    namespace = "dev.drosh.terminal"
    // The termux view + emulator Java sources ship as src/main/java/ alongside
    // src/main/kotlin/ in this module. AGP must schedule compileDebugJavaWithJavac
    // before compileDebugKotlin so the Java sources are reachable from Kotlin.
    // We explicitly add both source dirs to be certain the variant builder picks
    // them up.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
        }
    }

    defaultConfig {
        minSdk = 26
        // Required for native arm64 binary support.
        // Prebuilt JNI artifact only contains
        // arm64-v8a (real Android devices = 99% arm64; x86_64 emulator supported by ABI rounding).
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        resources {
            // Ensure JNI .so libs from this module win over conflicting builds.
            pickFirsts += setOf("lib/**/libtermux.so")
        }
    }
}

dependencies {
    // domain is pure Kotlin and depends on zero Android types.
    // terminal explicitly creates a bridge from Android APIs to PTY engine.
    api(project(":domain"))
    implementation(project(":core"))

    // coroutines — dedicated TerminalManager CoroutineScope
    implementation(libs.kotlinx.coroutines.android)

    // timber logging
    implementation(libs.timber)

    // serialization — block / token export to FTS / Commerce context
    implementation(libs.kotlinx.serialization.json)

    // okhttp — UbuntuBootstrap.downloadRootfs() fetches the rootfs archive over HTTPS.
    implementation(libs.okhttp)

    // Room out-requires this module so we don't reference room.DB types
    // from terminal layer (clean architecture).

    // Unit tests: robolectric needed for shadow.syscalls
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
