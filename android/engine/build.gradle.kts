// engine/ — byte → pixel: the Rust terminal core (alacritty 0.26 engine, libc
// PTY, swash font, chroot-ng guest) plus the Kotlin bridge that drives it
// (JNI surface, GL ES 3.0 renderer, input encoder, snapshot pump).
//
// Isolation follows docs/AGENT.md: nothing outside `:terminal` imports this
// module. The native library is NOT built here — CI cross-compiles
// `libdrosh_core.so` and drops it into `src/main/jniLibs/arm64-v8a/` before
// Gradle runs (AGENTS.md rule 10 in drosh-terminal-lab: no local Rust builds).

plugins {
    alias(libs.plugins.dev.drosh.android.library)
}

android {
    namespace = "dev.drosh.engine"

    defaultConfig {
        // The Rust core is cross-compiled for arm64-v8a only — real Android
        // devices are arm64, and x86_64 emulation is covered by ABI rounding.
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            // The engine's .so comes from CI, never from a local cargo build;
            // pickFirsts would only mask a duplicated-build mistake.
            keepDebugSymbols += "**/libdrosh_core.so"
        }
    }
}

dependencies {
    // Plain JUnit is enough: the engine's JVM tests are byte-exact contract
    // tests (encoder, mouse reports) with no Android framework involved.
    testImplementation(libs.junit)
}
