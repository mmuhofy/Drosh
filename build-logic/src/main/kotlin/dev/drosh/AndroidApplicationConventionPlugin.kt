package dev.drosh

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Convention plugin applied to the root `:app` application module.
 *
 * Sets up:
 *  - AGP `com.android.application`
 *  - Kotlin Android
 *  - Java 17 toolchain + Kotlin jvmTarget 17
 *  - Common Android SDK levels (minSdk 26, targetSdk 36, compileSdk 36)
 *  - Test fixtures + unit test support
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Resolved before the `with(target)` block, so the version functions are
        // handed the Project explicitly. Inside `extensions.configure<…>` the
        // receiver is the extension, which happens to expose a `project` of its
        // own — relying on which `project` that resolves to is a trap.
        //
        // Named `publishVersion*` rather than `version*` for the same class of
        // reason: `defaultConfig { versionCode = versionCode }` resolves the
        // right-hand side to `ApplicationDefaultConfig.versionCode` — the
        // property being assigned — and tries to reassign a val.
        val publishVersionCode = resolveVersionCode(target)
        val publishVersionName = resolveVersionName(target)

        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(false)
                }
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = DroshBuildConfig.COMPILE_SDK

                defaultConfig {
                    applicationId = DroshBuildConfig.APPLICATION_ID
                    minSdk = DroshBuildConfig.MIN_SDK
                    targetSdk = DroshBuildConfig.TARGET_SDK
                    versionCode = publishVersionCode
                    versionName = publishVersionName
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    vectorDrawables.useSupportLibrary = true
                }

                compileOptions {
                    sourceCompatibility = DroshBuildConfig.JAVA_VERSION
                    targetCompatibility = DroshBuildConfig.JAVA_VERSION
                }

                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                    }
                }
            }
        }
    }
}

/**
 * Where the build's position in history comes from.
 *
 * Read from git, so a commit's version is a property of the commit rather than
 * of whatever the build file last happened to say. `-PversionCode=` overrides it
 * for a release, where the number has to come from the store rather than from
 * this repository.
 */
private fun gitCommitCount(project: Project): Int? = runCatching {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(project.rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    // A non-numeric line here is git's own error text ("fatal: not a git
    // repository"), not a count. A tarball or a source export has no history,
    // and guessing a version for one would be worse than the override.
    process.waitFor()
    val count = output.toIntOrNull() ?: return@runCatching null

    // A shallow clone counts what it was given, not what exists. `actions/
    // checkout` defaults to depth 1, so without this the CI build would come out
    // numbered 10001 forever and every CI APK would be interchangeable with
    // every other — reintroducing the downgrade this numbering exists to prevent.
    if (project.rootDir.resolve(".git").exists() && isShallow(project)) {
        project.logger.warn(
            "Drosh: the git checkout is shallow, so versionCode was derived from " +
                "${count} commit(s) instead of the real history. Fetch the full " +
                "history (CI: actions/checkout with fetch-depth: 0) or pass " +
                "-PversionCode=<n>.",
        )
    }
    count
}.getOrNull()

private fun isShallow(project: Project): Boolean = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--is-shallow-repository")
        .directory(project.rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.equals("true", ignoreCase = true)
}.getOrDefault(false)

/**
 * The version code this build publishes.
 *
 * Monotonic across commits, and deliberately so.
 *
 * Every build used to be versionCode 1. Android refuses to install a build
 * whose versionCode is *lower* than the installed one but accepts an equal
 * one, so checking out an older commit and installing it over a newer one
 * succeeded silently — same application id, same committed debug keystore, no
 * uninstall, and `databases/irisshell.db` carried over untouched at the newer
 * schema version. The older build then had a version for which no migration
 * existed in either direction, and Room threw out of the Hilt graph at startup.
 * The symptom was a crash on a device that had never done anything wrong, and
 * the cure was clearing app data.
 *
 * Deriving the code from the commit count means the ordering is the ordering of
 * the history: an older commit gets a lower code, Android rejects the install,
 * and the database is never opened at a version it does not belong to. The
 * downgrade fallback in DatabaseModule is still there, because history can be
 * rewritten and the count is not a guarantee — but it is now the second line of
 * defence rather than the only one.
 */
private fun resolveVersionCode(project: Project): Int {
    val override = project.findProperty("versionCode")?.toString()?.trim()?.toIntOrNull()
    if (override != null && override > 0) {
        return override
    }
    // Two digits of headroom above the commit count, so hand-written codes and
    // CI counters do not collide with a commit-derived one.
    val commits = gitCommitCount(project)
    val floor = 10_000
    if (commits == null) {
        project.logger.warn(
            "Drosh: could not read a commit count from git; falling back to " +
                "versionCode $floor. Pass -PversionCode=<n> for a real build.",
        )
        return floor
    }
    return floor + commits
}

/**
 * The user-visible version name.
 *
 * `-PversionName=` overrides it; otherwise it follows the commit count so a
 * build is identifiable by eye in an about screen, which a flat "0.1.0" on every
 * build was not.
 */
private fun resolveVersionName(project: Project): String {
    val override = project.findProperty("versionName")?.toString()?.trim()
    if (!override.isNullOrEmpty()) return override
    val commits = gitCommitCount(project) ?: return "0.1.0"
    return "0.1.$commits"
}
