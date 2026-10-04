// editor/ — the native code editor.
//
// Wraps Rosemoe/sora-editor, a third-party Android code editor (LGPL-2.1-or-later).
// Only the editor widget is taken; none of the IDE shell around it. Nothing in
// this module is reimplemented — see SoraCodeEditor.kt for what is delegated
// and what is Drosh's.
//
// This is a separate module for two reasons:
//   1. sora-editor is LGPL. Keeping it behind one module boundary makes the
//      licence surface obvious and greppable instead of smeared across :ui.
//   2. :ui is forbidden from importing :terminal (AGENT.md §139). Putting the
//      editor here keeps that rule intact and leaves :ui free to grow an editor
//      route later without either rule bending.
//
// Depends on :domain only. File access goes through GuestFileRepository, so
// this module never learns that the rootfs is a host directory — same reason
// the terminal layer is not imported here.

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.compose)
    alias(libs.plugins.dev.drosh.android.hilt)
}

android {
    namespace = "dev.drosh.editor"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        // Required by language-textmate, which calls java.time APIs that do not
        // exist below API 33. Only take the dependency once that module lands;
        // desugaring rewrites bytecode across the app for a feature that is not
        // in the first cut.
        // isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":core"))
    implementation(project(":design-system"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    // The editor widget itself. Java-only, so no Kotlin metadata and no clash
    // with this project's Kotlin 2.2.0 — see the pin note in libs.versions.toml.
    implementation(libs.sora.editor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}