package dev.drosh.terminal.engine.core

/**
 * The records the engine answers with.
 *
 * The search and the URL capabilities both report a position on the visible
 * grid, so one parser serves both. It lives here rather than inline in the
 * session so the format can be tested on its own: a record that is parsed
 * wrongly shows up as a highlight in the wrong column, and a test is the only
 * place that is visible without a device.
 */
internal object EngineRecords {

    /**
     * Parse newline-separated `row:col:len` records.
     *
     * A record that is not in that shape is skipped rather than guessed at, and
     * an `error:` record — what the engine answers for an unknown handle — is
     * skipped too: a missing hit is better than a hit in row zero.
     */
    fun parseTriples(text: String): List<Triple<Int, Int, Int>> =
        text.lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("error:") }
            .mapNotNull { record ->
                val parts = record.split(':')
                if (parts.size != 3) return@mapNotNull null
                val row = parts[0].toIntOrNull() ?: return@mapNotNull null
                val col = parts[1].toIntOrNull() ?: return@mapNotNull null
                val len = parts[2].toIntOrNull() ?: return@mapNotNull null
                Triple(row, col, len)
            }
            .toList()

    /**
     * Parse newline-separated `row:col:len:text` records.
     *
     * The text is the rest of the record, not the fourth field: a URL holds
     * colons of its own (`https://example.com`), so splitting the record into
     * four parts would cut the link in half.
     */
    fun parseUrlHits(text: String): List<UrlHit> =
        text.lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("error:") }
            .mapNotNull { record ->
                val first = record.indexOf(':')
                if (first <= 0) return@mapNotNull null
                val second = record.indexOf(':', first + 1)
                if (second <= first) return@mapNotNull null
                val third = record.indexOf(':', second + 1)
                if (third <= second) return@mapNotNull null

                val row = record.substring(0, first).toIntOrNull() ?: return@mapNotNull null
                val col = record.substring(first + 1, second).toIntOrNull() ?: return@mapNotNull null
                val len = record.substring(second + 1, third).toIntOrNull() ?: return@mapNotNull null
                val url = record.substring(third + 1)
                if (url.isEmpty()) return@mapNotNull null
                UrlHit(row = row, col = col, len = len, text = url)
            }
            .toList()
}
