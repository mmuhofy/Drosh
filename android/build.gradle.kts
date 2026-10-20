// Root build file — applied to the root project only.
// Module-level configuration is delegated to convention plugins in build-logic/.
//
// Per AGENT.md §72–103 commit format and §107–135 architecture, no module
// should re-declare AGP / Compose / Hilt boilerplate. Each module's
// build.gradle.kts only declares:
//   - the Drosh convention plugin ids it needs
//   - the modules / catalog libraries it consumes
//   - module-specific options (namespace, test instrumentation, etc.)

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply true
}

// Spotless — repository-wide code formatting checks.
// Convention plugins inherit formatting via `ktlint` automatically because
// Spotless is configured here at the root level.
configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
        target("**/*.kt")
        targetExclude(
            "**/build/**",
            "**/build-logic/**/build/**",
            "**/.gradle/**",
        )
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_filename" to "enabled",
                    "ktlint_standard_function-naming" to "enabled",
                    "ij_kotlin_imports_layout" to "ascii",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("**/*.md", "**/.gitignore", "**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Convenience cleanup task — runs `clean` on all subprojects.
tasks.register<Delete>("cleanAll") {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { sub ->
        dependsOn("${sub.name}:clean")
    }
}

// ── Module boundaries ────────────────────────────────────────────────────────
//
// Rules are specified in docs/ARCHITECTURE.md §3. The check lives here rather
// than in a build-logic plugin on purpose: applying a build-logic plugin to the
// root project puts the whole plugin jar on the build classpath with an unknown
// version, which then collides with the aliases the modules use ("already on
// the classpath with an unknown version").
//
// Each rule is `module|forbidden-package-prefix|allowed`. `allowed` is a
// ratchet, not a licence: it records debt that a phase is going to remove, and
// the phase that removes the imports must lower the number.

val boundaryRules = listOf(
    // :domain is pure Kotlin — no Android, no Drosh module.
    "domain|android|0",
    "domain|androidx|0",
    "domain|dev.drosh.data|0",
    "domain|dev.drosh.terminal|0",
    "domain|dev.drosh.terminal.engine|0",
    "domain|dev.drosh.linux|0",
    "domain|dev.drosh.ui|0",
    "domain|dev.drosh.ssh|0",
    "domain|dev.drosh.agent|0",
    // :linux is the Linux userland — no UI, no session, no engine.
    "linux|dev.drosh.terminal|0",
    "linux|dev.drosh.terminal.engine|0",
    "linux|dev.drosh.data|0",
    "linux|dev.drosh.ui|0",
    "linux|androidx.compose|0",
    // :terminal owns sessions; it never draws.
    "terminal|dev.drosh.ui|0",
    "terminal|dev.drosh.app|0",
    "terminal|androidx.compose|0",
    // :ui is presentation over domain only.
    "ui|dev.drosh.terminal|0",
    "ui|dev.drosh.terminal.engine|0",
    // Known debt — must reach 0. `data -> terminal` clears in P3, `ssh` in P5.
    // It was 22 before the guest moved to its own module: the nine imports of
    // the rootfs, the bootstrap and the archive were part of it.
    "features/ssh|dev.drosh.terminal|1",
    "data|dev.drosh.terminal|13",
)

// Rules inside one module: the files of a source package must not import a
// forbidden package.
//
// The engine half of `:terminal` is a library the session half drives — the
// renderer, the JNI surface and the view know nothing about panes or blocks.
// That was a module boundary once; now that both halves live in `:terminal`,
// the rule is a package one, and it is still mechanical: the checker reads the
// package of each file and its imports.
//
// The format is `source package|forbidden package`.
val internalRules = listOf(
    "dev.drosh.terminal.engine|dev.drosh.terminal.session",
    "dev.drosh.terminal.engine|dev.drosh.linux",
    "dev.drosh.terminal.engine|dev.drosh.data",
    "dev.drosh.terminal.engine|dev.drosh.agent",
    "dev.drosh.terminal.engine|dev.drosh.ssh",
    "dev.drosh.terminal.engine|dev.drosh.ui",
)

// Modules the plan expects but that are not in the tree yet. A rule may name
// one of these: the checker then asserts that the module is *still* absent, so
// a rule written for a module that has not been built cannot pass by accident,
// and cannot be forgotten when the module arrives — the checker says so.
//
// :linux is not here any more: the module exists, so its rules are enforced.
val plannedModules = emptyList<String>()

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails when a module imports a package its layer forbids."

    val projectRoot = layout.projectDirectory.asFile
    val rules = boundaryRules
    val planned = plannedModules
    val internal = internalRules

    doLast {
        val failures = mutableListOf<String>()
        val plannedFound = linkedSetOf<String>()
        var knownDebt = 0

        for (entry in rules) {
            val (module, forbidden, allowedText) = entry.split('|')
            val allowed = allowedText.toInt()
            val sourceDir = File(projectRoot, "$module/src/main")
            if (!sourceDir.isDirectory) {
                if (module in planned) {
                    plannedFound += module
                } else {
                    failures += "$module has no src/main — a boundary rule names a module that is not in the tree."
                }
                continue
            }
            if (module in planned) {
                failures += "$module is in the planned list but exists now — move its rules out of plannedModules."
            }

            val hits = mutableListOf<String>()
            for (file in sourceDir.walkTopDown()) {
                if (!file.isFile) continue
                if (file.extension != "kt" && file.extension != "java") continue

                var lineNumber = 0
                for (line in file.readLines()) {
                    lineNumber++
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("import ")) continue

                    val fqn = trimmed
                        .removePrefix("import ")
                        .removeSuffix(";")
                        .substringBefore(" as ")
                        .trim()
                    if (fqn != forbidden && !fqn.startsWith("$forbidden.")) continue

                    val path = file.relativeTo(projectRoot).invariantSeparatorsPath
                    hits += "  $path:$lineNumber  $fqn"
                }
            }

            knownDebt += allowed
            when {
                hits.size > allowed -> {
                    failures += buildString {
                        appendLine("$module imports $forbidden ${hits.size} time(s); the boundary allows $allowed.")
                        hits.take(20).forEach { appendLine(it) }
                        if (hits.size > 20) appendLine("  … ${hits.size - 20} more")
                    }
                }

                hits.size < allowed -> {
                    failures += "$module -> $forbidden: ${hits.size} import(s), baseline says $allowed. " +
                        "Lower the baseline in build.gradle.kts."
                }
            }
        }

        // Rules inside one module. A file belongs to the rule when its own
        // package is the source package; its imports are checked the same way
        // a module's are.
        for (entry in internal) {
            val (sourcePackage, forbidden) = entry.split('|')
            val module = sourcePackage.removePrefix("dev.drosh.").substringBefore('.')
            val sourceDir = File(projectRoot, "$module/src/main")
            if (!sourceDir.isDirectory) continue

            for (file in sourceDir.walkTopDown()) {
                if (!file.isFile) continue
                if (file.extension != "kt" && file.extension != "java") continue
                val text = file.readText()
                val packageName = Regex("^package\\s+([\\w.]+)", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)
                if (packageName != sourcePackage) continue

                var lineNumber = 0
                for (line in text.lines()) {
                    lineNumber++
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("import ")) continue
                    val fqn = trimmed
                        .removePrefix("import ")
                        .removeSuffix(";")
                        .substringBefore(" as ")
                        .trim()
                    if (fqn != forbidden && !fqn.startsWith("$forbidden.")) continue
                    val path = file.relativeTo(projectRoot).invariantSeparatorsPath
                    failures += "$sourcePackage imports $forbidden — $path:$lineNumber  $fqn"
                }
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module boundary violation — see docs/ARCHITECTURE.md §3.")
                    appendLine()
                    failures.forEach { appendLine(it.trimEnd()) }
                },
            )
        }

        val summary = "module boundaries: ${rules.size} rules enforced, $knownDebt known debt import(s)" +
            if (plannedFound.isEmpty()) "" else ", ${plannedFound.size} planned module(s) not built yet: ${plannedFound.joinToString()}"
        logger.lifecycle(summary)
    }
}

// Every module's `check` runs the boundary task, so `./gradlew check` is the
// whole gate and nobody has to remember a second command.
subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(":checkModuleBoundaries")
    }
}
