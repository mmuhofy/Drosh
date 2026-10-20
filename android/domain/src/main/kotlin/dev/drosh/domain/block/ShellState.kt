package dev.drosh.domain.block

/**
 * What the terminal knows about the shell behind a session.
 *
 * The same facts in the same shape, whoever produced them: the engine reads
 * them from the marks it taps, and a terminal that cannot report them says so
 * with a null rather than with an empty string — the difference matters for
 * the working directory, where "" is a path and null is an absence.
 *
 * @property phase where the shell is in its cycle
 * @property exitStatus the exit status of the last command, when the shell reported one
 * @property workingDirectory the working directory the shell reported, as a path
 * @property editorTarget the file the shell last asked the editor to open
 */
data class ShellState(
    val phase: ShellPhase = ShellPhase.UNKNOWN,
    val exitStatus: Int? = null,
    val workingDirectory: String? = null,
    val editorTarget: String? = null,
)
