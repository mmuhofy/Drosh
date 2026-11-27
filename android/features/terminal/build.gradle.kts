// feature/terminal — the terminal screen, its chrome and the state that
// drives them.
//
// This module exists so :app stays a composition root. The screen reaches
// :terminal (sessions and the engine), :linux (guest setup state) and :ui
// (shared panes, session chrome, keys and design components). :ui does not
// depend on this module, so the layer direction stays one-way.

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.compose)
    alias(libs.plugins.dev.drosh.android.hilt)
}

android {
    namespace = "dev.drosh.feature.terminal"
}

dependencies {
    // Public parameters of TerminalScreen.
    api(project(":domain"))
    api(project(":terminal"))
    api(project(":linux"))

    // Shared Compose screens and components the terminal composes.
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":theme"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
