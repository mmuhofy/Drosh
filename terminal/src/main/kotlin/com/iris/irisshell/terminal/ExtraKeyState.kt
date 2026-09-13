package com.iris.irisshell.terminal

import com.iris.irisshell.domain.input.StickyModifierState

// Ported from: mmuhofy/IrisCode — app/src/main/kotlin/com/iris/iriscode/terminal/ExtraKeyState.kt
// Adapted for Iris Shell — com.iris.irisshell
//
// Originally used Compose runtime's mutableStateOf/getValue/setValue. Since this
// module does NOT apply the Compose plugin (Compose belongs to :ui and :app per
// AGENT.md §109, §139), we replace the observable properties with plain mutable
// Kotlin properties. The :ui layer wraps these in Compose-backed state where
// recomposition is needed.
//
// Implements [StickyModifierState] so the UI layer can depend on the
// `:domain` interface instead of importing this module directly — see
// AGENT.md §139 and `docs/MEMORYBANK.md` §8.

class ExtraKeyState : StickyModifierState {
    var ctrlActive: Boolean = false
    var ctrlLocked: Boolean = false
    var altActive: Boolean = false
    var altLocked: Boolean = false

    private var onModifierConsumed: (() -> Unit)? = null

    override fun setOnModifierConsumed(listener: () -> Unit) {
        onModifierConsumed = listener
    }

    private fun notifyConsumed() {
        onModifierConsumed?.invoke()
    }

    override fun tapCtrl() {
        if (ctrlLocked) {
            ctrlLocked = false
            ctrlActive = false
        } else if (ctrlActive) {
            ctrlActive = false
        } else {
            ctrlActive = true
        }
        notifyConsumed()
    }

    fun longPressCtrl() {
        ctrlActive = true
        ctrlLocked = true
    }

    fun readCtrl(): Boolean {
        if (!ctrlActive) return false
        if (!ctrlLocked) {
            ctrlActive = false
            notifyConsumed()
        }
        return true
    }

    override fun tapAlt() {
        if (altLocked) {
            altLocked = false
            altActive = false
        } else if (altActive) {
            altActive = false
        } else {
            altActive = true
        }
        notifyConsumed()
    }

    fun longPressAlt() {
        altActive = true
        altLocked = true
    }

    fun readAlt(): Boolean {
        if (!altActive) return false
        if (!altLocked) {
            altActive = false
            notifyConsumed()
        }
        return true
    }

    /**
     * Peek the sticky state WITHOUT consuming it. UI layer uses this to
     * paint the sticky highlight on [ExtraKeyButton]s.
     *
     * Pair: `readCtrl` consumes (auto-deactivates when not locked);
     * `peekCtrl` does NOT modify state.
     */
    override fun peekCtrl(): Boolean = ctrlActive || ctrlLocked

    /** [peekCtrl]'s sibling for ALT. */
    override fun peekAlt(): Boolean = altActive || altLocked

    /** StickyModifierState consumer pass-through to [readCtrl]. */
    override fun consumeCtrl(): Boolean = readCtrl()

    override fun consumeAlt(): Boolean = readAlt()
}
