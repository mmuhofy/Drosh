package dev.drosh.terminal.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout machine, on a clock we control.
 *
 * Every one of these cases was a device bug: text drawn inside itself while a
 * keyboard opened, a pinch that reflowed sixty times a second, a grid that
 * never caught up because nobody asked again. They are all numbers now.
 */
class LayoutMachineTest {

    private fun machine() = LayoutMachine(settleMillis = 48L)

    /**
     * Apply the grid the machine wants, the way the render loop does.
     *
     * The machine decides the shape and the caller resizes the engine to it;
     * the machine only records that the resize happened when it is told. A
     * test that wants the machine to *know* the shape has to say so, exactly
     * as the loop does.
     */
    private fun applyGrid(machine: LayoutMachine, now: Long): LayoutMachine.GridChange? {
        val change = machine.poll(now = now) ?: return null
        machine.onGridApplied(change.columns, change.rows)
        return change
    }

    @Test
    fun `a surface change defers the grid`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)
        // The cell change settles at once — it is not deferred by the settle
        // — so the grid is 80x30 and the change is there to be applied.
        val change = applyGrid(machine, now = 0)
        assertNotNull(change)
        requireNotNull(change)
        assertEquals(80, change.columns)
        assertEquals(30, change.rows)
        assertEquals(80, machine.appliedColumns)
        assertEquals(30, machine.appliedRows)

        val size = machine.onSurface(800, 400, now = 100)
        // Drawing follows the surface at once…
        assertEquals(800, size.width)
        assertEquals(400, size.height)
        // …but the grid is still the old shape.
        assertEquals(80, machine.appliedColumns)
        assertEquals(30, machine.appliedRows)
        assertTrue(machine.settlePending)
    }

    @Test
    fun `the grid settles once the surface stops`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)
        applyGrid(machine, now = 0)

        machine.onSurface(800, 400, now = 100)
        assertNull("still moving", machine.poll(now = 120))

        val change = applyGrid(machine, now = 200)
        assertNotNull(change)
        requireNotNull(change)
        assertEquals(80, change.columns)
        assertEquals(20, change.rows)
        assertEquals(20, machine.appliedRows)
        assertFalse(machine.settlePending)
    }

    @Test
    fun `an animation reflows once, not sixty times`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)

        // A keyboard rising: a surface change every 16 ms for 300 ms.
        var changes = 0
        var now = 0L
        while (now < 300) {
            machine.onSurface(800, 600 - (now / 4).toInt(), now)
            if (machine.poll(now) != null) changes += 1
            now += 16
        }
        assertEquals("no reflow while the surface moves", 0, changes)

        val change = machine.poll(now = now + 100)
        assertNotNull("one reflow after it stops", change)
        assertEquals(1, changes + 1)
    }

    @Test
    fun `a pinch arrives as a cell change and lays the grid out at once`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)
        applyGrid(machine, now = 0)

        val before = machine.appliedColumns to machine.appliedRows
        assertEquals(80, before.first)

        // A pinch is a font size, and the font's size is the cell's size, so
        // the grid is laid out again as the gesture runs.
        machine.onCell(12f, 24f, now = 1000)
        val change = machine.poll(now = 1000)
        assertNotNull("a committed size reflows immediately", change)
        requireNotNull(change)
        assertEquals(66, change.columns) // 800 / 12
        assertEquals(25, change.rows) // 600 / 24
    }

    @Test
    fun `a scroll offset is drawing only`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)
        applyGrid(machine, now = 0)

        machine.onScrollOffset(7.5f)
        assertEquals(7.5f, machine.size.scrollOffsetPx, 0.0001f)
        assertNull(machine.poll(now = 500))
    }

    @Test
    fun `a grid change to the same shape is not reported`() {
        val machine = machine()
        machine.onSurface(800, 600, now = 0)
        machine.onCell(10f, 20f, now = 0)
        applyGrid(machine, now = 0)

        // The surface moves and comes back to the same shape.
        machine.onSurface(800, 400, now = 100)
        machine.onSurface(800, 600, now = 200)
        assertNull("nothing changed", machine.poll(now = 400))
    }

    @Test
    fun `a surface before a cell lays nothing out`() {
        val machine = machine()
        machine.onSurface(1080, 2400, now = 0)
        // The one-pixel default would be a thousand columns; nothing is laid out
        // until a real cell exists.
        assertEquals(0, machine.appliedColumns)
        assertEquals(0, machine.appliedRows)
        assertFalse(machine.cellReady)

        machine.onCell(10f, 20f, now = 0)
        applyGrid(machine, now = 0)
        assertEquals(108, machine.appliedColumns)
        assertEquals(120, machine.appliedRows)
        assertTrue(machine.cellReady)
    }

    /**
     * A zero-sized surface has no grid, and saying so is the fix for the black
     * square at the origin.
     *
     * The old rule floored the shape at two columns and one row, so a surface
     * that had not been laid out yet produced a two-cell grid — one dark
     * rectangle in the corner of an otherwise empty window. A window with no
     * size has no cells, and the renderer now draws nothing until it does.
     */
    @Test
    fun `a zero-sized surface has no grid and is not ready to draw`() {
        val machine = machine()
        machine.onSurface(0, 0, now = 0)
        machine.onCell(10f, 20f, now = 0)

        assertEquals(0, machine.appliedColumns)
        assertEquals(0, machine.appliedRows)
        assertFalse(machine.cellReady)
    }

    @Test
    fun `the projection carries the scroll and no scale`() {
        val size = SizeInfo(
            width = 800,
            height = 600,
            cellWidth = 10f,
            cellHeight = 20f,
            scrollOffsetPx = 10f,
        )
        val out = FloatArray(4)
        size.projection(out)

        assertEquals(-1f, out[0], 0.0001f)
        // 1 - 2*10/600
        assertEquals(1f - 20f / 600f, out[1], 0.0001f)
        assertEquals(2f / 800f, out[2], 0.0001f)
        assertEquals(-2f / 600f, out[3], 0.0001f)
    }

    @Test
    fun `a moving surface does not stretch the cells`() {
        val machine = LayoutMachine(settleMillis = 100)
        machine.onCell(10f, 20f, 0)
        machine.onSurface(800, 600, 0)

        // The keyboard is rising: the surface shrank and the grid has not been
        // laid out again. The cells keep the size they were laid out with, so
        // the text does not grow and shrink under the eyes while it animates.
        val moving = machine.onSurface(800, 400, 16)
        assertEquals(10f, moving.stepX, 0.0001f)
        assertEquals(20f, moving.stepY, 0.0001f)
    }

    @Test
    fun `a settled surface stretches the cells to fill it`() {
        val machine = LayoutMachine(settleMillis = 100)
        machine.onCell(10f, 20f, 0)
        machine.onSurface(801, 601, 0)
        machine.poll(200)

        val settled = machine.size
        assertEquals(801f / 80f, settled.stepX, 0.0001f)
        assertEquals(601f / 30f, settled.stepY, 0.0001f)
    }

    @Test
    fun `the step divides the surface exactly`() {
        val size = SizeInfo(width = 801, height = 601, cellWidth = 10f, cellHeight = 20f)
        assertEquals(80, size.columns)
        assertEquals(30, size.rows)
        assertEquals(801f / 80f, size.stepX, 0.0001f)
        assertEquals(601f / 30f, size.stepY, 0.0001f)
    }
}
