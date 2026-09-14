// Root build file — applied to the root project only.
// Module-level configuration is delegated to convention plugins in build-logic/.
//
// Per AGENT.md §72–103 commit format and §107–135 architecture, no module
// should re-declare AGP / Compose / Hilt boilerplate. Each module's
// build.gradle.kts only declares:
//   - the Drosh convention plugin ids it needs
//   - the modules / catalog libraries it consumes
//   - module-specific options (namespace, test instrumentation, etc.)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply true
}

// Spotless — repository-wide code formatting checks.
// Convention plugins inherit formatting via `ktlint` automatically because
// Spotless is configured here at the root level.
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("**/*.kt")
        targetExclude(
            "**/build/**",
            "**/build-logic/**/build/**",
            "**/.gradle/**",
        )
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_filename" to "enabled",
                    "ktlint_standard_function-naming" to "enabled",
                    "ij_kotlin_imports_layout" to "ascii",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("**/*.md", "**/.gitignore", "**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Convenience cleanup task — runs `clean` on all subprojects.
tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { sub ->
        dependsOn("${sub.name}:clean")
    }
}
