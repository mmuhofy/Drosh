package dev.drosh.ui.terminal

import dev.drosh.terminal.session.AnsiStripper
import dev.drosh.terminal.session.TerminalManager
import dev.drosh.ui.session.SessionLogSource

/**
 * A session's terminal output, read out of the emulator's buffer.
 *
 * ## Why the buffer and not a file
 *
 * There is no file. The scrollback lives in the emulator's circular
 * [com.termux.terminal.TerminalBuffer], which is the only record of what a
 * session has ever printed. So this reads that, which also means it can answer
 * for a session the user is not currently looking at: a session with no pane
 * still has its buffer, and that is the whole point of offering this from the
 * drawer rather than from the terminal's own overflow.
 *
 * ## What comes back
 *
 * The transcript as plain text: escape sequences stripped by [AnsiStripper],
 * because a control character pasted into a prompt is a prompt the model cannot
 * read. Trailing spaces go, because a terminal pads every row to the column
 * width and the padding is not part of what the command said.
 *
 * Capped to the last [MAX_LINES] lines. A session that has been running for a
 * week has more scrollback than a sheet can hold, and the lines anyone wants are
 * the ones at the end — the failure they are about to paste.
 */
class TerminalSessionLogSource(
    private val terminalManager: TerminalManager,
) : SessionLogSource {

    override fun lines(sessionId: String): List<String> {
        val emulator = terminalManager.sessionForId(sessionId)?.emulator ?: return emptyList()
        val text = AnsiStripper.strip(emulator.getScreen().getTranscriptText())
        val lines = text.lines().map { line ->
            // A tab has no width here and the grid it came from is gone, so it
            // becomes four spaces. Emulators expand tabs at write time, so this
            // is a guard rather than a case that occurs.
            line.trimEnd().replace("\t", "    ")
        }
        return if (lines.size <= MAX_LINES) {
            lines
        } else {
            lines.subList(lines.size - MAX_LINES, lines.size)
        }
    }

    private companion object {
        /**
         * How much of a session is offered.
         *
         * A sheet, not a file: past a few thousand lines nobody scrolls to the
         * top of a log to paste it, and every extra line is a line laid out.
         */
        const val MAX_LINES = 4000
    }
}