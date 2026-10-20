package dev.drosh.terminal.engine.core

/**
 * A match in the engine's output log.
 *
 * The log is the engine's bounded copy of what the session printed: the
 * scrollback inside the grid cannot be read out of it, so the bytes are copied
 * on their way to the parser and kept. A search over that copy reaches further
 * back than the visible grid does.
 *
 * The coordinates are the log's, not the grid's. [line] is the index of the
 * line in the log (oldest first) and [colStart] and [colEnd] are the character
 * columns the match covers within that line.
 *
 * A line of the log is an **output** line, not a grid row: a line the
 * terminal soft-wrapped covers several rows, and a line a program redrew with
 * a carriage return holds every redraw. A match here is therefore a match in
 * the output — it cannot be turned into a position on the screen.
 *
 * @property line the index of the line in the log, oldest first
 * @property colStart the character column where the match starts
 * @property colEnd the character column just past the match
 */
data class TranscriptHit(
    val line: Int,
    val colStart: Int,
    val colEnd: Int,
) {
    companion object {
        /**
         * Parse the engine's answer: newline-separated `line:colStart:colEnd`
         * records.
         *
         * A record that is not in that shape is skipped rather than guessed
         * at, and an `error:` record — what the engine answers for an unknown
         * handle — is skipped too: a missing match is better than a match in
         * line zero.
         */
        fun parse(record: String): List<TranscriptHit> =
            record.lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("error:") }
                .mapNotNull { entry ->
                    val parts = entry.split(':')
                    if (parts.size != 3) return@mapNotNull null
                    val line = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val colStart = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val colEnd = parts[2].toIntOrNull() ?: return@mapNotNull null
                    TranscriptHit(line = line, colStart = colStart, colEnd = colEnd)
                }
                .toList()
    }
}
