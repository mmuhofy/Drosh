package dev.drosh.ui.terminal

/**
 * Which terminal a find answer came from.
 *
 * The two terminals answer "where is the match?" in two different coordinate
 * systems, and the answer says which one it is: a Termux-backed terminal
 * answers with a row of its scrollback buffer, an engine-backed one with a
 * line of its output log.
 *
 * The kind is never a disguise. Each source only ever answers in its own
 * coordinates and only ever reveals its own matches, so an engine answer
 * cannot be read as a Termux one, and a Termux answer cannot be read as an
 * engine one.
 */
enum class TerminalFindKind {

    /**
     * The vendored Termux terminal.
     *
     * Its matches are runs of cells in the rows of the emulator's buffer, so
     * a match can be turned into a position on the screen: the terminal can be
     * scrolled to the row the match is on.
     */
    TERMUX,

    /**
     * The Rust engine.
     *
     * Its matches are runs of characters in the lines of the engine's output
     * log — a bounded copy of what the session printed. A line of that log is
     * an *output* line and not a grid row: a line the terminal soft-wrapped
     * covers several rows, and a line a program redrew with a carriage return
     * holds every redraw. So a match here is a match in the output, and there
     * is no position on the screen to scroll it to.
     */
    ENGINE,
}
