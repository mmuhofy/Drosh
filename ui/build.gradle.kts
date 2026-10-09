// ui/ — all Compose screens: Terminal, Sessions, SSH Manager, Settings, HUD, etc.
//
// Per AGENT.md §110-111: "Compose screens, components, ViewModels. No direct
// data/agent/terminal access."
// Per AGENT.md §139: "ui/ never imports from data/, agent/, terminal/, or ssh/ directly"
// Per AGENT.md §145-147: ViewModels expose StateFlow<UiState>; UI collects
// with collectAsStateWithLifecycle().

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.compose)
    alias(libs.plugins.dev.drosh.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.drosh.ui"

    defaultConfig {
        // Coil needs network security config for theme store previews — fine on Android.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // ui sees ONLY the domain interfaces + design-system — it does NOT see data/,
    // agent/, terminal/, or ssh/ per AGENT.md §139.
    api(project(":domain"))
    implementation(project(":core"))
    implementation(project(":design-system"))

    // AndroidX Lifecycle + ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // OkHttp + kotlinx-serialization — for LLM provider model fetching
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Hilt + ViewModel integration
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)

    // Coil — the device photo in the drawer header. Was held back for a theme
    // store that never landed; in use now for the device image.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Lottie — onboarding animations (MEMORYBANK.md §59).
    // OMITTED in Phase 1 (boot splash + simple Compose start screen cover
    // the user; the onboarding wizard is Phase 2). Re-add when needed.
    // implementation(libs.lottie.compose)

    // Lucide icons — via ardasoyturk lucide-android library from Maven Central.
    // Provides 1,669 Compose ImageVector icons as extension properties on
    // compose.icons.LucideIcons. No Kotlin version conflicts (stdlib 2.0.0
    // is backward-compatible with project's Kotlin 2.2.0).
    implementation(libs.lucide.compose)

    // Real backdrop blur for the glass surfaces on the agent screens. Android 12+
    // renders an offscreen layer and blurs it; below that Haze draws a scrim,
    // because RenderEffect does not exist before API 31.
    implementation(libs.haze)

    // Markdown for assistant messages. Pure Java, so it adds nothing to the
    // Kotlin toolchain; the AST is rendered by MarkdownText in this module.
    implementation(libs.flexmark)
    implementation(libs.flexmark.util.ast)
    // Tables are a separate extension in flexmark, not part of the core parser.
    implementation(libs.flexmark.ext.tables)

    // coroutines
    implementation(libs.kotlinx.coroutines.android)

    // logging
    implementation(libs.timber)

    // unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}

// (See comment in AndroidHiltConventionPlugin.kt for why we don't patch
// task ordering here.)
