package com.iris.irisshell.domain.input

/**
 * Read-only peek + stateful tap of the shared sticky-modifier container.
 *
 * Implementations live in `:terminal` (`ExtraKeyState`). The UI gets a
 * read-only handle via [peekCtrl]/[peekAlt] so it can paint sticky
 * highlights; the dispatcher calls into the same implementation to
 * arm/consume bytes per the next intent.
 *
 * See `docs/MEMORYBANK.md` §8 for the agreed sticky semantics.
 *
 * UNTESTED — verify on device.
 */
interface StickyModifierState {

    /**
     * Returns whether CTRL is currently sticky (active OR locked).
     * Does NOT consume the state.
     */
    fun peekCtrl(): Boolean

    /**
     * Returns whether ALT is currently sticky. Does NOT consume.
     */
    fun peekAlt(): Boolean

    /**
     * Consumes the sticky CTRL state if it is currently active.
     * Mirrors the behaviour of [com.iris.irisshell.terminal.ExtraKeyState.readCtrl]:
     * auto-deactivates after one read unless locked.
     *
     * Returns true if the next intent should see CTRL armed; false otherwise.
     */
    fun consumeCtrl(): Boolean

    /** ALT sibling of [consumeCtrl]. */
    fun consumeAlt(): Boolean

    /**
     * Toggles/Cycles the sticky CTRL. Matches the tap semantics of
     * `ExtraKeyState.tapCtrl()`.
     */
    fun tapCtrl()

    /** ALT sibling of [tapCtrl]. */
    fun tapAlt()

    /**
     * Register a listener invoked whenever the sticky state is consumed
     * by an external reader (e.g. hardware keyboard event in TerminalView
     * calls [consumeCtrl]/[consumeAlt]). The UI layer uses this to push
     * fresh `ctrlStuck`/`altStuck` values into [InputBarUiState].
     */
    fun setOnModifierConsumed(listener: () -> Unit)
}
