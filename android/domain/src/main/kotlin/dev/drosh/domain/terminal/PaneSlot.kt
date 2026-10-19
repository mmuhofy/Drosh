package dev.drosh.domain.terminal

/**
 * Which of the at-most-two panes a piece of terminal UI belongs to.
 *
 * Drosh splits into exactly two. A tree of slots would allow arbitrary
 * nesting, which on a phone means panes too narrow to read a line of output
 * in — and every nested split is another divider the user has to discover and
 * another thing that can collapse to zero. Two panes is the most that is
 * genuinely usable at this width, so the model refuses to describe more.
 *
 * [PRIMARY] is not "the more important pane". It is the one that owns the
 * active session id that the rest of the app persists, and the one that
 * [dev.drosh.domain.session.SessionRepository] treats as active. Whichever
 * pane the user last touched holds that role.
 */
enum class PaneSlot {
    PRIMARY,
    SECONDARY;

    /** The other pane. */
    fun other(): PaneSlot = if (this == PRIMARY) SECONDARY else PRIMARY

    companion object {
        /**
         * The pane a fresh session opens in.
         *
         * Primary, because the secondary pane only exists once the user has
         * asked for a split; before that there is nothing to open it into.
         */
        val DEFAULT: PaneSlot = PRIMARY
    }
}