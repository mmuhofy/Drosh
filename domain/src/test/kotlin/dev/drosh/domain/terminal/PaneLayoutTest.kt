package dev.drosh.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The divider and the floating window.
 *
 * Pure arithmetic, but the arithmetic is where the feature actually lives: the
 * clamping, the snapping and the collapse are three rules that can disagree with
 * one another, and a disagreement shows up as a pane at an unreachable size or a
 * split that cannot be closed.
 */
class PaneLayoutTest {

    private fun split() = PaneLayout(secondarySessionId = "b")

    // ── Dragging ──────────────────────────────────────────────────────────────

    /**
     * The whole point of separating this from [PaneLayout.withSplitFraction]: a
     * pane that jumps between four fixed heights while the finger is still
     * moving does not track the finger, and the seam appears to be somewhere
     * else entirely.
     */
    @Test
    fun `a drag follows the finger instead of snapping`() {
        val dragged = split().withDraggedFraction(0.37f)
        assertEquals(0.37f, dragged.splitFraction, 0.001f)
    }

    @Test
    fun `a drag cannot go past the collapse threshold`() {
        val dragged = split().withDraggedFraction(-0.5f)
        assertEquals(PaneLayout.COLLAPSE_FRACTION, dragged.splitFraction, 0.001f)
    }

    @Test
    fun `a drag cannot push the other pane off entirely`() {
        val dragged = split().withDraggedFraction(1.5f)
        assertEquals(1f - PaneLayout.COLLAPSE_FRACTION, dragged.splitFraction, 0.001f)
    }

    @Test
    fun `a drag with no split does nothing`() {
        val empty = PaneLayout.EMPTY.withDraggedFraction(0.3f)
        assertFalse(empty.isSplit)
    }

    // ── Releasing ─────────────────────────────────────────────────────────────

    @Test
    fun `a release snaps to the nearest step`() {
        // 0.46 is nearer 0.4 than 0.6.
        assertEquals(0.4f, split().withDraggedFraction(0.46f).commitDraggedFraction().splitFraction, 0.001f)
        assertEquals(0.6f, split().withDraggedFraction(0.54f).commitDraggedFraction().splitFraction, 0.001f)
    }

    @Test
    fun `a release lands exactly on a step unchanged`() {
        for (step in split().splitSteps()) {
            val committed = split().withDraggedFraction(step).commitDraggedFraction()
            assertEquals(step, committed.splitFraction, 0.001f)
            assertTrue("step $step should survive", committed.isSplit)
        }
    }

    @Test
    fun `every step is reachable by dragging`() {
        val steps = split().splitSteps()
        assertEquals(4, steps.size)
        steps.forEach { step ->
            val round = split().withDraggedFraction(step).commitDraggedFraction()
            // Delta, not equality: 0.2 * 3 is not 0.6 in binary floating point,
            // and a test asserting bit equality would fail on the arithmetic
            // rather than on the behaviour.
            assertEquals(step, round.splitFraction, 0.001f)
        }
    }

    /** Dragging to an end and letting go closes the split rather than leaving a stub. */
    @Test
    fun `a release at either end collapses the split`() {
        assertFalse(split().withDraggedFraction(0.05f).commitDraggedFraction().isSplit)
        assertFalse(split().withDraggedFraction(0.95f).commitDraggedFraction().isSplit)
        assertFalse(split().withDraggedFraction(0f).commitDraggedFraction().isSplit)
        assertFalse(split().withDraggedFraction(1f).commitDraggedFraction().isSplit)
    }

    /** Just inside the threshold is still a split, so the gesture has to be decisive. */
    @Test
    fun `a release just inside the threshold stays split`() {
        val committed = split()
            .withDraggedFraction(PaneLayout.COLLAPSE_FRACTION + 0.01f)
            .commitDraggedFraction()
        assertTrue(committed.isSplit)
        assertEquals(PaneLayout.MIN_SPLIT_FRACTION, committed.splitFraction, 0.001f)
    }

    /**
     * Which session is left, and it is not the obvious way round.
     *
     * Dragging the divider **down** makes the *lower* pane taller, so what
     * vanishes is the top one and the lower session is what survives. Getting
     * this backwards loses the pane the user was enlarging — the one that was
     * growing as they dragged.
     */
    @Test
    fun `dragging down keeps the bottom session`() {
        assertEquals(PaneSlot.SECONDARY, beforeCollapse(SplitSlotCase.TOP_DOWN))
    }

    @Test
    fun `dragging up keeps the top session`() {
        assertEquals(PaneSlot.PRIMARY, beforeCollapse(SplitSlotCase.BOTTOM_UP))
    }

    @Test
    fun `a swap inverts which session survives a collapse`() {
        val normal = split().withDraggedFraction(0.05f)
        val swapped = split().swapped().withDraggedFraction(0.05f)
        assertFalse(normal.isSplit || swapped.isSplit)
        assertTrue(normal.collapsedSurvivingSlot() != swapped.collapsedSurvivingSlot())
    }

    // ── Swapping ──────────────────────────────────────────────────────────────

    @Test
    fun `swapping flips the flag`() {
        assertTrue(split().swapped().secondarySwapped)
        assertFalse(split().swapped().swapped().secondarySwapped)
    }

    /**
     * The height is the one thing the user did not ask to change. Carrying it
     * across is what makes a swap feel like a swap rather than a rearrange.
     */
    @Test
    fun `swapping leaves the divider where it was`() {
        val before = split().withSplitFraction(0.6f)
        assertEquals(before.splitFraction, before.swapped().splitFraction, 0.001f)
    }

    @Test
    fun `swapping leaves the session alone`() {
        assertEquals("b", split().swapped().secondarySessionId)
    }

    /** A flag with no second session describes nothing and must not outlive one. */
    @Test
    fun `clearing resets the swap`() {
        val swapped = split().swapped()
        assertFalse(swapped.cleared().secondarySwapped)
        assertFalse(swapped.cleared().isSplit)
    }

    // ── The floating window ───────────────────────────────────────────────────

    @Test
    fun `a floating pane can be pushed well off the edge`() {
        val parked = split().floating().withFloatingBounds(NormalizedRect(-0.25f, 0.5f, 0.6f, 0.4f))
        assertEquals(-PaneLayout.OVERSCAN, parked.floatingBounds.left, 0.001f)
    }

    /**
     * Generous, not unlimited: a window entirely outside its host has no visible
     * edge to grab, and the only way back would be to kill the process.
     */
    @Test
    fun `a floating pane always keeps a strip on screen`() {
        val far = split().floating().withFloatingBounds(NormalizedRect(-9f, -9f, 0.5f, 0.4f))
        assertEquals(-PaneLayout.OVERSCAN, far.floatingBounds.left, 0.001f)
        assertEquals(-PaneLayout.OVERSCAN, far.floatingBounds.top, 0.001f)
        assertTrue(
            "some part must stay reachable",
            far.floatingBounds.left + far.floatingBounds.width > 0f,
        )
    }

    @Test
    fun `a floating pane cannot be dragged smaller than a terminal`() {
        val tiny = split().floating().withFloatingBounds(NormalizedRect(0.1f, 0.1f, 0.01f, 0.01f))
        assertEquals(PaneLayout.MIN_FLOAT_WIDTH, tiny.floatingBounds.width, 0.001f)
        assertEquals(PaneLayout.MIN_FLOAT_HEIGHT, tiny.floatingBounds.height, 0.001f)
    }

    @Test
    fun `moving a pane is ignored while it is docked`() {
        val docked = split().withFloatingBounds(NormalizedRect(0f, 0f, 0.5f, 0.5f))
        assertEquals(NormalizedRect.DEFAULT, docked.floatingBounds)
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    @Test
    fun `a pane whose session ended closes`() {
        assertFalse(split().reconciledAgainst(setOf("a")).isSplit)
    }

    @Test
    fun `a pane whose session is live stays`() {
        assertTrue(split().reconciledAgainst(setOf("a", "b")).isSplit)
    }

    @Test
    fun `a blank secondary id is refused`() {
        assertFalse(split().withSecondary("").isSplit || PaneLayout.EMPTY.withSecondary("  ").isSplit)
    }

    /** Dropping the session that is already second must not reset the divider. */
    @Test
    fun `re-opening the same session leaves the divider alone`() {
        val arranged = split().withSplitFraction(0.6f)
        assertEquals(0.6f, arranged.withSecondary("b").splitFraction, 0.001f)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private enum class SplitSlotCase { TOP_DOWN, BOTTOM_UP }

    /**
     * The slot that survives a collapse to the given side.
     *
     * Runs the real call rather than restating its rule, so a change to
     * [PaneLayout] is what these assert about.
     */
    private fun beforeCollapse(which: SplitSlotCase): PaneSlot {
        val dragged = when (which) {
            SplitSlotCase.TOP_DOWN -> split().withDraggedFraction(0.95f)
            SplitSlotCase.BOTTOM_UP -> split().withDraggedFraction(0.05f)
        }
        return dragged.collapsedSurvivingSlot()
    }
}
