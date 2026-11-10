package dev.drosh.terminal.session

import dev.drosh.domain.block.ShellState

/**
 * What the app can ask a running session about the shell behind it.
 *
 * ## Why an interface and not the session itself
 *
 * The app asks three things of a session: the shell's phase, the directory it
 * is in, and the file it last asked the editor to open. The pane is what the
 * app holds — a pane is a view, and a view outlives and outlives differently
 * than a session — so the facts are asked of an interface that a pane can
 * answer.
 *
 * A session that knows nothing answers with null rather than with an empty
 * string: for a working directory, "" is a path and null is an absence.
 */
interface ShellSession {

    /**
     * The shell's state, or null when the session knows nothing.
     *
     * A session that has not seen a shell mark has no phase, no directory and
     * no editor target, and saying so is honest where inventing a phase would
     * not be.
     */
    fun shellState(): ShellState?

    /** The file the shell last asked the editor to open, or null. */
    fun editorTarget(): String?

    /**
     * The lines the session printed, oldest first.
     *
     * The block engine reads the transcript to work out what a command
     * printed, and the session is where that text lives.
     */
    fun transcript(): List<String>
}
