package dev.drosh

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Convention plugin for *pure Kotlin* (non-Android) modules.
 *
 * Currently this covers the `domain/` module which per AGENT.md §115–117
 * must remain "Pure Kotlin only — zero Android imports unless unavoidable."
 *
 * The plugin applies:
 *  - `org.jetbrains.kotlin.jvm`
 *  - Java 17 toolchain
 *  - Kotlin jvmTarget 17
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension>("java") {
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(DroshBuildConfig.JAVA_VERSION.majorVersion))
                }
            }

            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(false)
                }
            }
        }
    }
}
