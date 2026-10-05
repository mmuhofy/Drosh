// agent/ — Phase 6 Agent Intelligence.
//
// Depends on :domain interfaces only (AGENT.md: "agent/ depends only on
// domain/ interfaces, connected via Hilt").
//
// Layout:
//   runtime/   the agent loop — bounded, emits AgentEvent to the UI
//   provider/  ChatAdapter implementations (one per wire protocol) + registry
//   stream/    tool-call accumulator shared by the SSE adapters
//   tool/      Tool implementations + ToolRegistry
//   auth/      credential vault (EncryptedSharedPreferences)
//   di/        Hilt module — no logic
//
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
    implementation(project(":terminal"))

    // diff utilities — write_file diff/approve flow (AGENT.md §249)
    implementation(libs.diff.utils)

    // networking — for web_search tool (Tavily) and direct LLM streaming
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

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
