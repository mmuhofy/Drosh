package dev.drosh

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies Room + KSP for the `data` module.
 *
 * Per AGENT.md §121 + MEMORYBANK.md §52: Room 2.8.4 with FTS5 is used for
 * Command DNA, session history, error DNA fixes, aliases, etc.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("androidx.room")
            }

            dependencies {
                add("implementation", libs().findLibrary("room.runtime").get())
                add("implementation", libs().findLibrary("room.ktx").get())
                add("ksp", libs().findLibrary("room.compiler").get())
                add("testImplementation", libs().findLibrary("room.testing").get())
            }
            // Schema export location is configured in :data module's
            // android { room { schemaDirectory(...) } } block once entities
            // are added (Phase 6 — Command DNA).
        }
    }
}
