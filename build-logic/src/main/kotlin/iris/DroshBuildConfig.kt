// Internal helper used by all Iris convention plugins to share the common
// Android configuration values (SDK versions, Java toolchain, etc.).
//
// Centralizing these constants here guarantees that any change to the
// targeted Android API surface is reflected across every module.

package iris

import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal object IrisBuildConfig {
    const val APPLICATION_ID = "com.iris.irisshell"
    const val MIN_SDK = 26
    const val TARGET_SDK = 28
    const val COMPILE_SDK = 36
    val JAVA_VERSION = JavaVersion.VERSION_17
    const val KOTLIN_JVM_TARGET = "17"
}

internal fun org.gradle.api.Project.libs(): org.gradle.api.artifacts.VersionCatalog =
    extensions.getByType<VersionCatalogsExtension>().named("libs")
