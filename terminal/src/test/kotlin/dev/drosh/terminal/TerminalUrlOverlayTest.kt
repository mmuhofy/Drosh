package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the grouping rules the old SearchHighlightOverlay got wrong.
 *
 * The history: the overlay's onDraw walked its row groups inclusively, and
 * when a soft-wrapped line ran to the last visible row the group cursor
 * landed *on* the row count instead of on the last row. The inclusive walk
 * then read one row past the array and the app crashed —
 * SearchHighlightOverlay:279, in production, "sometimes, for no reason".
 * The same off-by-one was found and fixed in urlAtCell months earlier; the
 * draw path was missed. TerminalUrlOverlay now owns one grouping function for
 * both call sites, and these tests are what say it stays correct.
 */
class TerminalUrlOverlayTest {

    @Test fun `no rows produce no groups`() {
        assertEquals(emptyList<IntRange>(), TerminalUrlOverlay.logicalLineGroups(0, booleanArrayOf()))
    }

    @Test fun `a row that does not wrap is a group of its own`() {
        val continues = booleanArrayOf(false, false, false)
        val groups = TerminalUrlOverlay.logicalLineGroups(3, continues)
        assertEquals(listOf(0..0, 1..1, 2..2), groups)
    }

    @Test fun `a wrapped run groups into one logical line`() {
        // Row 0 wraps into 1, row 1 wraps into 2, row 2 does not.
        val continues = booleanArrayOf(true, true, false)
        val groups = TerminalUrlOverlay.logicalLineGroups(3, continues)
        assertEquals(listOf(0..2), groups)
    }

    @Test fun `a line wrapped past the bottom stops at the last row`() {
        // Every row wraps: the run never terminates on screen. The cursor
        // lands on rowCount, and the group must clamp to rowCount - 1 —
        // this is the shape that produced the crash.
        val continues = booleanArrayOf(true, true, true)
        val groups = TerminalUrlOverlay.logicalLineGroups(3, continues)
        assertEquals(listOf(0..2), groups)
        // The invariant, stated directly: no group ever names a row that
        // does not exist.
        for (group in groups) {
            assertTrue("group $group exceeds the row count", group.last < 3)
        }
    }

    @Test fun `a run stopped short by the bottom wraps into the final group`() {
        // Row 0 wraps into 1 and row 1 wraps past the bottom: one logical
        // line, rows 0..2. Row 2 also wraps, but its successor does not exist,
        // so the run stops at the last row of the screen.
        val continues = booleanArrayOf(true, true)
        val groups = TerminalUrlOverlay.logicalLineGroups(3, continues)
        assertEquals(listOf(0..2), groups)
    }

    @Test fun `wrapped and unwrapped runs alternate`() {
        val continues = booleanArrayOf(true, false, true, true)
        val groups = TerminalUrlOverlay.logicalLineGroups(4, continues)
        assertEquals(listOf(0..1, 2..3), groups)
    }

    @Test fun `groups partition every row exactly once`() {
        val continues = booleanArrayOf(true, false, true, true, false, true)
        val groups = TerminalUrlOverlay.logicalLineGroups(6, continues)
        val covered = groups.flatMap { group -> group.toList() }
        assertEquals((0 until 6).toList(), covered)
    }

    @Test fun `a single wrapped screen is one group ending at the only row`() {
        val groups = TerminalUrlOverlay.logicalLineGroups(1, booleanArrayOf(true))
        assertEquals(listOf(0..0), groups)
    }

    @Test fun `a defensive array shorter than the screen still partitions`() {
        // readRows() keeps continues the same length as the row count, so a
        // shorter array should never happen — but if it does, grouping must
        // still walk every row and never name one that does not exist.
        val groups = TerminalUrlOverlay.logicalLineGroups(4, booleanArrayOf(true))
        assertEquals((0 until 4).toList(), groups.flatMap { it.toList() })
    }
}
