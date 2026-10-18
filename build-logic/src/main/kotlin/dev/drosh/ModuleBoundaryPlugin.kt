package dev.drosh

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * `dev.drosh.module-boundary` — enforces the layer rules written down in
 * `docs/ARCHITECTURE.md`.
 *
 * A rule is "module X must not import package Y". Rules whose baseline is not
 * zero yet carry the exact current count, so the debt can only shrink: if a
 * later change removes an import the task fails and asks for the baseline to be
 * lowered, and if a change adds one it fails immediately.
 *
 * The task is wired into every subproject's `check`, so a boundary breach is a
 * red build and not a code-review opinion.
 */
class ModuleBoundaryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val root = target.layout.projectDirectory
        val check = target.tasks.register("checkModuleBoundaries", ModuleBoundaryTask::class.java) { task ->
            task.group = "verification"
            task.description = "Fails when a module imports a package its layer forbids."
            task.projectRoot.set(root)
        }

        // `check` exists on every module that applies the Kotlin or Android
        // plugin; matching lazily keeps this independent of configuration order.
        target.subprojects {
            tasks.matching { it.name == "check" }.configureEach { dependsOn(check) }
        }
    }
}

/** One layer rule: [module] must not import [forbidden] more than [allowed] times. */
data class BoundaryRule(
    val module: String,
    val forbidden: String,
    val allowed: Int = 0,
)

abstract class ModuleBoundaryTask : DefaultTask() {
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = projectRoot.get().asFile.toPath()
        val failures = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var knownDebt = 0

        RULES.forEach { rule ->
            val sourceDir = root.resolve(rule.module).resolve("src/main")
            if (!Files.isDirectory(sourceDir)) return@forEach

            val hits = scan(sourceDir, rule.forbidden, root)
            knownDebt += rule.allowed

            when {
                hits.size > rule.allowed -> {
                    failures += buildString {
                        appendLine(
                            "${rule.module} imports ${rule.forbidden} " +
                                "${hits.size} time(s); the boundary allows ${rule.allowed}.",
                        )
                        hits.take(MAX_REPORTED).forEach { appendLine("  $it") }
                        if (hits.size > MAX_REPORTED) {
                            appendLine("  … ${hits.size - MAX_REPORTED} more")
                        }
                    }
                }

                hits.size < rule.allowed -> {
                    failures += "${rule.module} -> ${rule.forbidden}: ${hits.size} import(s), " +
                        "baseline says ${rule.allowed}. Lower the baseline in ModuleBoundaryPlugin.kt."
                }

                rule.allowed > 0 -> {
                    notes += "${rule.module} -> ${rule.forbidden}: ${rule.allowed} known debt import(s)"
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

        logger.lifecycle(
            "module boundaries: ${RULES.size} rules enforced, $knownDebt known debt import(s)",
        )
        notes.forEach { logger.info("  $it") }
    }

    /** Returns the matching `path:line  fqn` entries for one rule. */
    private fun scan(dir: Path, prefix: String, root: Path): List<String> {
        val hits = mutableListOf<String>()

        Files.walk(dir).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path)) continue
                val name = path.fileName.toString()
                if (!name.endsWith(".kt") && !name.endsWith(".java")) continue

                val content = Files.readAllLines(path)
                for (index in content.indices) {
                    val trimmed = content[index].trim()
                    if (!trimmed.startsWith("import ")) continue

                    val fqn = trimmed
                        .removePrefix("import ")
                        .removeSuffix(";")
                        .substringBefore(" as ")
                        .trim()

                    if (fqn == prefix || fqn.startsWith("$prefix.")) {
                        hits += "${root.relativize(path)}:${index + 1}  $fqn"
                    }
                }
            }
        }

        return hits
    }

    private companion object {
        const val MAX_REPORTED = 20
    }
}

/**
 * The rules. `allowed` is a ratchet, never a licence: it records debt that an
 * architecture phase is going to remove, and it must reach 0 by that phase.
 *
 * Debts today (see docs/ARCHITECTURE.md §6):
 *  - `data -> terminal` 22 imports — removed in P3, when the new `:terminal` exposes ports.
 *  - `ssh  -> terminal`  1 import  — removed in P5, when the transport port moves to `:domain`.
 *
 * Deliberately absent until the phase that makes them true:
 *  - `* -> com.termux`   — `:terminal` still *is* Termux; added in P3.
 *  - `terminal -> engine` is allowed by design.
 */
private val RULES = listOf(
    // :domain is pure Kotlin. No Android, no Drosh module.
    BoundaryRule("domain", "android"),
    BoundaryRule("domain", "androidx"),
    BoundaryRule("domain", "dev.drosh.data"),
    BoundaryRule("domain", "dev.drosh.terminal"),
    BoundaryRule("domain", "dev.drosh.engine"),
    BoundaryRule("domain", "dev.drosh.guest"),
    BoundaryRule("domain", "dev.drosh.ui"),
    BoundaryRule("domain", "dev.drosh.ssh"),
    BoundaryRule("domain", "dev.drosh.agent"),

    // :engine is the bottom of the terminal stack: it knows nothing above it.
    BoundaryRule("engine", "dev.drosh.terminal"),
    BoundaryRule("engine", "dev.drosh.guest"),
    BoundaryRule("engine", "dev.drosh.data"),
    BoundaryRule("engine", "dev.drosh.agent"),
    BoundaryRule("engine", "dev.drosh.ssh"),
    BoundaryRule("engine", "dev.drosh.ui"),

    // :guest is a Linux-guest concern: no UI, no terminal session, no engine.
    BoundaryRule("guest", "dev.drosh.terminal"),
    BoundaryRule("guest", "dev.drosh.engine"),
    BoundaryRule("guest", "dev.drosh.data"),
    BoundaryRule("guest", "dev.drosh.ui"),
    BoundaryRule("guest", "androidx.compose"),

    // :terminal owns sessions; it never draws.
    BoundaryRule("terminal", "dev.drosh.ui"),
    BoundaryRule("terminal", "dev.drosh.app"),
    BoundaryRule("terminal", "androidx.compose"),

    // :ui is presentation over domain only.
    BoundaryRule("ui", "dev.drosh.terminal"),
    BoundaryRule("ui", "dev.drosh.engine"),

    // Known debt — must reach 0.
    BoundaryRule("ssh", "dev.drosh.terminal", allowed = 1),
    BoundaryRule("data", "dev.drosh.terminal", allowed = 22),
)
