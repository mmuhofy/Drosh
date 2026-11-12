package dev.drosh.ui.keyboard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
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
 * Reserves the keyboard's space, whatever kind of keyboard it is.
 *
 * The inset is the window's own: it is what the system says the keyboard covers,
 * and it is zero for a keyboard that floats over the content. Reading a mode
 * broadcast to decide whether to apply it meant that a keyboard whose mode never
 * arrived — any IME that is not Drosh's — left the last rows of the terminal
 * under the keyboard, where they cannot be read.
 */
fun Modifier.droshImePadding(): Modifier = imePadding()

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
    // The bar's own height, and only that.
    //
    // The band below the grid is the system's, and the render ends above it —
    // a row of output under a navigation bar is a row the user cannot read.
    // The strip the system keeps for its gestures is not reserved: under
    // gesture navigation it is tall, it is drawn over the content, and the
    // content under it is still readable.
    val barBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    if (imeBottom > 0.dp) return this

    return padding(bottom = barBottom)
}
