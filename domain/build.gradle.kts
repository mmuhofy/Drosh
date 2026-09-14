// domain/ — pure Kotlin: UseCases, repository interfaces, models.
//
// Per AGENT.md §115-118: "Pure Kotlin only. Zero Android imports unless
// unavoidable — flag explicitly."
// Per MEMORYBANK.md §77-83, the domain layer contains all entity interfaces
// and UseCases that data/, agent/, terminal/, ssh/ implement.

plugins {
    alias(libs.plugins.dev.drosh.kotlin.library)
    alias(libs.plugins.dev.drosh.kotlin.serialization)
}

// No Android types are permitted in this module.
// The Kotlin JVM plugin is sufficient — no AGP dependency needed.
//
// Coroutines are pure-Kotlin and thus permitted here.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
