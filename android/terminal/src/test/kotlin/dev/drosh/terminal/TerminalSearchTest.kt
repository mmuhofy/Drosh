package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The match list a find query resolves to, as pure data.
 *
 * The scan itself needs a live emulator and is not testable here; what is
 * testable is [matchTranscript], the function that decides where the matches
 * land — and that is where every quiet wrong answer lives. A row reported
 * against 0 instead of the scrollback it came from, a column past the right
 * edge, a list ordered newest-first: none of those throw, none of those look
 * like bugs, and all of them put a highlight on text the user did not search
 * for.
 */
class TerminalSearchTest {

    private fun scan(
        vararg rows: String?,
        query: String,
        baseRow: Int = 0,
        columns: Int = 8,
    ): List<TerminalSearchMatch> =
        matchTranscript(rows.toList(), query, baseRow, columns)

    @Test fun `a blank query finds nothing`() {
        assertEquals(emptyList<TerminalSearchMatch>(), scan("hello", query = ""))
    }

    @Test fun `a grid with no columns finds nothing`() {
        // Dividing by zero in columns would otherwise produce a match at
        // column 0 of nothing.
        assertEquals(emptyList<TerminalSearchMatch>(), scan("hello", query = "h", columns = 0))
    }

    @Test fun `no match finds nothing`() {
        assertEquals(emptyList<TerminalSearchMatch>(), scan("hello", "world", query = "zebra"))
    }

    @Test fun `a match reports its row and its columns`() {
        val matches = scan("abcdef", query = "cd")
        assertEquals(1, matches.size)
        assertEquals(TerminalSearchMatch(row = 0, colStart = 2, colEnd = 4), matches.first())
    }

    @Test fun `the row is the base row, not zero`() {
        // A whole-buffer scan starts at -activeTranscriptRows. A match
        // reported against 0 there is a highlight drawn a scrollback away from
        // its own text.
        val matches = scan("abcdef", query = "cd", baseRow = -17)
        assertEquals(-17, matches.first().row)
    }

    @Test fun `matching is case insensitive`() {
        assertEquals(1, scan("Hello World", query = "hello").size)
        assertEquals(1, scan("HELLO WORLD", query = "world").size)
    }

    @Test fun `several matches on one row are all found, first column first`() {
        val matches = scan("ab ab ab", query = "ab")
        assertEquals(3, matches.size)
        assertEquals(listOf(0, 3, 6), matches.map { it.colStart })
        assertEquals(listOf(2, 5, 8), matches.map { it.colEnd })
        assertTrue(matches.all { it.row == 0 })
    }

    @Test fun `a run of cells is one match, not two overlapping ones`() {
        // "aa" in "aaa": the cells are painted once and there is no second
        // layer to highlight, so two rectangles over the same cells would say
        // one more match than the bar counts.
        val matches = scan("aaaa", query = "aa")
        assertEquals(2, matches.size)
        assertEquals(listOf(0, 2), matches.map { it.colStart })
    }

    @Test fun `matches are returned oldest row first`() {
        // The order next/previous walk in. Newest-first would send the first
        // tap on "next" to the top of the history and leave the user to
        // scroll forward to reach the output they were looking at.
        val matches = scan("quiet", "match here", "quiet", "match again", query = "match")
        assertEquals(listOf(1, 3), matches.map { it.row })
    }

    @Test fun `a match is clamped to the columns the grid has`() {
        // A rectangle past the last column paints over the next pane, or off
        // the edge of the view.
        val matches = scan("abcdefgh", query = "fgh", columns = 5)
        assertEquals(1, matches.size)
        assertEquals(TerminalSearchMatch(row = 0, colStart = 4, colEnd = 5), matches.first())
    }

    @Test fun `a match longer than the grid is not a match`() {
        val matches = scan("abc", query = "abcdefgh", columns = 4)
        assertEquals(emptyList<TerminalSearchMatch>(), matches)
    }

    @Test fun `a null row is skipped`() {
        // A row the buffer could not resolve: unknown text, not a blank line,
        // and claiming it has no match is the honest answer for both.
        val matches = scan(null, "match", null, query = "match")
        assertEquals(1, matches.size)
        assertEquals(1, matches.first().row)
    }

    @Test fun `empty rows are skipped without disturbing the row count`() {
        val matches = scan("", "match", "", query = "match")
        assertEquals(1, matches.size)
        assertEquals(1, matches.first().row)
    }

    @Test fun `screen rows keep their own indices after the transcript`() {
        // The transcript is the negatives and the screen 0..mRows-1, and the
        // screen rows are the newest, so they come last in the list.
        val matches = scan("old match", "new match", query = "match", baseRow = -1)
        assertEquals(listOf(-1, 0), matches.map { it.row })
    }

    @Test fun `no match ever paints past the last column`() {
        // The invariant, over widths rather than one case: a rectangle past
        // the last column draws over the next pane, or off the view. The
        // clamp is the only thing standing between a long query and that.
        val text = "averylongqueryhereandmore"
        for (columns in 1..text.length) {
            val matches = scan(text, query = "verylongquery", columns = columns)
            assertTrue(
                "columns=$columns produced a match past the grid",
                matches.all { it.colStart in 0 until columns && it.colEnd in 1..columns },
            )
        }
    }

    @Test fun `a row longer than the grid still reports its match, clamped`() {
        // A row is never longer than the grid in a real terminal — it wraps —
        // but the clamp is what makes a mismatch harmless rather than a
        // rectangle in the next pane.
        val matches = scan("averylongqueryhere", query = "averylongquery", columns = 5)
        assertEquals(1, matches.size)
        assertEquals(TerminalSearchMatch(row = 0, colStart = 0, colEnd = 5), matches.first())
    }
}
