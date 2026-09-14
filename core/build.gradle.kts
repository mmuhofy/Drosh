// core/ — shared utilities, extensions, constants.
//
// Per AGENT.md §135: "util/ → Constants, extension functions, shared helpers."
// The core module exposes pure Kotlin helpers (no Android types) AND a small
// surface of Android-specific helpers (ContextExtensions, ResourceProvider).
// Modules that need said helpers declare a dep on :core.

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.kotlin.serialization)
}

android {
    namespace = "dev.drosh.core"
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
