package dev.drosh

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies the Compose Compiler Kotlin plugin (`org.jetbrains.kotlin.plugin.compose`)
 * and wires Compose BOM-managed dependencies for any Android module that needs
 * Compose (app, ui, design-system, and any future widgets module).
 *
 * Other modules (data, agent, terminal, ssh, domain, core) MUST NOT apply this
 * plugin — see AGENT.md §143 ("terminal/ is isolated — never imported directly by ui/").
 *
 * AGP 8.13 (and Kotlin 2.2.x line) no longer exposes the synthetic
 * CommonExtension type as a Gradle extension. We instead explicitly enable
 * `buildFeatures.compose = true` on whatever Android extension is registered
 * (LibraryExtension for `iris.android.library` consumers, ApplicationExtension
 * for `iris.android.application` consumers).
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            // Enable Compose build feature on whichever Android extension is registered.
            // Order matters: library plugins are typically applied before this convention
            // plugin in :ui / :design-system; :app's application plugin applies later.
            extensions.findByType(LibraryExtension::class.java)?.apply {
                buildFeatures {
                    compose = true
                }
            }
            if (extensions.findByType(LibraryExtension::class.java) == null) {
                extensions.findByType(ApplicationExtension::class.java)?.apply {
                    buildFeatures {
                        compose = true
                    }
                }
            }

            dependencies {
                val bom = platform(libs().findLibrary("compose.bom").get())
                add("implementation", bom)
                add("androidTestImplementation", bom)

                add("implementation", libs().findLibrary("compose.ui").get())
                add("implementation", libs().findLibrary("compose.ui.graphics").get())
                add("implementation", libs().findLibrary("compose.ui.tooling.preview").get())
                add("implementation", libs().findLibrary("compose.material3").get())
                add("implementation", libs().findLibrary("compose.material.icons.extended").get())
                add("implementation", libs().findLibrary("compose.foundation").get())
                add("implementation", libs().findLibrary("compose.runtime").get())
                add("debugImplementation", libs().findLibrary("compose.ui.tooling").get())
                add("debugImplementation", libs().findLibrary("compose.ui.test.manifest").get())
                add("androidTestImplementation", libs().findLibrary("compose.ui.test.junit4").get())
            }
        }
    }
}
