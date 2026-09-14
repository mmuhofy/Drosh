package com.iris.irisshell.domain.block

/**
 * Source of the latest shell prompt observed by the block engine.
 *
 * Exposed to the UI / domain layers so a newly submitted command can
 * be displayed with the correct prompt prefix (e.g.
 * `muhofy@iris:~/Drosh$`) without taking a hard dependency on the
 * `:terminal` module.
 *
 * Implementations observe the raw PTY byte stream and update
 * [lastPrompt] whenever a recognised prompt suffix (`$ `, `# `,
 * `❯ `, `➜ `) is seen at the end of an output line.
 *
 * Default value until the first prompt is observed lives in the
 * implementation — the domain layer does not know the default.
 */
interface BlockEngineState {
    /** Most recently seen prompt text, sans the trailing suffix. */
    val lastPrompt: String

    /** Last directory inferred from the prompt (e.g. `~/Drosh` → `Drosh`). */
    val lastDir: String
}
