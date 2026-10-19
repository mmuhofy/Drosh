package dev.drosh

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies Hilt + KSP for any Android module that participates in DI.
 *
 * Per AGENT.md §133: Hilt modules live in `di/` (no logic whatsoever), but
 * the actual @Inject / @Module / @HiltViewModel annotations are spread across
 * modules — so EVERY Android module that needs to participate in Hilt's
 * graph applies this plugin.
 *
 * The `app` module additionally applies the top-level `com.google.dagger.hilt.android`
 * plugin (handled by the `iris.android.hilt` plugin automatically — there is no
 * need to apply it manually per-module because Hilt supports aggregation through KSP).
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }

            dependencies {
                add("implementation", libs().findLibrary("hilt.android").get())
                add("ksp", libs().findLibrary("hilt.compiler").get())
                // Hilt + WorkManager integration (cron, agent watch)
                add("implementation", libs().findLibrary("hilt.work").get())
                add("ksp", libs().findLibrary("hilt.work.compiler").get())
                add("implementation", libs().findLibrary("hilt.navigation.compose").get())
            }
        }
    }
}
