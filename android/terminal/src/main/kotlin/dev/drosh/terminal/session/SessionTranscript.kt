package dev.drosh.terminal.session

import dev.drosh.terminal.engine.core.EngineSession

/** What a tab becomes in a log: four spaces, the width it stood for. */
private const val TAB_WIDTH = "    "

/**
 * A session's terminal output: the lines it printed, oldest first.
 *
 * ## Why there are two kinds
 *
 * The app is between two terminals. The vendored Termux emulator keeps a
 * scrollback in a [com.termux.terminal.TerminalBuffer]; the Rust engine keeps
 * a bounded copy of what the session printed and hands it over through JNI
 * ([EngineSession.transcript]). Both can answer "what did this session
 * print?", so the log sheet asks that one question of whichever terminal owns
 * the session — and the kind says which terminal answered it.
 *
 * A kind is never a disguise: a transcript read out of the emulator is a
 * [Termux] one and a transcript read out of the engine is an [Engine] one,
 * even when both are empty.
 *
 * ## What the lines are
 *
 * Plain text. The escape sequences go, because a control character pasted
 * into a prompt is a prompt the model cannot read, and the trailing spaces go,
 * because a terminal pads every row to the column width and the padding is
 * not part of what the command said.
 *
 * ## Where the mapping lives
 *
 * The mapping — the trimming, the tab expansion and the cap — is a pure
 * function of the text, so it is a function of a [String] and can be tested
 * without an emulator and without a live engine session. The readers below
 * are the thin part that needs one.
 */
sealed interface SessionTranscript {

    /** The lines the session printed, oldest first. */
    val lines: List<String>

    /**
     * Read out of the vendored Termux emulator's scrollback.
     *
     * The scrollback is the only record of what a session has ever printed:
     * there is no file behind it. A session the user is not looking at still
     * has its buffer, which is why the log can be offered from the drawer for
     * a session that has no pane on screen.
     */
    // Termux transcript removed — engine is the only terminal now.

    /**
     * Read out of the Rust engine's bounded copy of the session's output.
     *
     * The engine keeps the last lines the session printed — the scrollback
     * lives inside the core's grid and the grid does not hand it out — so the
     * copy is what the log can be read from.
     *
     * There is no transcript without an engine session: an [Engine] one is
     * only ever made out of a session the engine holds, so an empty [Engine]
     * transcript means "the engine session printed nothing" and never "there
     * was no session". The engine keeps its copy with the session, so a
     * session the engine has closed has no lines left to show.
     */
    data class Engine(override val lines: List<String>) : SessionTranscript {

        companion object {

            /**
             * What the engine has of the session.
             *
             * The lines arrive already stripped: the copy the engine keeps is
             * raw bytes, but [EngineSession.transcript] runs them through the
             * core's own parser before handing them over. Stripping them a
             * second time is not the same as stripping them once — it eats
             * the characters that were meant to be literal, so a line that
             * printed an escape character as text would lose it.
             *
             * The engine trims the trailing spaces itself and keeps the last
             * four thousand lines, so there is nothing to trim and nothing to
             * cap here.
             *
             * These are the lines as they arrived, not as the screen drew
             * them: a program that redrew its line shows every redraw, and the
             * carriage returns that separated them are still in the text.
             */
            fun of(session: EngineSession): Engine =
                Engine(linesOf(session.transcript()))

            /**
             * The plain-text lines of the engine's lines.
             *
             * The engine's lines are already plain: the core's parser removed
             * the escape sequences and the engine trimmed the trailing
             * spaces. Nothing is stripped here — a second pass would eat the
             * characters that were meant to be literal.
             *
             * The one thing that changes is the tab: it has no width in a
             * text sheet, and the grid it came from is gone, so it becomes
             * four spaces. The engine's parser keeps the tab.
             */
            fun linesOf(engineLines: List<String>): List<String> =
                engineLines.map { line -> line.replace("\t", TAB_WIDTH) }
        }
    }
}
