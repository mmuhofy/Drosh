package dev.drosh.ui.terminal

/**
 * One match in the engine's output log.
 *
 * The coordinates are the log's: [line] is the index of the line in the log
 * (oldest first) and [colStart] and [colEnd] are the character columns the
 * match covers within that line. They are not positions on the screen — a
 * line of the log is an output line, not a grid row — so nothing here can be
 * scrolled to.
 *
 * [text] is the matched text itself: the log's line, cut at the columns. It
 * is what the find bar quotes, because the engine's matches are never drawn
 * on the screen.
 */
data class EngineTranscriptMatch(
    /** The index of the line in the engine's output log, oldest first. */
    val line: Int,
    /** The character column where the match starts. */
    val colStart: Int,
    /** The character column just past the match. */
    val colEnd: Int,
    /** The matched text — empty when the log moved and the text is unknown. */
    val text: String,
)
