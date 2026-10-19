package dev.drosh

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies `org.jetbrains.kotlin.plugin.serialization` and adds the
 * kotlinx-serialization-json runtime.
 *
 * Used by any module that consumes / produces JSON (shortcut export/import,
 * theme packs, LLM tool args, SSH config import/export, etc.).
 */
class KotlinSerializationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                add("implementation", libs().findLibrary("kotlinx.serialization.json").get())
            }
        }
    }
}
