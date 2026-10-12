package dev.drosh.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * The sidebar's pick-up-and-drop state, shared by every row.
 *
 * The old mechanic was press-and-hold-then-release-on-another-row: the
 * held row shrank in place, nothing followed the finger, and the target
 * was whichever row happened to be under the finger when it lifted. It
 * worked, and it felt like a form to fill in rather than like moving
 * something.
 *
 * This is the launcher version: the row lifts and follows the finger,
 * the row under it lights up with a preview of the split it would make,
 * and the drop is a spring. One holder rather than per-row state,
 * because the thing being dragged is one row and the thing being aimed
 * at is another, and neither can see the other.
 */
class SidebarDragState {

    /** The session currently picked up, or null. */
    var heldId by mutableStateOf<String?>(null)
        private set

    /** How far the held row has travelled from its own slot, in pixels. */
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The session under the held row right now, or null. */
    var hoveredId by mutableStateOf<String?>(null)
        private set

    /**
     * Every visible row's bounds in root coordinates, by session id.
     *
     * Written by each row as it is positioned and read on every drag
     * frame: the hit test is "which of these contains the held row's
     * centre", which needs the rectangles rather than a tree walk.
     * Entries for rows that scrolled away are replaced as they come back.
     */
    private val bounds = mutableMapOf<String, Rect>()

    /** Records where a row is. Called from the row's layout pass. */
    fun onRowPositioned(id: String, rect: Rect) {
        bounds[id] = rect
    }

    /** Drops a row's rectangle when it leaves the list. */
    fun onRowDisposed(id: String) {
        bounds.remove(id)
    }

    /** Picks [id] up. The offset starts at zero: the slot is the origin. */
    fun begin(id: String) {
        heldId = id
        offset = Offset.Zero
        hoveredId = null
    }

    /** Moves the held row by [delta] and re-aims. */
    fun drag(delta: Offset) {
        offset += delta
        reaim()
    }

    /** Puts the held row back and returns the session it was over. */
    fun end(): String? {
        val target = hoveredId
        heldId = null
        offset = Offset.Zero
        hoveredId = null
        return target
    }

    /** Ends the drag without a drop — a release over nothing, or a close. */
    fun cancel() {
        heldId = null
        offset = Offset.Zero
        hoveredId = null
    }

    /**
     * Finds the row under the held one.
     *
     * The held row's centre is its own rectangle moved by the drag offset;
     * the first other row containing that point is the target. Ties are
     * impossible — the rectangles do not overlap — but a row that scrolled
     * out from under the gesture simply stops being under it.
     */
    private fun reaim() {
        val held = heldId ?: run { hoveredId = null; return }
        val origin = bounds[held] ?: run { hoveredId = null; return }
        val centre = Offset(origin.left + origin.width / 2f + offset.x,
            origin.top + origin.height / 2f + offset.y)
        hoveredId = bounds.entries
            .firstOrNull { (id, rect) -> id != held && rect.contains(centre) }
            ?.key
    }

    /** True while [id] is the row being carried. */
    fun isHeld(id: String): Boolean = heldId == id

    /** True while [id] is the row being aimed at. */
    fun isTarget(id: String): Boolean = hoveredId == id && heldId != id

    /** True when any row is being carried, so every other row may light up. */
    val isDragging: Boolean get() = heldId != null
}
