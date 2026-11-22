package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection's arithmetic, without a terminal.
 *
 * The engine owns the cells; this model owns the translation between its
 * eight-value answer and the two things the chrome needs — a box and two
 * grips. The cases that used to go wrong are here: a selection whose endpoint
 * has scrolled away, a select-all that has no endpoint on screen at all, and
 * the difference between the box's corners and the selection's own ends.
 */
class SelectionSnapshotTest {

    /** A box from the engine: first row, first column, last row, last column, then endpoints. */
    private fun snapshot(
        firstRow: Int,
        firstColumn: Int,
        lastRow: Int,
        lastColumn: Int,
        topLeft: Pair<Int, Int> = firstRow to firstColumn,
        bottomRight: Pair<Int, Int> = lastRow to lastColumn,
    ) = SelectionSnapshot.from(
        intArrayOf(
            firstRow, firstColumn, lastRow, lastColumn,
            topLeft.first, topLeft.second,
            bottomRight.first, bottomRight.second,
        ),
    )

    @Test
    fun `a single-row word has a box one row tall`() {
        val selection = snapshot(3, 7, 3, 12)!!
        val box = selection.box(cellWidth = 10f, cellHeight = 20f, scrollOffsetPx = 0f)!!

        assertEquals(70, box.left)
        assertEquals(60, box.top)
        // The last column is inclusive: column 12 ends at column 13's edge.
        assertEquals(130, box.right)
        assertEquals(80, box.bottom)
    }

    @Test
    fun `a wrapped selection spans both rows`() {
        val selection = snapshot(2, 40, 4, 3)!!
        val box = selection.box(cellWidth = 10f, cellHeight = 20f, scrollOffsetPx = 0f)!!

        assertEquals(400, box.left)
        assertEquals(40, box.top)
        assertEquals(40, box.right)
        assertEquals(100, box.bottom)
    }

    @Test
    fun `the picture's sub-row shift moves the box with it`() {
        val selection = snapshot(1, 0, 1, 1)!!
        val box = selection.box(cellWidth = 10f, cellHeight = 20f, scrollOffsetPx = 5f)!!

        assertEquals(15, box.top)
        assertEquals(35, box.bottom)
    }

    @Test
    fun `an endpoint that scrolled away has no grip but the box stays`() {
        // The selection runs from above the screen down to row 4. Only the
        // bottom endpoint is visible; the box still has to hold the part on
        // screen, because that is where the menu goes.
        val selection = snapshot(
            firstRow = 0,
            firstColumn = 0,
            lastRow = 4,
            lastColumn = 9,
            topLeft = -1 to -1,
            bottomRight = 4 to 9,
        )!!

        assertNull(selection.topLeftHandle)
        assertEquals(SelectionCell(4, 9), selection.bottomRightHandle)
        assertTrue(selection.hasVisibleCells)
    }

    @Test
    fun `select all with both endpoints off screen still has a box`() {
        val selection = snapshot(
            firstRow = 0,
            firstColumn = 0,
            lastRow = 23,
            lastColumn = 79,
            topLeft = -1 to -1,
            bottomRight = -1 to -1,
        )!!

        assertNull(selection.topLeftHandle)
        assertNull(selection.bottomRightHandle)
        // The menu still needs something to hang on even when neither grip has
        // a row: the visible part of the selection is the box.
        assertNotNull(selection.box(cellWidth = 10f, cellHeight = 20f, scrollOffsetPx = 0f))
        // With no endpoint on screen there is no cell to grow a word or line
        // from; the view supplies the first visible cell as its fallback.
        assertNull(selection.actionAnchor())
        assertEquals(SelectionCell(0, 0), selection.actionAnchor(SelectionCell(0, 0)))
    }

    @Test
    fun `an action prefers the moving end`() {
        val selection = snapshot(1, 1, 3, 8)!!
        assertEquals(SelectionCell(3, 8), selection.actionAnchor())
    }

    @Test
    fun `the lines action falls back when both endpoints are off screen`() {
        val selection = snapshot(
            0, 0, 10, 10,
            topLeft = -1 to -1,
            bottomRight = -1 to -1,
        )!!
        assertNull(selection.actionAnchor())
        assertEquals(SelectionCell(0, 0), selection.actionAnchor(SelectionCell(0, 0)))
    }

    @Test
    fun `a selection with no visible cells is not a selection`() {
        assertNull(snapshot(-1, -1, -1, -1, -1 to -1, -1 to -1))
    }

    @Test
    fun `a malformed native answer is ignored`() {
        assertNull(SelectionSnapshot.from(null))
        assertNull(SelectionSnapshot.from(IntArray(0)))
        assertNull(SelectionSnapshot.from(intArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `edges are a band at both ends and nothing in the middle`() {
        assertEquals(SelectionEdges.TOP, SelectionEdges.at(10f, 1000f, 56f))
        assertEquals(SelectionEdges.BOTTOM, SelectionEdges.at(980f, 1000f, 56f))
        assertEquals(SelectionEdges.NONE, SelectionEdges.at(500f, 1000f, 56f))
    }

    @Test
    fun `a finger beyond either end still counts as that edge`() {
        assertEquals(SelectionEdges.TOP, SelectionEdges.at(-20f, 1000f, 56f))
        assertEquals(SelectionEdges.BOTTOM, SelectionEdges.at(1200f, 1000f, 56f))
    }
}
