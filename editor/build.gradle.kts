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
        // Required by language-textmate: it uses java.time APIs that do not
        // exist below API 33, and without this it throws NoClassDefFoundError at
        // runtime on API 26-32 — which is every device this app supports. The
        // version is the one upstream prescribes for the textmate module.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // Must be the same coordinate as :app's, or the two modules desugar with
    // different libraries and the packaged app does not link.
    coreLibraryDesugaring(libs.desugar.jdk.libs)

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

    // Syntax highlighting via TextMate. Pinned to 0.24.3, not 0.24.6: the latter
    // is compiled with Kotlin 2.3.10 and its metadata is unreadable by this
    // project's KGP 2.2.0. Measured with javap on the real artifacts.
    implementation(libs.sora.language.textmate)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}