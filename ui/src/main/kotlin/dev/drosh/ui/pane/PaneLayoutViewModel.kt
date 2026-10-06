package dev.drosh.ui.pane

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.domain.terminal.PaneLayoutRepository
import dev.drosh.domain.terminal.PaneSessionBinder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the split layout and the gestures that move it.
 *
 * The layout itself lives in [PaneLayoutRepository], not here. A ViewModel
 * survives a rotation but not a process death, and a rotation is not a session
 * boundary: the user who arranged two panes wants them back when the screen
 * turns, while the two shells behind them keep running either way.
 *
 * What *is* local is the drag. The divider and the floating window both move
 * continuously, and pushing each intermediate position through DataStore would
 * rewrite the session id and the float bounds for values that did not change.
 * So a drag updates [layout] in memory and only the divider is written through,
 * on release.
 */
@HiltViewModel
class PaneLayoutViewModel @Inject constructor(
    private val repository: PaneLayoutRepository,
    private val panes: PaneSessionBinder,
) : ViewModel() {

    private val _layout = MutableStateFlow(PaneLayout.EMPTY)

    /**
     * The live layout.
     *
     * Seeded from storage and then owned locally, so a pane can be dragged
     * without waiting on a disk round-trip.
     */
    val layout: StateFlow<PaneLayout> = _layout.asStateFlow()

    init {
        // Seeded, then owned.
        //
        // Reading the repository as a continuing flow would be wrong: its flow
        // is derived from the whole preferences file, so writing *any* setting
        // — the font size, a colour — re-emits and would overwrite an
        // un-persisted drag with the last saved value. Nothing writes to this
        // repository, so there is nothing to listen for after the first read.
        //
        // The stored *geometry* is read — where the divider was, where the
        // floating window sat — but **never the split itself**. A saved split
        // restored on launch is what made the app come up already divided,
        // which is not what "split" means to anyone: it is something you did in
        // this session, with this arrangement, and relaunching into it looks
        // like a bug rather than a feature. The two sessions stay where they
        // were; they are simply not sharing the screen until asked to.
        viewModelScope.launch {
            val stored = repository.layout.first()
            _layout.value = PaneLayout.EMPTY.copy(
                splitFraction = stored.splitFraction,
                floatingBounds = stored.floatingBounds,
            )
        }
    }

    // ── Opening and closing ───────────────────────────────────────────────────

    /**
     * Opens [sessionId] in the second pane, docked.
     *
     * A session already in the primary pane is refused. Two views driven by one
     * session is not something the emulator can serve — each attach resets the
     * other's scroll position — so this would render a pane fighting the first
     * one rather than a second terminal.
     */
    fun openSplit(sessionId: String, primarySessionId: String?) {
        if (sessionId.isBlank() || sessionId == primarySessionId) return
        apply(_layout.value.withSecondary(sessionId).docked())
    }

    /** Closes the second pane. */
    fun closeSplit() = apply(_layout.value.cleared(), persist = true)

    /**
     * Opens [sessionId] as a floating window rather than docked below.
     *
     * The same "open it in a second pane" intent as [openSplit], with the
     * presentation chosen up front — so the user does not have to split and
     * then remember that there was a second thing to toggle.
     */
    fun openFloating(sessionId: String, primarySessionId: String?) {
        if (sessionId.isBlank() || sessionId == primarySessionId) return
        apply(_layout.value.withSecondary(sessionId).floating())
    }

    /**
     * Closes the second pane if it is showing [sessionId].
     *
     * Separate from [closeSplit] because the trigger is not a deliberate act:
     * the session ended, or the user deleted it. A pane outliving its session
     * would render as an empty rectangle with no way to tell it apart from one
     * whose shell failed to start.
     */
    fun closeSplitIfShowing(sessionId: String) {
        if (_layout.value.secondarySessionId != sessionId) return
        closeSplit()
    }

    // ── Presenting the second pane ────────────────────────────────────────────

    /** Turns the second pane into a floating window, or docks it back. */
    fun togglePresentation() = apply(_layout.value.togglePresentation())

    fun float() = apply(_layout.value.floating())

    /**
     * Turns a floating window into a proper lower pane, with a divider.
     *
     * Replaces the maximise button, which expanded the window to fill the host.
     * That mode was a dead end: once full-bleed the window *was* the pane, so
     * the only way back was the same button, and a user who wanted their
     * terminal back had to go looking for it. Docking ends in a terminal you can
     * keep using.
     */
    fun dock() = apply(_layout.value.docked())

    /**
     * Drops the second pane and leaves the single one underneath.
     *
     * The close button on a floating window. Not [closeSplit] by another name:
     * this is reachable in one tap from a window that may be half off screen,
     * which is exactly where a "get this out of my way" button has to be.
     */
    fun closeFloatingPane() = apply(_layout.value.cleared(), persist = true)

    // ── Gestures ──────────────────────────────────────────────────────────────

    /**
     * Moves the divider.
     *
     * Not persisted, and not snapped. A drag emits a value per frame and
     * DataStore would rewrite the same session id on every one of them; the
     * position is written once, in [commitSplitFraction], when the finger lifts.
     *
     * Snapping is [PaneLayout.withDraggedFraction]'s job and is deliberately
     * *not* applied here: the pane has to follow the finger exactly while it is
     * moving, or the seam appears not to be under the finger at all.
     */
    fun dragSplitFraction(fraction: Float) {
        _layout.value = _layout.value.withDraggedFraction(fraction)
    }

    /**
     * Writes the divider position the drag left behind.
     *
     * A release at either end closes the split rather than committing a stub,
     * which is why this can end up with no second pane at all.
     */
    fun commitSplitFraction() {
        val settled = _layout.value.commitDraggedFraction()
        _layout.value = settled
        viewModelScope.launch { repository.setSplitFraction(settled.splitFraction) }
        if (!settled.isSplit) {
            viewModelScope.launch { repository.setLayout(settled) }
        }
    }

    /**
     * Splits so that [draggedId] is on top and [targetId] underneath.
     *
     * The drawer gesture: hold a session card, tap another one. The held card is
     * the primary, which is also what makes it the session the rest of the app
     * treats as active — the user pointed at it, so it is the one they are
     * looking at now.
     *
     * The layout's own swap flag is cleared rather than toggled: the panes are
     * being *built* here, not exchanged, so carrying a swap over from a previous
     * split would put the held session underneath, which is the exact opposite
     * of the gesture.
     *
     * A refused pair changes nothing. The terminal manager returns false when
     * either session is gone, and a half-applied split would leave panes bound
     * to a pair the user never chose.
     */
    fun splitWithPrimary(draggedId: String, targetId: String) {
        if (draggedId.isBlank() || targetId.isBlank() || draggedId == targetId) return
        if (!panes.setPaneSessions(draggedId, targetId)) return
        apply(
            _layout.value
                .withSecondary(targetId)
                .copy(secondarySwapped = false)
                .docked(),
        )
    }

    /**
     * Swaps which session is on top.
     *
     * The layout records the swap and the terminal manager does the exchanging,
     * because only the terminal layer knows which session is attached to which view.
     * A refusal — one pane empty, which happens if the divider was closed a
     * frame earlier — leaves the flag alone, so the card in the drawer cannot
     * claim an order the screen is not showing.
     */
    fun swapPanes() {
        if (!panes.swapPanes()) return
        val swapped = _layout.value.swapped()
        if (swapped == _layout.value) return
        _layout.value = swapped
        viewModelScope.launch { repository.setLayout(swapped) }
    }

    /**
     * Steps the divider to its next position.
     *
     * The same five positions a drag lands on, reachable without a drag at all
     * — which is the point of snapping to a small set. A finger that has to find
     * a 4dp seam while also holding a split open is a worse way to move it than
     * a menu entry, and this is that entry.
     *
     * Wraps rather than stopping at the ends, so it stays a cycle instead of
     * becoming a no-op the first time it is used past the last step.
     */
    fun cycleSplitFraction() {
        val steps = _layout.value.splitSteps()
        if (steps.isEmpty()) return
        val current = _layout.value.splitFraction
        val next = steps.firstOrNull { it > current + 0.001f } ?: steps.first()
        val moved = _layout.value.withSplitFraction(next)
        _layout.value = moved
        viewModelScope.launch { repository.setSplitFraction(moved.splitFraction) }
    }

    /** Moves or resizes the floating window. */
    fun dragFloatingBounds(bounds: NormalizedRect) {
        _layout.value = _layout.value.withFloatingBounds(bounds)
    }

    /**
     * Applies [next] to the in-memory layout and, unless this is a drag frame,
     * writes it through.
     */
    private fun apply(next: PaneLayout, persist: Boolean = true) {
        if (next == _layout.value && persist) return
        _layout.value = next
        if (!persist) return
        viewModelScope.launch { repository.setLayout(next) }
    }
}