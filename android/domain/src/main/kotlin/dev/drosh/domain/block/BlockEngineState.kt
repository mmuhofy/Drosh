package dev.drosh.domain.block

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
 *
 * The *state* of the shell — its phase, the working directory, the editor
 * target — comes from the terminal when the terminal knows it. A terminal
 * that runs on the Rust engine reads those facts from the shell-integration
 * marks it taps; the vendored Termux emulator has no such report to give, so
 * an implementation there infers what it can from the screen and answers
 * [ShellPhase.UNKNOWN] and null.
 */
interface BlockEngineState {
    /** Most recently seen prompt text, sans the trailing suffix. */
    val lastPrompt: String

    /**
     * Last directory inferred from the prompt (e.g. `~/Drosh` → `Drosh`).
     *
     * The short label the block header shows. A terminal that reports a
     * [workingDirectory] answers with that path's last segment instead.
     */
    val lastDir: String

    /**
     * True when the shell itself is at a prompt and waiting for a command.
     *
     * False once an interactive program has taken the terminal — a Python
     * REPL's `>>>`, a `psql`, an editor. The block input bar is for shell
     * commands: submitting through it there would record the line as a new
     * command block and suppress the echo the program is about to print. The UI
     * hides the bar in that state and the input goes to the program instead.
     *
     * A terminal that reports a [shellPhase] answers from it rather than from
     * the screen, and there "false" also covers a command that is running: the
     * marks know that something owns the terminal, not whether that something
     * is a REPL.
     */
    val awaitingShellInput: Boolean

    /**
     * Where the shell is in its cycle, when the terminal knows it.
     *
     * [ShellPhase.UNKNOWN] when nothing reports it: the vendored Termux
     * emulator has no phase to hand over, and the block engine infers the
     * phase from the screen instead.
     */
    val shellPhase: ShellPhase
        get() = ShellPhase.UNKNOWN

    /**
     * The working directory the shell reported, when it reported one.
     *
     * Null rather than an empty string: "" is a path and null is an absence.
     * The vendored Termux path leaves it null — the emulator's directory is
     * not something the block engine reads, and the short label it does keep
     * is [lastDir].
     */
    val workingDirectory: String?
        get() = null

    /**
     * The file the shell last asked the editor to open, when it reported one.
     *
     * The vendored Termux path leaves it null: there the request is an event,
     * handed to the editor as it happens rather than read back later.
     */
    val editorTarget: String?
        get() = null
}
