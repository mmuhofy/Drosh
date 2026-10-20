package dev.drosh.terminal.session

import dev.drosh.linux.GuestCommand

/**
 * How the engine starts a program.
 *
 * The engine has one way of running something — a program, its arguments, its
 * environment and a working directory — and the guest launchers produce
 * exactly that, in a slightly different shape. This is the translation between
 * the two.
 *
 * ## The one rule worth knowing
 *
 * **The executable is not repeated in the arguments.**
 *
 * Termux's `execvp` took the whole argument vector, whose first entry was the
 * executable itself. The engine's PTY builds the vector the other way round:
 * the program is `argv[0]` and the arguments follow it. Passing the vector
 * unchanged would therefore exec the linker twice — once as `argv[0]` and once
 * as the first argument — and proot would be handed its own name as an
 * argument, which is not a thing it understands.
 */
data class EngineLaunch(
    /** The program to run; it becomes the argument vector's first entry. */
    val program: String,
    /** The arguments, without the program. */
    val args: List<String>,
    /** The environment, as pairs. */
    val env: Map<String, String>,
    /** The working directory, or null to leave it to the caller. */
    val cwd: String?,
) {
    companion object {
        /**
         * The engine's way of running [command].
         *
         * The environment arrives as `KEY=VALUE` strings and is split on the
         * first `=`, so a value may hold one of its own.
         */
        fun from(command: GuestCommand): EngineLaunch = EngineLaunch(
            program = command.executable,
            // The executable is the argument vector's first entry already.
            args = command.argv.drop(1),
            env = command.environment.mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    entry.substring(0, separator) to entry.substring(separator + 1)
                }
            }.toMap(),
            cwd = command.cwd,
        )
    }
}
