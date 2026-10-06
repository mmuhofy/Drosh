package dev.drosh.ui.pane

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.domain.terminal.PaneLayoutRepository
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
        viewModelScope.launch {
            val stored = repository.layout.first()
            if (_layout.value == PaneLayout.EMPTY) _layout.value = stored
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

    fun dock() = apply(_layout.value.docked())

    /** Expands the floating window to fill the host, or restores it. */
    fun toggleMaximized() = apply(_layout.value.toggleMaximized())

    // ── Gestures ──────────────────────────────────────────────────────────────

    /**
     * Moves the divider.
     *
     * Not persisted. A drag emits a value per frame and DataStore would rewrite
     * the same session id on every one of them; the position is written once,
     * on [commitSplitFraction], when the finger lifts.
     */
    fun dragSplitFraction(fraction: Float) {
        _layout.value = _layout.value.withSplitFraction(fraction)
    }

    /** Writes the divider position the drag left behind. */
    fun commitSplitFraction() {
        viewModelScope.launch { repository.setSplitFraction(_layout.value.splitFraction) }
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