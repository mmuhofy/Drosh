package dev.drosh.domain.terminal

import kotlinx.coroutines.flow.StateFlow

/**
 * Hands a pane's terminal view to and from a window that is not this app's.
 *
 * ## Why this is a port
 *
 * The split screen lives in `:ui` and may not import `:terminal`. Floating a pane
 * over *other apps* is not a layout change at all — it is a different window,
 * owned by a foreground service, that outlives the activity — so it does not fit
 * [PaneLayout] the way `FLOATING` does, and it needs an operation that the layout
 * alone cannot express.
 *
 * Deliberately free of any Android type. In particular it has no way to ask for
 * the `SYSTEM_ALERT_WINDOW` permission and cannot return an `Intent` to send the
 * user to the settings screen: `:domain` is plain Kotlin, and a port that leaked
 * platform types into it would make every test of the split screen need a device.
 * The permission gate is therefore the caller's, and this port reports whether
 * the window is up.
 *
 * ## Why the view has to be handed over rather than shared
 *
 * A `TerminalView` holds one session, and attaching the same session to a second
 * view resets that view's emulator — so two views on one session corrupt each
 * other rather than mirroring. There is therefore never more than one view per
 * pane, and [attach] has to take the pane's existing view out of this app's
 * composition before the overlay can hold it. Callers must expect to be holding a
 * pane that is briefly not drawn at all: that gap is a frame, not a failure, and
 * the implementation is required to close it without the user seeing an empty
 * rectangle.
 */
interface SystemOverlayController {

    /** True while a pane is being shown in the overlay window. */
    val isAttached: StateFlow<Boolean>

    /**
     * Shows [slot]'s pane in the overlay window.
     *
     * @return false when the pane has no session, when the overlay permission has
     *         not been granted, or when the window could not be added. All three
     *         are refusals the caller is expected to survive rather than report:
     *         the user can revoke the permission while the app is open, and a
     *         session can exit between the tap and this call.
     */
    fun attach(slot: PaneSlot): Boolean

    /**
     * Takes the pane back out of the overlay, returning it to this app's window.
     *
     * @return false when nothing was attached, so a teardown or a second dock
     *         cannot report having done work it did not.
     */
    fun detach(): Boolean

    /**
     * True when the app may draw over other apps.
     *
     * A snapshot rather than a flow: it changes only through the settings screen,
     * and re-reading it on every [attach] is both sufficient and honest — a value
     * cached from app start is wrong for the entire session after a revoke.
     */
    fun canDrawOverlays(): Boolean
}