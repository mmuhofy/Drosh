package dev.drosh.ui.keyboard

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Keeps content clear of the system's bottom strip.
 *
 * That strip is the navigation bar, or — under gesture navigation — the swipe
 * handle the system draws over the content and keeps for itself. Either way the
 * bottom of the window is the system's, and a grid that runs into it puts its
 * last line where the user can neither read it nor reach it.
 *
 * It is one inset rather than the keyboard's and the bar's added together: the
 * docked keyboard already covers the bar, and its safe drawing inset already
 * includes it. A floating keyboard sits over the content by design and takes
 * nothing from it.
 *
 * Where the system reserves nothing — the bars hidden, a device with neither —
 * the inset is zero and the terminal fills the screen, which is the other half
 * of fitting the screen exactly.
 */
@Composable
fun Modifier.droshNavigationBarPadding(): Modifier {
    // The bar, and the strip the system keeps for its own gestures.
    //
    // A three-button bar reports its height as the navigation bar; gesture
    // navigation reports almost nothing there, and the swipe handle — which is
    // still drawn over the content and still swallows touches — is in the
    // mandatory system gestures inset instead. A terminal only ever needs the
    // larger of the two, and using just one of them is how the last line of
    // output ends up under a handle that the layout never accounted for.
    val bottom = WindowInsets.safeDrawing
        .union(WindowInsets.mandatorySystemGestures)
        .asPaddingValues()
        .calculateBottomPadding()
    val barOnly = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // With a floating keyboard the content keeps its height: the keyboard sits
    // over it by design. Otherwise the safe drawing inset covers the keyboard,
    // the bar and the gesture strip at once, so nothing is added twice.
    return padding(bottom = if (KeyboardWindowModeState.docked) bottom else barOnly)
}
