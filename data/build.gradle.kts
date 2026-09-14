// data/ — repository implementations, local (Room), remote (LLM, SSH).
// Per AGENT.md §119-121: implements interfaces from domain/.
// Per MEMORYBANK.md §85-90: terminal manager, agent streamer, Room DAOs,
// remote LLM / SSH clients, theme store client.

plugins {
    alias(libs.plugins.iris.android.library)
    alias(libs.plugins.iris.android.room)
    alias(libs.plugins.iris.android.hilt)
    alias(libs.plugins.iris.kotlin.serialization)
}

android {
    namespace = "dev.drosh.data"
}

room {
    // Phase 6 schema export target — :data/schemas directory is created now
    // so the Room Gradle plugin accepts the configuration even though no
    // entities exist yet. Once CommandDnaDao / SessionDao / etc. land, this
    // path starts emitting *.json schema snapshots per database version.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":domain"))
    implementation(project(":core"))
    // The bootstrap port lives in :terminal — :data consumes it through this seam
    // so the UI never imports Android-specific types from :terminal.
    implementation(project(":terminal"))

    // storage — DataStore
    implementation(libs.androidx.datastore.preferences)
    // security — encrypted API keys + SSH key vault
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    // networking — OkHttp + SSE for streaming LLM responses
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // coroutines
    implementation(libs.kotlinx.coroutines.android)

    // logging
    implementation(libs.timber)

    // unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
}

// (See comment in AndroidHiltConventionPlugin.kt for why we don't patch
// task ordering here.)
