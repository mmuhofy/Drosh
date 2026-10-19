// build-logic uses its own settings file so it can declare a version catalog
// that mirrors the root `gradle/libs.versions.toml`. This is needed because
// `includeBuild` is a composite build and does NOT inherit the parent's
// dependencyResolutionManagement block.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
