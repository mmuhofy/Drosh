pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // OSS Snapshots — used for bleeding-edge AndroidX pre-release artifacts
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
    }
}

rootProject.name = "Drosh"

// Application entry point
include(":app")

// Feature / layer modules — see AGENT.md §107–135 for layer responsibilities.
include(":core")
include(":domain")
include(":data")
include(":agent")
include(":terminal")
include(":ssh")
include(":ui")
include(":design-system")
// Native code editor (Rosemoe/sora-editor). Its own module so the LGPL-2.1
// surface stays isolated and :ui keeps its "no :terminal" rule.
include(":editor")

// Convention plugins — included as a composite build so its plugin ids
// (e.g. `dev.drosh.android.library`) are visible to all subprojects.
includeBuild("build-logic")
