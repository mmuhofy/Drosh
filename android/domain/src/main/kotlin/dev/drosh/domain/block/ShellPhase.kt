package dev.drosh.domain.block

/**
 * Where the shell is in its cycle.
 *
 * The engine knows this from the shell-integration marks it taps where the
 * bytes enter, so a session that runs on the engine never has to guess the
 * phase from the shape of a prompt. The vendored Termux emulator has no such
 * state to hand over, which is why [UNKNOWN] is a value of its own rather
 * than a guess.
 */
enum class ShellPhase {
    /** Nothing has been reported yet: the shell has not said where it is. */
    UNKNOWN,

    /** The prompt is on screen; the shell waits for a command. */
    PROMPT,

    /** The command is being typed. */
    TYPING,

    /** The command is running. */
    RUNNING,

    /** The command finished; the shell is about to print the next prompt. */
    FINISHED,
}
