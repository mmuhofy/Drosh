// agent/ — AgentLoop, ToolRegistry, MultiStepStreamer, tool implementations.
//
// Per AGENT.md §122-124: "Depends on domain/ interfaces only."
// Per AGENT.md §142: "agent/ depends only on domain/ interfaces, connected via Hilt"
// Per MEMORYBANK.md §92-95: tools, loop, semantic — see §10 (Agent Core).

plugins {
    alias(libs.plugins.dev.drosh.android.library)
    alias(libs.plugins.dev.drosh.android.hilt)
    alias(libs.plugins.dev.drosh.kotlin.serialization)
}

android {
    namespace = "dev.drosh.agent"
}

dependencies {
    api(project(":domain"))
    implementation(project(":core"))

    // diff utilities — write_file diff/approve flow (AGENT.md §249)
    implementation(libs.diff.utils)

    // networking — for web_search tool (Tavily) and direct LLM streaming
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)

    // serialization for tool args (JSON object schema — AGENT.md §234)
    implementation(libs.kotlinx.serialization.json)

    // coroutines
    implementation(libs.kotlinx.coroutines.android)

    // logging
    implementation(libs.timber)

    // unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}
