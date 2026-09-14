package iris

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Convention plugin applied to every Android *library* module
 * (`core`, `data`, `agent`, `terminal`, `ssh`, `ui`, `design-system`).
 *
 * Sets up:
 *  - AGP `com.android.library`
 *  - Kotlin Android
 *  - Java 17 toolchain + Kotlin jvmTarget 17
 *  - Common Android SDK levels (minSdk 26, compileSdk 36)
 *  - Test fixtures support for modular testing
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(false)
                }
            }

            extensions.configure<LibraryExtension> {
                compileSdk = DroshBuildConfig.COMPILE_SDK

                defaultConfig {
                    minSdk = DroshBuildConfig.MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }

                compileOptions {
                    sourceCompatibility = DroshBuildConfig.JAVA_VERSION
                    targetCompatibility = DroshBuildConfig.JAVA_VERSION
                }

                testOptions {
                    unitTests.isReturnDefaultValues = true
                }
            }
        }
    }
}
