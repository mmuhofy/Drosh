// terminal/ — the terminal: the Rust core's Kotlin side, and the sessions
// that drive it.
//
//   engine/                  the Rust core: JNI, the GL renderer, the input
//                            encoder, the view
//   (the rest of the files) sessions, panes, blocks, shell launch
//
// The engine half is a library the session half drives: it does not know about
// sessions, and the boundary checker says so (docs/ARCHITECTURE.md §3).
//
// Per AGENT.md §125-128: "Isolated — only data/ and agent/ interact with it."
// Per AGENT.md §143: "terminal/ is isolated — never imported directly by ui/"

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.compose)
    alias(libs.plugins.dev.drosh.kotlin.serialization)
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
        jniLibs {
            // libdrosh_core.so is the Rust core, cross-compiled by CI and
            // dropped into src/main/jniLibs/arm64-v8a/ before Gradle runs. It
            // comes from CI, never from a local cargo build; pickFirsts would
            // only mask a duplicated-build mistake.
            keepDebugSymbols += "**/libdrosh_core.so"
        }
    }
}

dependencies {
    // domain is pure Kotlin and depends on zero Android types.
    // terminal explicitly creates a bridge from Android APIs to PTY engine.
    api(project(":domain"))
    // The guest: its rootfs, its bootstrap and the launcher that execs a
    // shell inside it.
    implementation(project(":guest"))
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
