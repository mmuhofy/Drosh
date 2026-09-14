// build-logic — convention plugins for Drosh.
//
// Exposes the following Gradle convention plugins (each as a Kotlin class
// in src/main/kotlin/dev/drosh/*.kt):
//
//   dev.drosh.android.application     — AGP application + Kotlin Android + SDK + Java 17
//   dev.drosh.android.library         — AGP library + Kotlin Android + SDK + Java 17
//   dev.drosh.android.compose         — Compose Compiler plugin + Compose BOM
//   dev.drosh.android.hilt            — Hilt + KSP for Android modules
//   dev.drosh.android.room            — Room + KSP for data module
//   dev.drosh.kotlin.library          — Kotlin JVM plugin for pure-Kotlin modules (domain/)
//   dev.drosh.kotlin.serialization    — kotlinx.serialization plugin
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
            id = "dev.drosh.android.application"
            implementationClass = "dev.drosh.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "dev.drosh.android.library"
            implementationClass = "dev.drosh.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "dev.drosh.android.compose"
            implementationClass = "dev.drosh.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "dev.drosh.android.hilt"
            implementationClass = "dev.drosh.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "dev.drosh.android.room"
            implementationClass = "dev.drosh.AndroidRoomConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "dev.drosh.kotlin.library"
            implementationClass = "dev.drosh.KotlinLibraryConventionPlugin"
        }
        register("kotlinSerialization") {
            id = "dev.drosh.kotlin.serialization"
            implementationClass = "dev.drosh.KotlinSerializationConventionPlugin"
        }
    }
}
