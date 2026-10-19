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

    // ── The scan band ─────────────────────────────────────────────────────────

    /**
     * The margin is a quarter of a screen, and never the whole of a short one.
     *
     * The band is what keeps a scroll from costing a scan: a viewport with a
     * quarter screen past each end covers almost two screens of travel, and a
     * grid too short to spare a quarter is given a single row rather than a
     * margin that would eat the viewport it is meant to surround.
     */
    @Test fun `the margin is a quarter of the screen, floored at one row`() {
        assertEquals(1, TerminalUrlOverlay.marginFor(4))
        assertEquals(1, TerminalUrlOverlay.marginFor(8))
        assertEquals(10, TerminalUrlOverlay.marginFor(40))
        assertEquals(25, TerminalUrlOverlay.marginFor(100))
    }

    /**
     * A band never inverts, however short the grid.
     *
     * With a margin smaller than the band's own overlap the cached range would
     * end above where it starts, and every frame would read as a scroll past
     * the edge — one full rescan per frame, which is the cost the band exists
     * to avoid.
     */
    @Test fun `the band always covers the viewport and reaches past it`() {
        for (rows in 1..60) {
            val margin = TerminalUrlOverlay.marginFor(rows)
            val top = -margin
            val bottom = rows - 1 + margin
            assertTrue("rows=$rows band is inverted", bottom >= top)
            assertTrue("rows=$rows band misses the viewport", top < 0 && bottom >= rows - 1)
        }
    }
}
