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
            // No termux .so — engine uses Rust core only.
        }
        jniLibs {
            // The Ghostty engine and our own native half. Both arrive the same
            // way — from CI, never from a local build — and both keep their
            // symbols so a tombstone names a function rather than an address.
            keepDebugSymbols += "**/libghostty-vt.so"
            keepDebugSymbols += "**/libdrosh.so"
        }
    }
}

dependencies {
    // domain is pure Kotlin and depends on zero Android types.
    // terminal explicitly creates a bridge from Android APIs to PTY engine.
    api(project(":domain"))
    // The guest: its rootfs, its bootstrap and the launcher that execs a
    // shell inside it.
    implementation(project(":linux"))
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
