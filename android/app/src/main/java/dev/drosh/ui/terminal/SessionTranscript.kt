package dev.drosh.ui.terminal

import com.termux.terminal.TerminalEmulator
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.session.AnsiStripper

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
    data class Termux(override val lines: List<String>) : SessionTranscript {

        companion object {

            /**
             * How much of a session is offered.
             *
             * A sheet, not a file: past a few thousand lines nobody scrolls to
             * the top of a log to paste it, and every extra line is a line
             * laid out.
             */
            const val MAX_LINES = 4000

            /**
             * The emulator's scrollback as plain text.
             *
             * The lines are the ones the emulator's buffer holds: the escape
             * sequences it drew with are stripped, the padding the grid added
             * is trimmed, and only the last [MAX_LINES] of them are kept.
             */
            fun of(emulator: TerminalEmulator): Termux {
                val text = AnsiStripper.strip(emulator.getScreen().getTranscriptText())
                val lines = text.lines().map { line ->
                    // A tab has no width here and the grid it came from is gone, so it
                    // becomes four spaces. Emulators expand tabs at write time, so this
                    // is a guard rather than a case that occurs.
                    line.trimEnd().replace("\t", "    ")
                }
                return Termux(
                    if (lines.size <= MAX_LINES) {
                        lines
                    } else {
                        lines.subList(lines.size - MAX_LINES, lines.size)
                    }
                )
            }
        }
    }

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
                Engine(
                    session.transcript().map { line ->
                        // The same reason the Termux side expands tabs: a tab has
                        // no width in a text sheet, and the grid it came from is
                        // gone. The engine's parser keeps the tab.
                        line.replace("\t", "    ")
                    }
                )
        }
    }
}
