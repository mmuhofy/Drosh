package dev.drosh.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull

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

    /**
     * A release keeps the position the drag reached.
     *
     * It used to snap to the nearest step, which read as the drag being undone:
     * the split opens at 0.5, which is not one of the four steps, so a few
     * pixels of movement released in the middle landed back on 0.4 or 0.6 and
     * the panes visibly sprang away from where they had been dropped.
     */
    @Test
    fun `a release keeps the dragged position`() {
        assertEquals(0.46f, split().withDraggedFraction(0.46f).commitDraggedFraction().splitFraction, 0.001f)
        assertEquals(0.54f, split().withDraggedFraction(0.54f).commitDraggedFraction().splitFraction, 0.001f)
    }

    /** Anywhere between the collapse thresholds is a legal resting place. */
    @Test
    fun `a release anywhere inside the thresholds survives`() {
        for (fraction in listOf(0.22f, 0.35f, 0.5f, 0.61f, 0.78f)) {
            val committed = split().withDraggedFraction(fraction).commitDraggedFraction()
            assertTrue("$fraction should stay split", committed.isSplit)
            assertEquals(fraction, committed.splitFraction, 0.001f)
        }
    }

    /**
     * The overflow's "Move divider" still walks the four steps.
     *
     * The steps moved out of the drag and into the button: a button has no
     * position to remember, so a fixed set is right for it and only for it.
     */
    @Test
    fun `every step is reachable and snapped by the menu`() {
        val steps = split().splitSteps()
        assertEquals(4, steps.size)
        steps.forEach { step ->
            // Delta, not equality: 0.2 * 3 is not 0.6 in binary floating point,
            // and a test asserting bit equality would fail on the arithmetic
            // rather than on the behaviour.
            assertEquals(step, split().withSplitFraction(step).splitFraction, 0.001f)
            // And a release still lands on it when it was dragged there.
            assertEquals(step, split().withDraggedFraction(step).commitDraggedFraction().splitFraction, 0.001f)
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

    /**
     * Which slot survives depends on the swap flag.
     *
     * Asked of [PaneLayout.collapsedSurvivingSlot] on a *dragged* layout, not a
     * committed one: a commit that reached the collapse threshold returns a
     * layout with no second pane, and asking a layout without a second pane
     * which slot survived is a question about nothing. The drag is the state in
     * which the decision has not been taken yet, which is exactly when the
     * caller needs it.
     */
    @Test
    fun `a swap inverts which slot survives a collapse`() {
        val normal = split().withDraggedFraction(0.05f)
        val swapped = split().swapped().withDraggedFraction(0.05f)

        // Still split: the drag has not been released yet.
        assertTrue(normal.isSplit)
        assertTrue(swapped.isSplit)

        assertEquals(PaneSlot.PRIMARY, normal.collapsedSurvivingSlot())
        assertEquals(PaneSlot.SECONDARY, swapped.collapsedSurvivingSlot())
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

    /**
     * Generous, not unlimited — and the value asked for here has to be *past*
     * the limit to prove anything.
     *
     * The first version of this test asked to park the pane at -0.25 and
     * expected -OVERSCAN back, with OVERSCAN at 0.30. It asserted the clamp by
     * accident: -0.25 was inside the range, so the answer was -0.25, and the
     * test failed for having picked a number that was not actually extreme.
     */
    @Test
    fun `a floating pane can be pushed well off the edge`() {
        val parked = split().floating()
            .withFloatingBounds(NormalizedRect(-0.25f, 0.5f, 0.6f, 0.4f))
        // -0.25 is legal under a 0.30 overscan, so it is taken as asked.
        assertEquals(-0.25f, parked.floatingBounds.left, 0.001f)

        // Past the limit, it stops at the limit rather than leaving the host.
        val pinned = split().floating()
            .withFloatingBounds(NormalizedRect(-0.9f, 0.5f, 0.6f, 0.4f))
        assertEquals(-PaneLayout.OVERSCAN, pinned.floatingBounds.left, 0.001f)
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

    /**
     * Two separate claims, so a failure says which one broke.
     *
     * The first version chained them into one expression, and `split()` already
     * carries a session — so `withSecondary("")` was a no-op that left it split,
     * and the assertion failed on the wrong half. The blank-id guard is about
     * starting from nothing.
     */
    @Test
    fun `a blank secondary id is refused`() {
        assertFalse(PaneLayout.EMPTY.withSecondary("").isSplit)
        assertFalse(PaneLayout.EMPTY.withSecondary("   ").isSplit)
    }

    /** And an existing split is left alone rather than blanked. */
    @Test
    fun `a blank secondary id does not blank an existing one`() {
        assertEquals("b", split().withSecondary("").secondarySessionId)
        assertEquals("b", split().withSecondary("  ").secondarySessionId)
    }

    /** Dropping the session that is already second must not reset the divider. */
    @Test
    fun `re-opening the same session leaves the divider alone`() {
        val arranged = split().withSplitFraction(0.6f)
        assertEquals(0.6f, arranged.withSecondary("b").splitFraction, 0.001f)
    }


    // ── Parking at an edge ─────────────────────────────────────────────────────

    @Test
    fun `a floating pane released near the left edge parks`() {
        val parked = split().floating()
            .withFloatingBounds(NormalizedRect(0.03f, 0.4f, 0.5f, 0.4f))
            .withEdgeSnap()

        assertTrue(parked.edgeSnapped)
        assertEquals(0f, parked.floatingBounds.left, 0.001f)
        assertEquals(PaneLayout.EDGE_SLIVER, parked.floatingBounds.width, 0.001f)
    }

    @Test
    fun `a floating pane released near the right edge parks on the right`() {
        val parked = split().floating()
            .withFloatingBounds(NormalizedRect(0.45f, 0.4f, 0.5f, 0.4f))
            .withEdgeSnap()

        assertTrue(parked.edgeSnapped)
        assertEquals(1f - PaneLayout.EDGE_SLIVER, parked.floatingBounds.left, 0.001f)
    }

    /**
     * A pane left in the middle at a silly size is a mistake, not a parking spot,
     * and snapping it would hide that it was a mistake.
     */
    @Test
    fun `a pane released in the middle does not park`() {
        val loose = split().floating()
            .withFloatingBounds(NormalizedRect(0.25f, 0.25f, 0.5f, 0.5f))
            .withEdgeSnap()

        assertFalse(loose.edgeSnapped)
        // Untouched entirely: same position and same size it was left at.
        assertEquals(0.25f, loose.floatingBounds.left, 0.001f)
        assertEquals(0.5f, loose.floatingBounds.width, 0.001f)
    }

    @Test
    fun `a docked pane is never parked`() {
        val docked = split().withFloatingBounds(NormalizedRect(0f, 0f, 0.5f, 0.5f)).withEdgeSnap()
        assertFalse(docked.edgeSnapped)
    }

    /** Coming back has to restore a usable size, not the sliver. */
    @Test
    fun `unparking restores a readable pane`() {
        val parked = split().floating()
            .withFloatingBounds(NormalizedRect(0.02f, 0.4f, 0.5f, 0.4f))
            .withEdgeSnap()
        val back = parked.unedgeSnapped()

        assertFalse(back.edgeSnapped)
        assertTrue(
            "a restored pane must be wider than the sliver",
            back.floatingBounds.width > PaneLayout.EDGE_SLIVER,
        )
    }

    @Test
    fun `unparking a pane that was not parked changes nothing`() {
        val floating = split().floating()
        assertEquals(floating, floating.unedgeSnapped())
    }

    /** Docking and floating must both clear the parked state, or it lingers. */
    @Test
    fun `docking clears the parked state`() {
        val parked = split().floating()
            .withFloatingBounds(NormalizedRect(0.02f, 0.4f, 0.5f, 0.4f))
            .withEdgeSnap()
        assertFalse(parked.docked().edgeSnapped)
        assertFalse(parked.unedgeSnapped().floating().edgeSnapped)
    }

    // ── System overlay ─────────────────────────────────────────────────────────

    @Test
    fun `a system overlay is a window but not an in-app float`() {
        val overlay = split().systemOverlay()

        // Both are "a window", and the in-app one is the only one this
        // composition draws a frame for.
        assertTrue(overlay.isWindowed)
        assertTrue(overlay.isSystemOverlay)
        assertFalse(overlay.isFloating)
    }

    @Test
    fun `a docked pane is neither floating nor an overlay`() {
        val docked = split().docked()

        assertFalse(docked.isWindowed)
        assertFalse(docked.isSystemOverlay)
        assertFalse(docked.isFloating)
    }

    @Test
    fun `an in-app float is a window but not an overlay`() {
        val floating = split().floating()

        assertTrue(floating.isWindowed)
        assertFalse(floating.isSystemOverlay)
    }

    @Test
    fun `there is no overlay without a split`() {
        val solo = PaneLayout(secondarySessionId = null)

        assertEquals(solo, solo.systemOverlay())
        assertFalse(solo.isSystemOverlay)
    }

    @Test
    fun `maximising is ignored while the pane is in the overlay`() {
        // "Maximised" means fill the host, and the host is this app's window. An
        // overlay is not in it, so the toggle has to do nothing rather than act on
        // a rectangle nobody can see. Asserted as "unchanged" rather than "false",
        // because the flag is deliberately carried across the trip.
        val overlay = split().floating().toggleMaximized().systemOverlay()

        assertEquals(overlay.maximized, overlay.toggleMaximized().maximized)
    }

    @Test
    fun `maximised survives a move to the overlay so it comes back expanded`() {
        // The opposite of the test above: the flag is remembered rather than
        // dropped, because a user who expanded the pane wants it back that way.
        val overlay = split().floating().toggleMaximized().systemOverlay()

        assertTrue(overlay.maximized)
    }

    @Test
    fun `toggling an overlay docks it rather than bringing it back in-app`() {
        // The pane cannot be handed back by a toggle — the service owns it and
        // only stopping the service releases it. Docking is the honest half of
        // "bring the pane back".
        assertEquals(
            split().docked(),
            split().systemOverlay().togglePresentation(),
        )
    }

    @Test
    fun `an overlay is not edge-snapped`() {
        val parked = split().floating().withEdgeSnap().systemOverlay()

        assertFalse(parked.edgeSnapped)
    }

    @Test
    fun `docking from an overlay returns it to a docked pane`() {
        val docked = split().systemOverlay().dockedFromOverlay()

        assertEquals(PanePresentation.DOCKED, docked.presentation)
        assertFalse(docked.edgeSnapped)
    }

    @Test
    fun `docking from an overlay keeps the pane expanded`() {
        // The counterpart to the toggle test: the pane can neither be expanded nor
        // collapsed while it is in the overlay, so its size survives the trip and
        // the user gets back the pane they had.
        val maximised = split().floating().toggleMaximized().systemOverlay()

        assertTrue(maximised.dockedFromOverlay().maximized)
    }

    @Test
    fun `docking from an overlay on a pane that was never in one changes nothing`() {
        val floating = split().floating()

        assertEquals(floating, floating.dockedFromOverlay())
    }

    @Test
    fun `parking at an edge remembers the size it had and restores it`() {
        val sized = split().floating().withFloatingBounds(
            NormalizedRect(left = 0.10f, top = 0.20f, width = 0.60f, height = 0.50f),
        )

        val parked = sized.withEdgeSnap()

        assertTrue(parked.edgeSnapped)
        // Bringing it back must not reset it to the default rect.
        assertEquals(sized.floatingBounds, parked.unedgeSnapped().floatingBounds)
    }

    @Test
    fun `parking twice keeps the original size, not the second sliver`() {
        val sized = split().floating().withFloatingBounds(
            NormalizedRect(left = 0.10f, top = 0.20f, width = 0.60f, height = 0.50f),
        )

        val parked = sized.withEdgeSnap().withEdgeSnap()

        assertEquals(sized.floatingBounds, parked.unedgeSnapped().floatingBounds)
    }

    @Test
    fun `docking forgets the remembered float size`() {
        val sized = split().floating().withFloatingBounds(
            NormalizedRect(left = 0.10f, top = 0.20f, width = 0.60f, height = 0.50f),
        )

        assertNull(sized.withEdgeSnap().docked().preEdgeSnapBounds)
    }

    // ── What a relaunch is allowed to restore ─────────────────────────────────

    /**
     * A relaunch restores the divider's geometry and never the split.
     *
     * The app must come up as one full-width terminal. A split is something the
     * user arranged *in this session*, with a particular pair of sessions, and
     * arriving into one on launch looks like a bug rather than a convenience —
     * especially since the pair itself is not being restored, so the two halves
     * would not be the ones that were side by side last time.
     *
     * This is the one path where a stored layout is turned back into a live one,
     * and it is the only place that could quietly produce a split on its own, so
     * it gets its own test rather than relying on nothing having changed.
     */
    @Test
    fun `restoring stored geometry never restores a split`() {
        val stored = split().docked().withDraggedFraction(0.6f)

        val restored = PaneLayout.EMPTY.copy(
            splitFraction = stored.splitFraction,
            floatingBounds = stored.floatingBounds,
        )

        assertEquals(0.6f, restored.splitFraction, 0.001f)
        assertFalse(restored.isSplit)
        assertFalse(restored.isFloating)
        assertFalse(restored.isSystemOverlay)
    }

    @Test
    fun `an empty layout is not a split`() {
        assertFalse(PaneLayout.EMPTY.isSplit)
    }

    /**
     * Closing the split keeps the window where the user put it.
     *
     * `cleared()` used to rebuild the layout from its defaults, which reset the
     * floating window's geometry *and persisted the default*. So closing a
     * floating pane and opening it again gave a centred window rather than the
     * one the user had arranged — the split closed, and took their layout with it.
     *
     * What is about a pair of sessions goes; what is about one window stays.
     */
    @Test
    fun `clearing the split keeps the floating window geometry`() {
        val arranged = NormalizedRect(left = 0.08f, top = 0.31f, width = 0.55f, height = 0.4f)
        val layout = split().floating().copy(floatingBounds = arranged, secondarySwapped = true)

        val closed = layout.cleared()

        assertEquals(arranged, closed.floatingBounds)
        assertFalse(closed.isSplit)
        assertFalse(closed.isFloating)
        // A swap describes which of two sessions is on top, so it cannot outlive
        // the second one.
        assertFalse(closed.secondarySwapped)
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
