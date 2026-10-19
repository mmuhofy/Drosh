package dev.drosh.domain.terminal

/**
 * The terminal's two panes, as the split UI needs to see them.
 *
 * ## Why this exists
 *
 * The split screen lives in `:ui` and the panes are owned by the terminal layer.
 * `:ui` is forbidden from importing `:terminal` (AGENT.md §139), and the split
 * genuinely needs to *do* things to the panes — exchange them, point them at a
 * chosen pair — not merely read their geometry, which [PaneLayoutRepository]
 * already covers.
 *
 * So the two operations that cross the boundary are named here, and the
 * terminal layer implements them. Each returns a boolean rather than throwing:
 * every one of them can legitimately be refused, and a refusal is a normal
 * outcome here rather than an error — a pane can be empty, and a session can
 * have exited between the gesture and the call.
 *
 * Nothing about *layout* belongs here. Widths, heights and positions are
 * [PaneLayout]'s business and stay in it.
 */
interface PaneSessionBinder {

    /**
     * Exchanges the two panes' sessions.
     *
     * @return false when either pane is empty, so a swap cannot be applied to
     *         half a split.
     */
    fun swapPanes(): Boolean

    /**
     * Puts [primaryId] in the primary pane and [secondaryId] in the other.
     *
     * @return false when either session is not live. The caller must leave its
     *         own state alone in that case: a split showing one session from the
     *         intended pair and one from somewhere else is worse than no split.
     */
    fun setPaneSessions(primaryId: String, secondaryId: String): Boolean

    /**
     * Moves the second pane's session into the primary pane, leaving the second
     * empty.
     *
     * The collapse half of a divider drag. Dragging the seam to either end grows
     * one pane to full height, and the user closing the split that way plainly
     * means *that* pane — dropping the other session would throw away the one
     * they had just made room for. The primary pane is the app's active pane, so
     * the survivor has to be moved into it rather than merely left in place.
     *
     * No ids, because the caller does not hold them: the primary pane always
     * shows whichever session is active, and the split UI never sees the
     * terminal layer's pane bookkeeping.
     *
     * @return false when the second pane is empty, so a collapse cannot promote
     *         nothing and leave the primary holding a session the user did not
     *         choose.
     */
    fun promoteSecondaryToPrimary(): Boolean

    /**
     * The session id in the primary pane, or null when it is empty.
     *
     * Not the *active* session, which the rest of the app also has. They differ
     * whenever a split is up and the user has touched the lower pane, and the
     * question "is this session already on screen" is about a specific pane.
     * Answering it with the active session let the upper pane's own session be
     * bound to the lower one.
     */
    fun primarySessionId(): String?
}
