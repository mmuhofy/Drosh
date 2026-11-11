package dev.drosh.ui.keyboard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where Drosh Keyboard is docked on screen. */
enum class KeyboardWindowMode {
    /** Along the bottom edge, full width. The default. */
    FIXED,

    /** Free-floating over the content, like a desktop window. */
    FLOATING,

    /** No keyboard running, or its mode is unknown. Treated as FIXED. */
    UNKNOWN,
}

/**
 * Placement of Drosh Keyboard, as broadcast by it.
 *
 * Only relevant when Drosh Keyboard is the active IME. Anything else leaves
 * this at [KeyboardWindowMode.UNKNOWN], which every consumer treats as FIXED —
 * the safe default, since it is what every keyboard does.
 */
object KeyboardWindowModeState {

    /** Broadcast by Drosh Keyboard. Signature-permission protected. */
    const val ACTION_WINDOW_MODE = "dev.drosh.action.KEYBOARD_WINDOW_MODE"
    const val EXTRA_MODE = "mode"

    /**
     * Broadcasters must hold this. Declared at signature level in both apps,
     * so only builds signed with the shared Drosh key can tell Drosh where
     * the keyboard sits.
     */
    const val PERMISSION_SEND_KEYBOARD_MODE = "dev.drosh.my.permission.SEND_KEYBOARD_MODE"

    private val _mode = MutableStateFlow(KeyboardWindowMode.UNKNOWN)
    val mode: StateFlow<KeyboardWindowMode> = _mode.asStateFlow()

    /** True when content should stay clear of the keyboard. */
    val docked: Boolean get() = _mode.value != KeyboardWindowMode.FLOATING

    fun onBroadcast(raw: String?) {
        _mode.value = when (raw?.trim()?.lowercase()) {
            "floating" -> KeyboardWindowMode.FLOATING
            "fixed" -> KeyboardWindowMode.FIXED
            else -> KeyboardWindowMode.UNKNOWN
        }
    }

    fun reset() {
        _mode.value = KeyboardWindowMode.UNKNOWN
    }
}

/**
 * Reserves space for the keyboard only while it is docked.
 *
 * A floating keyboard sits *over* the content by design, so padding would push
 * the layout around for no reason — and would leave a dead band where the
 * keyboard happens to sit. Docked, the keyboard occupies the bottom and content
 * must move up.
 *
 * Falls back to padding whenever the mode is unknown, which includes every
 * other IME and any moment before the first broadcast.
 */
fun Modifier.droshImePadding(): Modifier =
    if (KeyboardWindowModeState.docked) imePadding() else this

/**
 * Keeps content clear of the navigation bar.
 *
 * Only the *visible* bar is reserved. Under gesture navigation the system draws
 * its swipe handle over the content and reports no bar at all, and reserving the
 * handle's strip left a band of empty terminal along the bottom — the content
 * was never hidden by it in the first place.
 *
 * Nothing is added while the keyboard is up: the keyboard covers the bar, and
 * its own inset already includes it.
 */
@Composable
fun Modifier.droshNavigationBarPadding(): Modifier {
    // The bar, and the strip the system keeps for its own gestures.
    //
    // A three-button bar reports its height as the navigation bar; gesture
    // navigation reports almost nothing there and keeps the swipe handle —
    // which is drawn over the content and takes its touches — in the mandatory
    // system gestures inset instead. Reserving only the bar left the last line
    // of output under the handle.
    //
    // The strip is not a band on the screen: the area belongs to the pane that
    // holds it, and the pane's own background is the terminal's, so what the
    // grid does not reach reads as terminal and not as app.
    val gestureStrip = WindowInsets.mandatorySystemGestures
        .asPaddingValues()
        .calculateBottomPadding()
    val barBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    if (imeBottom > 0.dp) return this

    return padding(bottom = maxOf(barBottom, gestureStrip))
}
