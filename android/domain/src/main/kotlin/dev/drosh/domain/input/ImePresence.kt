package dev.drosh.domain.input

import kotlinx.coroutines.flow.Flow

/**
 * Detects whether the companion **Drosh Keyboard** IME (package
 * `dev.drosh.ime`, including build variants such as `dev.drosh.ime.debug`)
 * is the currently selected input method.
 *
 * While it is active, the keyboard owns the special keys (Ctrl, Alt, Esc,
 * Tab) and the cursor movement, so Drosh's own on-screen extra-keys bar is
 * redundant and hides — one bar per screen, not two.
 *
 * The active IME can change while the app is backgrounded, so [refresh]
 * must be called on resume; the flow emits the latest known state on
 * collect.
 */
interface ImePresence {

    /** True while the Drosh Keyboard IME is the selected input method. */
    val droshKeyboardActive: Flow<Boolean>

    /** Re-reads the active input method. Call on activity resume. */
    fun refresh()
}
