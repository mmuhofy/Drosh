package dev.drosh.terminal

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import dev.drosh.core.TerminalConstants
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.PaneSessionBinder
import dev.drosh.domain.terminal.PaneSlot
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages PTY session lifecycle, tab state, and id-keyed session lookup.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 *
 * Key improvements over the prior implementation:
 *  - [DroshSession] wrapper bundles TerminalSession + persistentId + name + pid,
 *    replacing the fragile 4-parallel-structure design (_sessions + _tabNames
 *    + _idToIndex + _indexToId) that could desync.
 *  - [onSessionFinished] now cleans up id mappings (like [closeTab] does) and
 *    notifies [SessionLifecycleCallbacks] so the data layer can sync Room.
 *  - PID tracking is wired through [TerminalSessionClientImpl.setTerminalShellPid],
 *    following Termux's TerminalSessionClient callback pattern.
 */
class TerminalManager(
    private val ubuntuBootstrap: UbuntuBootstrap,
    private val bootstrapStatePort: BootstrapStatePort,
    application: Application,
    private val blockEngineWire: BlockEngineWire? = null,
    private val settingsRepository: SettingsRepository,
) : PaneSessionBinder {

    // PaneSessionBinder: the split screen lives in :ui and cannot import this
    // class (AGENT.md §139), but it does need to exchange the panes and to
    // build a split from a chosen pair. Those two operations are the whole
    // surface; everything else about the layout stays in :domain.
    private val appContext: Context = application.applicationContext
    /**
     * Single source of truth for session storage. Each [DroshSession] bundles
     * the live [TerminalSession] with its persistent id, display name, and
     * shell pid — eliminating the prior risk of _sessions / _tabNames /
     * _idToIndex / _indexToId falling out of sync.
     */
    private val irisSessions: MutableList<DroshSession> = mutableListOf()

    private val _sessionCount = MutableStateFlow(0)
    val sessionCountFlow: StateFlow<Int> = _sessionCount.asStateFlow()

    /**
     * Reverse map: persistent session id (UUID, stored in Room) →
     * positional index into [irisSessions]. Inspired by ReTerminal's
     * SessionService id-keyed HashMap (github.com/RohitKushvaha01/ReTerminal,
     * file core/main/src/main/java/com/rk/terminal/service/SessionService.kt).
     */
    private val idToIndex: MutableMap<String, Int> = mutableMapOf()

    /**
     * Notified whenever the active session changes, with its persistent id or
     * null when the active one is not persisted.
     *
     * [TerminalManager] owns "which session is active" and this is the only way
     * that fact leaves it, so the persisted value cannot drift from the live
     * one. The data layer persists it; it must not push a value back, or the
     * two would argue.
     */
    var onActiveSessionChanged: ((String?) -> Unit)? = null

    private fun publishActiveId() {
        onActiveSessionChanged?.invoke(activePersistentId())
        // Command marks are per session, so the flow follows whichever pane has
        // focus — that is the one a keystroke is going to.
        val focusedSession = sessionForSlot(focusedPane.value)
        commandState.bind(focusedSession)
        // Same reasoning: `dedit` is a command, so it belongs to the session that
        // ran it — the focused one — not to whichever tab happens to be showing.
        editorRequests.bind(focusedSession)
    }

    /**
     * How much the running command is actually producing output.
     *
     * Lets the keyboard tell a working command from a stalled one without the
     * user looking away from it. Declared before [commandState] because the
     * publisher drives it from command transitions.
     */
    val commandActivity: CommandActivityTracker = CommandActivityTracker()

    /**
     * Command lifecycle of the active terminal, derived from OSC 133 marks.
     *
     * Consumed by the command state provider and, through it, by Drosh
     * Keyboard.
     */
    val commandState: CommandStatePublisher = CommandStatePublisher(commandActivity)

    /**
     * Paths the guest asked Drosh to open in the native editor, delivered by
     * the `editor` shell command.
     *
     * One-shot events, so this is a [kotlinx.coroutines.flow.Flow] rather than
     * state: there is nothing to read, only something to react to. Collected
     * by the terminal screen to navigate.
     */
    val editorRequests: EditorRequestPublisher = EditorRequestPublisher()

    /**
     * Ambient tint derived from the colours on screen, or null when neutral.
     *
     * See [AmbientTintCalculator] for why this reads style bits rather than
     * pixels.
     */
    val ambientTint: AmbientTintCalculator = AmbientTintCalculator()

    private var _currentAmbientTint: Int? = null

    /** Latest ambient tint, or null when the screen reads as neutral. */
    val currentAmbientTint: Int? get() = _currentAmbientTint

    private fun recomputeAmbientTint(): Int? {
        val session = sessionForSlot(focusedPane.value) ?: return null
        val emulator = session.emulator ?: return null
        return runCatching {
            ambientTint.compute(
                screen = emulator.getScreen(),
                colors = emulator.mColors,
                alternateBuffer = emulator.isAlternateBufferActive(),
            )
        }.getOrNull()
    }

    private val _activeTabIndex = MutableStateFlow(0)

    /**
     * Positional index into [irisSessions] for each pane, or [NO_PANE_SESSION]
     * when the pane is empty.
     *
     * Both panes hold explicit indices rather than one of them following
     * [currentSession]. That indirection is what made the original
     * single-view design work — one view, one "current" session — and it is
     * exactly what breaks with two: switching focus would drag the other
     * pane's session along with it, because both panes would be resolving
     * through the same index. Each pane keeps what it was given.
     */
    private val paneTabIndices: MutableMap<PaneSlot, Int> = mutableMapOf(
        PaneSlot.PRIMARY to 0,
        PaneSlot.SECONDARY to NO_PANE_SESSION,
    )

    private val _focusedPane = MutableStateFlow(PaneSlot.DEFAULT)
    val focusedPane: StateFlow<PaneSlot> = _focusedPane.asStateFlow()

    /**
     * Sessions currently in the alternate screen buffer — a TUI has the
     * terminal, not a shell.
     *
     * Per pane rather than one flag: the question is asked per pane because
     * the answer differs. A `vim` in the background pane says nothing about
     * whether the foreground pane is at a shell prompt, and a single boolean
     * would swap the foreground pane's whole renderer because of what the
     * other one is doing.
     */
    private val _altBufferByPane = MutableStateFlow<Map<PaneSlot, Boolean>>(emptyMap())

    /** True when the given pane is showing a TUI rather than shell output. */
    fun isAltBufferActive(slot: PaneSlot): Boolean =
        _altBufferByPane.value[slot] == true

    /**
     * Sessions currently live, as a flow.
     *
     * The split layout stores a session id, and that session can end while the
     * app runs. The UI needs to watch the live set to drop a pane whose session
     * has gone, and it cannot see [irisSessions] — only the ids that made it
     * into Room belong in the public surface.
     */
    private val _liveSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val liveSessionIdsFlow: StateFlow<Set<String>> = _liveSessionIds.asStateFlow()

    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    /**
     * Synchronous snapshot of the active tab index, intended for UI scaffolds
     * (e.g. the topbar's "1 / N" indicator) that do not need a Flow<T>.
     */
    fun getActiveTabIndexSnapshot(): Int = _activeTabIndex.value

    val tabCount: Int get() = irisSessions.size

    /**
     * The session the user is currently working in: whichever pane last had
     * focus.
     *
     * This is what the rest of the app means by "the" session — the one the
     * block engine ingests, the one a keystroke goes to, the one whose id is
     * persisted as active. It is deliberately not the primary pane's session:
     * with a split open, the user reading scrollback in the right-hand pane is
     * working in the right-hand pane.
     */
    val currentSession: TerminalSession?
        get() = sessionForSlot(focusedPane.value)

    /** Display names of all tabs, in positional order. */
    val tabNames: List<String>
        get() = irisSessions.map { it.name }

    val sessionClient: TerminalSessionClientImpl = TerminalSessionClientImpl()

    /**
     * Callback for session lifecycle events (finish, pid change).
     * Set by the data layer (SessionManagerAdapter) so Room stays
     * in sync with PTY state — inspired by Termux's TerminalSessionClient
     * callback flow (github.com/termux/termux-app).
     */
    var lifecycleCallbacks: SessionLifecycleCallbacks? = null

    /**
     * Emits a non-null [ProcessExitEvent] when a terminal session's subprocess
     * exits, carrying the exit code. Collected by [TerminalScreen] to show
     * the exit dialog. Call [clearProcessExitEvent] to dismiss.
     */
    private val _processExitEvent = MutableStateFlow<ProcessExitEvent?>(null)
    val processExitEvent: StateFlow<ProcessExitEvent?> = _processExitEvent.asStateFlow()

    data class ProcessExitEvent(val exitCode: Int)

    /**
     * True once the last session is gone, whether it was closed from the UI,
     * deleted from the sidebar, or was the shell the user exited by typing
     * `exit`. The UI turns this into the exit dialog so the user picks a new
     * session or quits, instead of a session silently reappearing.
     */
    private val _noSessionsLeft = MutableStateFlow(false)
    val noSessionsLeft: StateFlow<Boolean> = _noSessionsLeft.asStateFlow()

    fun clearProcessExitEvent() {
        _processExitEvent.value = null
    }

    /** Dismisses the no-sessions dialog. Called when a new session is created. */
    fun clearNoSessionsLeft() {
        _noSessionsLeft.value = false
    }

    /**
     * The [TerminalView] showing each pane, if that pane's view has been built.
     *
     * A view is registered by the Compose layer as it is created and dropped
     * when the pane goes away, so this map is empty for a pane that is not on
     * screen. Everything below tolerates that: a pane whose view has not
     * arrived yet still has an index, and attaching is a no-op until there is
     * something to attach to.
     *
     * [LinkedHashMap] rather than [HashMap] so [focusedPane] can hand the last
     * view back to the new one when a pane is torn down, without caring about
     * iteration order.
     */
    private val paneViews = LinkedHashMap<PaneSlot, TerminalView>()

    /**
     * Last cursor-blink rate seen from settings, replayed onto views as they
     * register.
     *
     * Held because the rate is a property of the pane, not of the view's
     * creation moment: applying it only on the settings flow meant a pane
     * opened after the user last touched the setting kept the emulator
     * default, so a second terminal blinked at a different speed from the
     * first.
     */
    private var cursorBlinkRateMs: Int = DEFAULT_CURSOR_BLINK_MS

    /** The pane's view, or null when the pane is not on screen. */
    fun viewForPane(slot: PaneSlot): TerminalView? = paneViews[slot]

    /** The focused pane's view — the one keystrokes and pastes go to. */
    val focusedView: TerminalView? get() = paneViews[focusedPane.value]

    /**
     * The pane showing [session], or null when it is on no pane.
     *
     * Found by asking the views rather than by keeping a session→pane map
     * alongside this one. [TerminalView.mTermSession] is the same fact, already
     * maintained by `attachSession`, so a second copy could only ever disagree
     * with it.
     */
    fun paneForSession(session: TerminalSession?): PaneSlot? {
        if (session == null) return null
        return paneViews.entries.firstOrNull { it.value.mTermSession === session }?.key
    }

    /** Redraws every pane that is showing [session]. */
    private fun redrawPanesShowing(session: TerminalSession) {
        paneViews.values.forEach { view ->
            if (view.mTermSession === session) view.onScreenUpdated()
        }
    }

    /**
     * Points [slot] at [sessionId].
     *
     * Returns false when the id is not live, so a caller can tell "pane opened"
     * from "pane refused a session that is gone" — the second needs the layout
     * left alone, because the id may become live again on the next reconcile
     * tick rather than being a mistake to unwind.
     */
    fun bindPaneToSession(slot: PaneSlot, sessionId: String?): Boolean {
        if (sessionId == null) {
            // No attach here: TerminalView.attachSession takes a non-null
            // session, and there is nothing to attach. The view keeps painting
            // the old session until the UI unmounts the pane, which unregisters
            // it — painting a stale frame for the frame or two before that is
            // better than growing a detach path on the vendored view for it.
            paneTabIndices[slot] = NO_PANE_SESSION
            return true
        }
        val index = getIndexForId(sessionId)
        if (index < 0) return false
        paneTabIndices[slot] = index
        paneViews[slot]?.let { view -> irisSessions[index].terminalSession.let(view::attachSession) }
        return true
    }

    /**
     * The primary pane's session id, for [PaneSessionBinder].
     *
     * Distinct from [activePersistentId], which is the *focused* pane's and is
     * what the sidebar highlights. The split needs to know which session the
     * upper pane is holding, and with a split up the two routinely differ.
     */
    override fun primarySessionId(): String? = sessionIdForSlot(PaneSlot.PRIMARY)

    /** The session id shown in [slot], or null when the pane is empty. */
    fun sessionIdForSlot(slot: PaneSlot): String? = irisSessions
        .getOrNull(paneTabIndices[slot] ?: NO_PANE_SESSION)
        ?.persistentId

    /**
     * Exchanges the two panes' sessions.
     *
     * Both the index map and the two Views are swapped, because the views hold
     * their own attachment: moving the indices alone would leave each view
     * drawing the session it was attached to, and the split would look correct
     * in the model while the terminal showed the old pairing.
     *
     * A one-pane layout is a no-op rather than an error — there is nothing to
     * exchange with, and the caller may be a double tap on a divider that was
     * closed a frame earlier.
     *
     * Focus follows the pane the user was looking at, so the swap does not also
     * move the keyboard somewhere they were not.
     */
    override fun swapPanes(): Boolean {
        val primary = paneTabIndices[PaneSlot.PRIMARY] ?: NO_PANE_SESSION
        val secondary = paneTabIndices[PaneSlot.SECONDARY] ?: NO_PANE_SESSION
        if (primary == NO_PANE_SESSION || secondary == NO_PANE_SESSION) return false

        paneTabIndices[PaneSlot.PRIMARY] = secondary
        paneTabIndices[PaneSlot.SECONDARY] = primary

        val primaryView = paneViews[PaneSlot.PRIMARY]
        val secondaryView = paneViews[PaneSlot.SECONDARY]
        if (primaryView != null && secondaryView != null) {
            val primarySession = sessionForSlot(PaneSlot.PRIMARY)
            val secondarySession = sessionForSlot(PaneSlot.SECONDARY)
            primarySession?.let(primaryView::attachSession)
            secondarySession?.let(secondaryView::attachSession)
        }
        return true
    }

    /**
     * Puts [sessionId] in the primary pane and [otherId] in the secondary one.
     *
     * The gesture that reaches this is "hold this card, drop it on that one", so
     * the held session is the primary. Making it primary also makes it the
     * session the rest of the app persists as active — which is what the user
     * meant by holding it.
     *
     * Returns false when either session is not live, and changes nothing in that
     * case: a half-applied swap would leave the panes bound to sessions that are
     * not the pair the user chose.
     */
    override fun setPaneSessions(primaryId: String, secondaryId: String): Boolean {
        if (primaryId == secondaryId) return false
        if (getIndexForId(primaryId) < 0 || getIndexForId(secondaryId) < 0) return false
        paneTabIndices[PaneSlot.PRIMARY] = getIndexForId(primaryId)
        paneTabIndices[PaneSlot.SECONDARY] = getIndexForId(secondaryId)
        paneViews[PaneSlot.PRIMARY]?.let { view ->
            irisSessions[paneTabIndices[PaneSlot.PRIMARY]!!].terminalSession.let(view::attachSession)
        }
        paneViews[PaneSlot.SECONDARY]?.let { view ->
            irisSessions[paneTabIndices[PaneSlot.SECONDARY]!!].terminalSession.let(view::attachSession)
        }
        return true
    }

    /**
     * Moves the second pane's session into the primary pane.
     *
     * What a divider drag to either end means. The pane the user grew to full
     * height is the one they are keeping, and the primary pane is the one the
     * rest of the app treats as active — so the survivor has to be moved into
     * it, not just left where it is. Without this the split closed by dragging
     * the seam down discarded the top pane and kept the bottom one, which is the
     * opposite of what the gesture asked for.
     *
     * Returns false, and changes nothing, when the second pane is empty.
     */
    override fun promoteSecondaryToPrimary(): Boolean {
        val secondary = paneTabIndices[PaneSlot.SECONDARY] ?: NO_PANE_SESSION
        if (secondary == NO_PANE_SESSION) return false

        paneTabIndices[PaneSlot.PRIMARY] = secondary
        paneTabIndices[PaneSlot.SECONDARY] = NO_PANE_SESSION

        paneViews[PaneSlot.PRIMARY]?.let { view ->
            irisSessions.getOrNull(secondary)?.terminalSession?.let(view::attachSession)
        }
        // The second pane keeps whatever it last had until the layout drops it:
        // the split UI unbinds it on the next composition, and clearing the index
        // first means anything reading it in between sees "empty" rather than a
        // session that is no longer supposed to be on screen.
        //
        // Everything below is the same tail [focusPane] runs, and for the same
        // reason: the primary pane's *contents* changed even though its view and
        // its focus did not, so the active session, the per-session command state
        // and the block engine's binding all have to be told. Without them the app
        // would keep reporting the session the user just closed as the active one
        // — a name that no longer had a terminal behind it.
        syncActiveTabIndex()
        // Focus has to follow the survivor.
        //
        // The primary pane is where the promoted session now lives, and leaving
        // focus on the second slot points it at a slot this function has just
        // emptied. `activePersistentId()` would then answer null, and since that
        // is what gets written back as the app's persisted active session, the
        // sidebar's highlight and the stored id would both disappear — the app
        // forgetting which session the user was in, from a gesture that was
        // meant to keep one.
        if (focusedPane.value != PaneSlot.PRIMARY) {
            paneViews[PaneSlot.PRIMARY]?.let { view ->
                _focusedPane.value = PaneSlot.PRIMARY
                _selectionBounds.value = null
                _hasSelection.value = false
                activatePaneView(PaneSlot.PRIMARY, view)
            }
        }
        val promoted = sessionForSlot(PaneSlot.PRIMARY)
        blockEngineWire?.onSessionChanged(sessionIdForSlot(PaneSlot.PRIMARY), promoted)
        publishAltBufferState()
        publishActiveId()
        return true
    }

    /** The session shown in [slot], or null when the pane is empty. */
    fun sessionForSlot(slot: PaneSlot): TerminalSession? = irisSessions
        .getOrNull(paneTabIndices[slot] ?: NO_PANE_SESSION)
        ?.terminalSession

    /**
     * Re-attaches whichever pane holds [index] to its current session.
     *
     * Called after a session's [TerminalSession] is replaced — a restart, or a
     * fallback shell being swapped in — because a view keeps its reference to
     * the dead session until something tells it otherwise. Matching on the
     * index rather than on the focused pane is what lets both panes recover
     * from a restart that touched only one of them.
     */
    private fun reattachPanesAt(index: Int) {
        paneTabIndices.forEach { (slot, slotIndex) ->
            if (slotIndex != index) return@forEach
            val session = irisSessions.getOrNull(index)?.terminalSession ?: return@forEach
            paneViews[slot]?.attachSession(session)
        }
    }

    /**
     * Re-points every pane at whatever its index currently resolves to, and
     * keeps [_activeTabIndex] agreeing with the focused pane.
     *
     * The bookkeeping after a session is added or removed: indices have shifted
     * for everyone, and the pane that lost its session has to fall back to
     * another one rather than keep pointing at a row that no longer exists.
     */
    private fun resyncPanes() {
        var focusMoved = false
        paneTabIndices.forEach { (slot, index) ->
            if (index == NO_PANE_SESSION) return@forEach
            val clamped = index.coerceIn(0, (irisSessions.size - 1).coerceAtLeast(0))
            if (clamped != index) paneTabIndices[slot] = clamped
            val session = irisSessions.getOrNull(clamped)?.terminalSession
            if (session == null) {
                paneTabIndices[slot] = NO_PANE_SESSION
                if (slot == focusedPane.value) focusMoved = true
            } else {
                paneViews[slot]?.attachSession(session)
            }
        }

        // A pane whose session vanished cannot stay focused — the app's idea of
        // "active session" would then name something that is not on screen.
        if (focusMoved || irisSessions.isEmpty()) {
            val fallback = PaneSlot.entries.firstOrNull {
                (paneTabIndices[it] ?: NO_PANE_SESSION) >= 0
            }
            if (fallback != null) _focusedPane.value = fallback
        }
        syncActiveTabIndex()
        publishAltBufferState()
    }

    /** Keeps [_activeTabIndex] pointing at the focused pane's session. */
    private fun syncActiveTabIndex() {
        val index = paneTabIndices[focusedPane.value] ?: NO_PANE_SESSION
        _activeTabIndex.value = index.coerceIn(0, (irisSessions.size - 1).coerceAtLeast(0))
    }

    /** Recomputes the per-pane alt-buffer flags from the live sessions. */
    private fun publishAltBufferState() {
        val next = HashMap<PaneSlot, Boolean>(PaneSlot.entries.size)
        PaneSlot.entries.forEach { slot ->
            val active = sessionForSlot(slot)?.emulator?.isAlternateBufferActive() ?: false
            if (active) next[slot] = true
        }
        _altBufferByPane.value = next
    }

    private val prootRunner: ProotRunner by lazy {
        ProotRunner(ubuntuBootstrap, application.applicationInfo.nativeLibraryDir)
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Whether the pane the user is actually looking at is running a TUI.
     *
     * [isAltBufferActive] answers the same question but is a plain function over
     * a `.value` read, so a composable collecting nothing would only ever see the
     * answer on the frame it happened to recompose for another reason. The top
     * bar's chrome is driven by this instead: a TUI owns the whole screen, so the
     * status bar has to go regardless of where the viewport is scrolled to.
     *
     * Joined on focus rather than published per pane, because the question the
     * chrome asks is about one screen — the foreground pane — and a vim in the
     * background pane must not collapse the controls out from under a shell the
     * user is still reading.
     */
    val focusedPaneAltBuffer: StateFlow<Boolean> =
        combine(_focusedPane, _altBufferByPane) { slot, byPane -> byPane[slot] == true }
            .stateIn(managerScope, SharingStarted.Eagerly, false)

    private var prootStartCommand: String = ""

    private var motdMode: MotdMode = MotdMode.PlainText
    private var motdText: String = TerminalConstants.DEFAULT_MOTD_TEXT

    var projectPath: String? = null

    /**
     * Detects the effective shell: if Oh My Zsh is installed, use zsh;
     * otherwise fall back to bash. This handles the case where zsh was chosen
     * but the OMZ install failed during bootstrap.
     */
    private fun recoverFallbackSessions() {
        if (!ubuntuBootstrap.isInstalled) return
        val fallbackIndices = irisSessions.mapIndexedNotNull { idx, s ->
            idx to s.isFallbackSession
        }.filter { it.second }.map { it.first }
        if (fallbackIndices.isEmpty()) return

        for (idx in fallbackIndices) {
            val old = irisSessions[idx]
            old.terminalSession.finishIfRunning()
            irisSessions[idx] = DroshSession(
                terminalSession = createNewSession(),
                persistentId = old.persistentId,
                name = old.name,
                pid = 0,
                isFallbackSession = false,
            )
            if (idx == _activeTabIndex.value) {
                reattachPanesAt(idx)
            }
        }
    }

    private fun effectiveShellPath(): String {
        val omzDir = File(ubuntuBootstrap.rootfsDir, "home/.oh-my-zsh")
        return if (omzDir.exists()) "/bin/zsh" else "/bin/bash"
    }

    init {
        sessionClient.onSessionFinished = { session -> onSessionFinished(session) }
        sessionClient.onTextChanged = { session ->
            redrawPanesShowing(session)
            commandActivity.onOutput()
            // Recomputed at most a few times per command; the screen has to
            // have changed for it to be worth asking.
            _currentAmbientTint = recomputeAmbientTint()
            val persistentId = getIndexOfSession(session)
                .takeIf { it >= 0 }
                ?.let { irisSessions[it].persistentId }
            blockEngineWire?.onSessionTextChanged(session, persistentId)
        }
        sessionClient.onAltBufferChanged = { session, isActive ->
            // Routed by pane rather than assigned to a shared flag: a TUI
            // opening in the background pane must not swap the foreground
            // pane's renderer out from under the user.
            // `return@label` is not available here: this lambda is assigned to
            // a property rather than passed to a function, so it has no
            // call-site label. An if reads the same and compiles.
            val slot = paneForSession(session)
            if (slot != null) {
                val next = _altBufferByPane.value.toMutableMap()
                if (isActive) next[slot] = true else next.remove(slot)
                _altBufferByPane.value = next
            }
        }
        sessionClient.onPidChanged = { session, pid -> onSessionPidChanged(session, pid) }

        settingsRepository.prootStartCommand
            .onEach { cmd -> prootStartCommand = cmd }
            .launchIn(managerScope)

        settingsRepository.cursorStyle
            .onEach { style ->
                sessionClient.cursorStyle = when (style) {
                    "Block"     -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
                    "Beam"      -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
                    "Underline" -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
                    else      -> null
                }
                // Applied to every pane: cursor shape is a user preference about how
                // Drosh looks, not about one terminal. Applying it to the
                // focused view alone left the second pane with whatever style
                // it happened to be created with.
                paneViews.values.forEach { view ->
                    view.mEmulator?.setCursorStyle()
                    view.invalidate()
                }
            }
            .launchIn(managerScope)

        settingsRepository.cursorBlinkRateMs
            .onEach { rate ->
                // Held, not just applied: a view registered later must get the
                // current rate too, and the settings flow does not re-emit for
                // a pane that appears after the user last changed it.
                cursorBlinkRateMs = rate
                paneViews.values.forEach { view -> view.setTerminalCursorBlinkerRate(rate) }
            }
            .launchIn(managerScope)

        settingsRepository.motdMode
            .onEach { mode -> motdMode = mode }
            .launchIn(managerScope)

        settingsRepository.motdText
            .onEach { text -> motdText = text }
            .launchIn(managerScope)

        bootstrapStatePort.state
            .filter { it is UbuntuSetupState.Ready }
            .distinctUntilChanged()
            .onEach { recoverFallbackSessions() }
            .launchIn(managerScope)
    }

    fun updateProotStartCommand(command: String) {
        prootStartCommand = command
    }

    private val _selectionBounds = MutableStateFlow<android.graphics.Rect?>(null)
    val selectionBounds: StateFlow<android.graphics.Rect?> = _selectionBounds.asStateFlow()

    private val _hasSelection = MutableStateFlow(false)
    val hasSelection: StateFlow<Boolean> = _hasSelection.asStateFlow()

    /**
     * Routes the selection controller's changes out to Compose.
     *
     * Called from registerPaneView rather than from a composable effect: a
     * LaunchedEffect on first composition runs before the AndroidView factory
     * has registered the view, so it returned early and the platform ActionMode
     * stayed on. Binding it where the view is actually attached cannot miss.
     *
     * Only the focused pane publishes. The selection menu is drawn once, over
     * whichever pane has it, and [selectionBounds] is in view pixels — feeding
     * it the unfocused pane's bounds would anchor the menu to a rectangle
     * belonging to a different terminal, at coordinates the host cannot
     * reconcile with its own.
     */
    private fun bindSelectionMenu(view: TerminalView, slot: PaneSlot) {
        view.installSelectionMenu(enabled = false) {
            // The highlight is painted inside TerminalView.onDraw, so the view
            // has to be invalidated for a selection change to be visible. The
            // handles reposition themselves, which is why select-all appeared
            // to do nothing: nothing moved the pixel content.
            view.invalidate()
            if (slot != focusedPane.value) return@installSelectionMenu
            val bounds = view.selectionBounds()
            _selectionBounds.value = bounds
            _hasSelection.value = bounds != null
        }
        view.notifySelectionChanged()
    }

    /**
     * Registers [view] as the terminal view for [slot].
     *
     * Every pane gets its own view rather than sharing one: a View holds a
     * single [TerminalSession], so two sessions cannot be drawn by one of them.
     * That makes several of this manager's single-value flows genuinely
     * per-pane — selection, scroll position, alt-buffer — and each is resolved
     * here against whichever pane currently has focus.
     */
    fun registerPaneView(slot: PaneSlot, view: TerminalView, context: Context) {
        // Drop any view this slot already had *before* installing the new one.
        // This is what makes handing a pane between windows safe: the overlay
        // service registers its view for a slot whose view may still be attached
        // in the activity, because the composition that owns the old one has not
        // necessarily been torn down yet when the service is asked to come up.
        // Installing over it instead would leave two views on one session, and
        // since attachSession resets the emulator, the two would overwrite each
        // other's screen rather than mirror it.
        //
        // Torn down quietly rather than through [unregisterPaneView]: that one is
        // for a pane going away for good, so it republishes focus and picks a
        // fallback, and none of that is true here. The slot is about to be handed
        // a view immediately, and a focus that briefly jumped to the other pane
        // mid-handover would be visible as a jump.
        paneViews.remove(slot)?.let { stale ->
            stale.installSelectionMenu(enabled = false, listener = null)
        }

        paneViews[slot] = view
        sessionClient.clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        view.setTerminalCursorBlinkerRate(cursorBlinkRateMs)

        // The pane may have been given its session before its view existed —
        // the layout is restored from storage before the first composition
        // builds anything, and a pane arriving in the overlay window has no
        // composition of its own to wait for — so attach now rather than waiting
        // for a change that has already happened.
        sessionForSlot(slot)?.let { view.attachSession(it) }

        if (slot == focusedPane.value) activatePaneView(slot, view)
    }

    /**
     * Makes [view] the one that reports scroll position, selection and pastes.
     *
     * Only the focused pane reports: the selection, the clipboard and the
     * keyboard all mean "the terminal the user is looking at", and a pane the
     * user is not looking at must not answer for any of them.
     */
    private fun activatePaneView(slot: PaneSlot, view: TerminalView) {
        sessionClient.terminalView = view
        bindSelectionMenu(view, slot)
    }

    /**
     * Moves keyboard focus to [slot].
     *
     * Focus is what makes a pane the active one app-wide, so this also rebinds
     * the flows that describe "the current terminal" and republishes the active
     * session id. A pane with no session cannot take focus; there would be
     * nothing to be active about.
     */
    fun focusPane(slot: PaneSlot) {
        val view = paneViews[slot] ?: return
        if (sessionForSlot(slot) == null) return
        if (_focusedPane.value == slot) {
            view.requestFocus()
            return
        }
        _focusedPane.value = slot
        _selectionBounds.value = null
        _hasSelection.value = false
        activatePaneView(slot, view)
        blockEngineWire?.onSessionChanged(sessionIdForSlot(slot), sessionForSlot(slot))
        publishAltBufferState()
        publishActiveId()
        view.requestFocus()
    }

    fun unregisterPaneView(slot: PaneSlot) {
        val view = paneViews.remove(slot) ?: return
        // Drop the callbacks before dropping the reference, or the view keeps a
        // strong reference to this manager after the pane is gone.
        view.installSelectionMenu(enabled = false, listener = null)

        if (slot != focusedPane.value) return

        _selectionBounds.value = null
        _hasSelection.value = false
        val fallback = PaneSlot.entries.firstOrNull {
            it != slot && paneViews.containsKey(it) && sessionForSlot(it) != null
        }
        if (fallback != null) {
            _focusedPane.value = fallback
            paneViews[fallback]?.let { activatePaneView(fallback, it) }
        } else {
            // Nothing on screen left to be active.
            sessionClient.terminalView = null
        }
        publishActiveId()
    }

    /**
     * Opens a session bound to a Room row.
     *
     * [persistentId] is required rather than optional. A session opened without
     * one has no row, is left out of [liveSessionIds] because that filters
     * nulls, and so is never reconciled, never closed and never restored — the
     * sidebar would list a session the system does not know it has. Every
     * session now enters through the repository first.
     */
    fun addTabWithId(persistentId: String, name: String): TerminalSession {
        val wasInstalled = ubuntuBootstrap.isInstalled
        val irisSession = DroshSession(
            terminalSession = createNewSession(),
            persistentId = persistentId,
            name = name,
            isFallbackSession = !wasInstalled,
        )
        irisSessions.add(irisSession)
        val newIndex = irisSessions.size - 1
        idToIndex[persistentId] = newIndex
        // Into the focused pane, matching the old single-view behaviour where
        // opening a session always showed it. The pane it displaces stays live
        // and keeps its place in the sidebar — it simply is not on screen,
        // which is exactly what happened before the split existed.
        paneTabIndices[focusedPane.value] = newIndex
        // A brand new session has no scrollback and has not been touched, which
        // is exactly the state the chrome treats as "first screen".
        _sessionCount.value = irisSessions.size
        _liveSessionIds.value = liveSessionIds()
        // A session exists again, so the exit dialog no longer applies.
        _noSessionsLeft.value = false
        // Block mode shares these sessions; point the block store at the new one.
        blockEngineWire?.onSessionChanged(persistentId, irisSession.terminalSession)
        paneViews[focusedPane.value]?.attachSession(irisSession.terminalSession)
        syncActiveTabIndex()
        publishAltBufferState()
        publishActiveId()
        return irisSession.terminalSession
    }

    /**
     * Look up the positional tab index for a persistent session id, or
     * `-1` if the id is unknown / the session was closed.
     */
    fun getIndexForId(persistentId: String): Int =
        idToIndex[persistentId] ?: -1

    /**
     * Reverse lookup: positional index → persistent id.
     */
    fun getIdForIndex(index: Int): String? =
        irisSessions.getOrNull(index)?.persistentId

    /**
     * Creates an interactive SSH session backed by the bridged TerminalSession
     * from [sshSessionFactory]. Not reconciled against Room rows — it lives
     * only as long as the process, like a local PTY session.
     *
     * [persistentId] is supplied by the caller via the SshSessionLauncher so
     * the launch result can be surfaced to the user.
     */
    fun addSshSession(
        persistentId: String,
        name: String,
        terminalSession: TerminalSession,
    ): TerminalSession {
        val irisSession = DroshSession(
            terminalSession = terminalSession,
            persistentId = persistentId,
            name = name,
        )
        irisSessions.add(irisSession)
        val newIndex = irisSessions.size - 1
        idToIndex[persistentId] = newIndex
        paneTabIndices[focusedPane.value] = newIndex
        // A brand new session has no scrollback and has not been touched, which
        // is exactly the state the chrome treats as "first screen".
        _sessionCount.value = irisSessions.size
        _liveSessionIds.value = liveSessionIds()
        _noSessionsLeft.value = false
        blockEngineWire?.onSessionChanged(persistentId, irisSession.terminalSession)
        paneViews[focusedPane.value]?.attachSession(irisSession.terminalSession)
        syncActiveTabIndex()
        publishAltBufferState()
        publishActiveId()
        return irisSession.terminalSession
    }

    /**
     * Look up the positional index of a [TerminalSession] by reference.
     * Returns -1 if the session is not currently managed.
     */
    fun getIndexOfSession(session: TerminalSession): Int =
        irisSessions.indexOfFirst { it.terminalSession === session }

    /**
     * Look up the [DroshSession] for a [TerminalSession] by reference.
     */
    private fun getDroshSession(session: TerminalSession): DroshSession? =
        irisSessions.find { it.terminalSession === session }

    /**
     * Switch to the session identified by [persistentId]. No-op when
     * the id is unknown. Used by [SessionManagerAdapter] when the UI
     * asks to change the active session.
     */
    fun switchSessionById(persistentId: String) {
        val idx = getIndexForId(persistentId)
        if (idx >= 0) switchTab(idx)
    }

    /**
     * Currently-active session's persistent id, or null if unknown.
     *
     * The focused pane's, not the primary pane's: this is what the sidebar
     * highlights and what survives a relaunch, and both should follow what the
     * user was last looking at.
     */
    fun activePersistentId(): String? = sessionIdForSlot(focusedPane.value)

    /**
     * Snippets for the keyboard. Read fresh on every call — the file is
     * small, and the service only calls this on command-state transitions,
     * so there is no hot loop to worry about. See [SnippetsStore] for the
     * format and the failure contract (missing or malformed reads as empty).
     */
    fun readSnippets(): List<SnippetsStore.Snippet> =
        SnippetsStore.load(File(ubuntuBootstrap.rootfsDir, "home"))

    /**
     * Snapshot of all session ids currently live in the terminal manager
     * (i.e. in [irisSessions] with a non-null [DroshSession.persistentId]).
     * Used by [SessionManagerAdapter] to reconcile Room state with live
     * PTY sessions.
     */
    /**
     * Rebuilds the id→index entries from [index] onwards.
     *
     * Removing a session shifts every position after it, and each removal site
     * used to redo that by hand. One site getting it wrong made
     * [getIndexForId] return a stale index, which silently selected the wrong
     * session.
     */
    private fun reindexFrom(index: Int) {
        for (i in index until irisSessions.size) {
            idToIndex[irisSessions[i].persistentId] = i
        }
    }

    fun liveSessionIds(): Set<String> =
        irisSessions.map { it.persistentId }.toSet()

    fun renameTab(index: Int, name: String) {
        if (index in irisSessions.indices) {
            irisSessions[index].name = name
        }
    }

    fun moveTab(from: Int, to: Int) {
        if (from == to) return
        if (from !in irisSessions.indices || to !in irisSessions.indices) return
        val session = irisSessions.removeAt(from)
        irisSessions.add(to, session)

        val rebaseRange = if (from < to) (from + 1)..to else to until from
        rebaseRange.forEach { idx ->
            val id = irisSessions[idx].persistentId
            idToIndex[id] = idx
        }

        // Panes hold indices, so they move with the rows. Left alone, a reorder
        // would leave each pane showing a different session than it did before,
        // without anything on screen changing shape to explain it.
        paneTabIndices.replaceAll { slot, index ->
            if (index == NO_PANE_SESSION) return@replaceAll index
            when {
                index == from -> to
                from < to && index in (from + 1)..to -> index - 1
                from > to && index in to until from -> index + 1
                else -> index
            }
        }
        syncActiveTabIndex()
    }

    fun restartCurrentTab() {
        val index = paneTabIndices[focusedPane.value] ?: NO_PANE_SESSION
        if (index !in irisSessions.indices) return
        val irisSession = irisSessions[index]

        val replacement = createNewSession()
        irisSession.terminalSession.finishIfRunning()
        irisSession.terminalSession = replacement

        _sessionCount.value = irisSessions.size
        // Same session id, so onSessionChanged would be a no-op. The diff anchor
        // has to be re-seeded regardless, or the new shell's first output is
        // diffed against the dead shell's transcript.
        blockEngineWire?.reanchor(replacement)
        reattachPanesAt(index)
    }

    /**
     * Kills every session and empties the list.
     *
     * The backstop behind "the last session did not actually close". Each
     * individual close sends SIGKILL and returns immediately, so a process can
     * still be alive for a moment after the list no longer mentions it. This is
     * called on the two ways out of the last-session dialog, so neither of them
     * can leave a shell running behind a finished app.
     */
    fun closeAll() {
        irisSessions.forEach { it.terminalSession.finishIfRunning() }
        irisSessions.clear()
        idToIndex.clear()
        paneTabIndices[PaneSlot.PRIMARY] = 0
        paneTabIndices[PaneSlot.SECONDARY] = NO_PANE_SESSION
        _focusedPane.value = PaneSlot.DEFAULT
        _activeTabIndex.value = 0
        _sessionCount.value = 0
        _altBufferByPane.value = emptyMap()
        _liveSessionIds.value = emptySet()
        _noSessionsLeft.value = false
        _processExitEvent.value = null
        // The view keeps painting the last screen until something else attaches;
        // both callers either finish the app or attach a new session right away.
        publishActiveId()
    }

    fun closeTab(index: Int) {
        if (index !in irisSessions.indices) return
        val irisSession = irisSessions[index]
        val persistentId = irisSession.persistentId
        irisSession.terminalSession.finishIfRunning()
        irisSessions.removeAt(index)
        _sessionCount.value = irisSessions.size

        idToIndex.remove(persistentId)
        reindexFrom(index)
        // Panes hold positional indices, so removing a session shifts every
        // pane below it and empties whichever one held it. resyncPanes does
        // both and hands focus to a pane that still has something to show,
        // rather than the index arithmetic this used to do for the active tab
        // alone — which left a second pane pointing at the wrong row.
        resyncPanes()
        _liveSessionIds.value = liveSessionIds()

        // The persistent row must be marked Closed for *every* close, not just
        // when the list empties. reconcile deliberately skips Closed rows when
        // spawning, and it only set the state itself for rows deleted from Room.
        // A session closed from the toolbar kept a Running row, so the next tick
        // saw it as not-live-but-openable and spawned it again.
        lifecycleCallbacks?.onSessionFinished(persistentId, -1)

        // Published unconditionally, including when the list has just emptied.
        // Skipping it there left the active id pointing at the session that was
        // closed, so the sidebar went on presenting a dead session as the live
        // one — green dot, "now" and all — and refused to clear it.
        publishActiveId()

        if (irisSessions.isEmpty()) {
            _noSessionsLeft.value = true
        }
    }

    /**
     * Shows the session at [index] in the focused pane.
     *
     * With a split open this cannot mean "make it the only thing on screen",
     * so a session already on screen in the other pane takes focus instead of
     * being opened twice. Two [TerminalView]s driven by one
     * [TerminalSession] is not a thing the emulator can serve: they would each
     * reset the other's scroll position on every attach.
     */
    fun switchTab(index: Int) {
        if (index < 0 || index >= irisSessions.size) return
        val slot = focusedPane.value
        if ((paneTabIndices[slot] ?: NO_PANE_SESSION) == index) return

        val other = slot.other()
        if ((paneTabIndices[other] ?: NO_PANE_SESSION) == index) {
            focusPane(other)
            return
        }

        paneTabIndices[slot] = index
        val target = irisSessions[index]
        // A different session is now what the user is looking at, so whatever
        // they had scrolled to belongs to the one that was there before.
        // Blocks are stored per session, so switching shows that session's
        // history instead of clearing the engine.
        blockEngineWire?.onSessionChanged(target.persistentId, target.terminalSession)
        paneViews[slot]?.attachSession(target.terminalSession)
        publishAltBufferState()
        publishActiveId()
    }

    private fun createNewSession(): TerminalSession {
        if (ubuntuBootstrap.isInstalled) {
            ensureShellRc()

            val guestWd = if (projectPath != null) {
                "/sdcard/dev.drosh/${File(projectPath!!).name}"
            } else null

            val shell = effectiveShellPath()
            val integration = DroshShellIntegration.install(appContext, shell)

            val cmd = prootRunner.build(
            guestWd,
            shell = shell,
            startCommand = prootStartCommand,
            environmentHooks = integration.environment,
            shellArgs = integration.shellArgs,
            )
            return TerminalSession(
                cmd.executable,
                cmd.cwd,
                cmd.argv.toTypedArray(),
                cmd.environment.toTypedArray(),
                3000,
                sessionClient
            )
        }

        return TerminalSession(
            "/system/bin/sh",
            "/",
            arrayOf("sh"),
            arrayOf("PATH=/system/bin:/system/xbin", "HOME=/", "TERM=vt100"),
            3000,
            sessionClient
        )
    }

    private fun ensureShellRc() {
        val d = "${'$'}"
        val homeDir = File(ubuntuBootstrap.rootfsDir, "home")
        val zshrc = File(homeDir, ".zshrc")
        val omzPath = File(homeDir, ".oh-my-zsh")

        // If Oh My Zsh is present, a full .zshrc was already written by
        // zshrc-write.sh during bootstrap — don't clobber it.
        if (zshrc.exists() && omzPath.exists()) return

        // ── MOTD file ──────────────────────────────────────────────────────────
        // When mode is PlainText, write the custom MOTD text to a separate file
        // that .zshrc sources on shell startup. For Compose/Disabled modes,
        // remove any stale MOTD file so the shell stays clean.
        val motdFile = File(homeDir, ".drosh_motd")
        if (motdMode == MotdMode.PlainText) {
            motdFile.writeText(motdText)
        } else {
            if (motdFile.exists()) motdFile.delete()
        }

        val cleanTemplate = """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export HOME=/home
                export TERM=xterm-256color
                export LANG=C.UTF-8
                export TMPDIR=/tmp

                HISTSIZE=5000
                HISTFILESIZE=10000

                alias ll='ls -la'
                alias la='ls -A'
                alias l='ls -CF'
                alias ..='cd ..'
                alias grep='grep --color=auto'

                PROMPT='%F{yellow}%n@drosh%f:%F{blue}%~%f${d} '

                if [[ -f "${d}HOME/.drosh_motd" ]]; then
                    cat "${d}HOME/.drosh_motd"
                    echo ""
                fi
        """.trimIndent() + "\n"

        if (!zshrc.exists()) {
            zshrc.writeText(cleanTemplate)
        }
    }

    /**
     * Called by [TerminalSessionClientImpl.onSessionFinished] when the PTY
     * process exits (either naturally or via [closeTab] → [finishIfRunning]).
     *
     * Fixes two bugs from the prior implementation:
     *  1. Was not cleaning up [idToIndex] mappings (unlike [closeTab]).
     *  2. Was not notifying [lifecycleCallbacks] so Room never learned
     *     the session exited — it stayed "Running" forever.
     *
     * If the session was already removed (e.g. by [closeTab] or [destroy]),
     * this is a no-op — the session was intentionally closed.
     */
    fun onSessionFinished(finishedSession: TerminalSession) {
        val idx = getIndexOfSession(finishedSession)
        if (idx < 0) return

        val irisSession = irisSessions[idx]
        val persistentId = irisSession.persistentId
        val exitCode = finishedSession.exitStatus

        irisSessions.removeAt(idx)
        _sessionCount.value = irisSessions.size

        idToIndex.remove(persistentId)

        reindexFrom(idx)
        // Same reasoning as closeTab: the indices every pane holds shift, and
        // whichever pane held the session that exited is now empty.
        resyncPanes()
        _liveSessionIds.value = liveSessionIds()

        lifecycleCallbacks?.onSessionFinished(persistentId, exitCode)

        // Only the last session is worth interrupting for. One session exiting
        // while siblings remain is routine — it just leaves the list, and the
        // user can pick another from the sidebar. The dialog is reserved for
        // the point where there is nothing left to switch to, so it appears when
        // the user tries to exit the final session and not on every exit.
        if (irisSessions.isEmpty()) {
            _processExitEvent.value = ProcessExitEvent(exitCode)
            _noSessionsLeft.value = true
        }
        // Same reasoning as closeTab: never let the recorded active session be
        // one that no longer exists.
        publishActiveId()
    }

    /**
     * Called by [TerminalSessionClientImpl.onPidChanged] when the shell pid
     * is assigned (during [TerminalSession.initializeEmulator]). Stores the
     * pid on the [DroshSession] and forwards it to [lifecycleCallbacks] so
     * the data layer can persist it if needed (PID tracking, inspired by
     * Termux's TerminalSessionClient.setTerminalShellPid).
     */
    private fun onSessionPidChanged(session: TerminalSession, pid: Int) {
        val irisSession = getDroshSession(session) ?: return
        irisSession.pid = pid
        lifecycleCallbacks?.onSessionPidChanged(irisSession.persistentId, pid)
    }

    fun destroy() {
        commandState.unbind()
        editorRequests.unbind()
        // Every pane's view, not just one: a pane left registered keeps this
        // manager alive through its selection and scroll listeners after the
        // screens that built it are gone.
        paneViews.values.forEach { view ->
                view.installSelectionMenu(enabled = false, listener = null)
        }
        paneViews.clear()
        sessionClient.terminalView = null
        irisSessions.forEach { it.terminalSession.finishIfRunning() }
        irisSessions.clear()
        idToIndex.clear()
    }

    suspend fun executeCommand(
        command: String,
        timeoutSec: Long = 30L,
        onOutput: suspend (String) -> Unit = {}
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!ubuntuBootstrap.isInstalled) {
            return@withContext ToolResult.Error("Ubuntu is not installed")
        }

        val guestWd = if (projectPath != null) {
            "/sdcard/dev.drosh/${File(projectPath!!).name}"
        } else null

        val cmd = prootRunner.buildBashCommand(command, guestWd, effectiveShellPath())

        try {
            val process = ProcessBuilder(cmd.argv)
                .directory(File(cmd.cwd))
                .apply {
                    environment().clear()
                    cmd.environment.forEach { entry ->
                        val eqIdx = entry.indexOf('=')
                        if (eqIdx > 0) {
                            environment()[entry.substring(0, eqIdx)] = entry.substring(eqIdx + 1)
                        }
                    }
                }
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    output.appendLine(l)
                    onOutput(l)
                }
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.Error(
                    "Command timed out after ${timeoutSec}s: $command"
                )
            }

            val exitCode = process.exitValue()
            val text = output.toString().trim()

            return@withContext if (exitCode == 0) {
                ToolResult.Success(
                    if (text.isNotEmpty()) text else "(no output)"
                )
            } else {
                ToolResult.Error(
                    if (text.isNotEmpty()) text else "(no output)"
                )
            }
        } catch (e: Exception) {
            ToolResult.Error("Command execution failed: ${e.message}")
        }
    }

    private companion object {
        /**
         * A pane with no session.
         *
         * Distinct from 0, which is a real index. Using -1 for "empty" is what
         * lets [paneTabIndices] hold either meaning without a second flag, and
         * it is why [syncActiveTabIndex] coerces rather than assigns — the
         * public active index cannot be negative, but the pane's can.
         */
        const val NO_PANE_SESSION = -1

        /**
         * Blinker rate assumed before settings have been read.
         *
         * A view registered in that window would otherwise keep the emulator's
         * own default for the rest of its life, because the settings flow has
         * already emitted and will not emit again.
         */
        const val DEFAULT_CURSOR_BLINK_MS = 600
    }
}
