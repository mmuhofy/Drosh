// build-logic — convention plugins for Drosh.
//
// Exposes the following Gradle convention plugins (each as a Kotlin class
// in src/main/kotlin/iris/*.kt):
//
//   iris.android.application     — AGP application + Kotlin Android + SDK + Java 17
//   iris.android.library         — AGP library + Kotlin Android + SDK + Java 17
//   iris.android.compose         — Compose Compiler plugin + Compose BOM
//   iris.android.hilt            — Hilt + KSP for Android modules
//   iris.android.room            — Room + KSP for data module
//   iris.kotlin.library          — Kotlin JVM plugin for pure-Kotlin modules (domain/)
//   iris.kotlin.serialization    — kotlinx.serialization plugin
//
// Module-level build files reference these by id rather than re-wiring
// AGP/Compose/Hilt each time.

plugins {
    `kotlin-dsl`
}

group = "dev.drosh.buildlogic"

// Plugin classpath — required for `plugins { id(...) }` blocks in the
// convention plugin classes to resolve external plugins at compile time.
dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.gradle.plugin)
    implementation(libs.kotlin.serialization.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "iris.android.application"
            implementationClass = "iris.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "iris.android.library"
            implementationClass = "iris.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "iris.android.compose"
            implementationClass = "iris.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "iris.android.hilt"
            implementationClass = "iris.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "iris.android.room"
            implementationClass = "iris.AndroidRoomConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "iris.kotlin.library"
            implementationClass = "iris.KotlinLibraryConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "iris.kotlin.serialization"
            implementationClass = "iris.KotlinSerializationConventionPlugin"
        }
    }
}
