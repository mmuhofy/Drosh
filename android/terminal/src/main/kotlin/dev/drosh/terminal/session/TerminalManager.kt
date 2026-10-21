package dev.drosh.terminal.session

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.block.ShellStateSource
import dev.drosh.domain.settings.MotdDefaults
import dev.drosh.domain.settings.BackgroundImageStore
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.PaneSessionBinder
import dev.drosh.domain.settings.GuestIsolation
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.linux.BootstrapStatePort
import dev.drosh.linux.ChrootNgRunner
import dev.drosh.linux.GuestCommand
import dev.drosh.linux.GuestIsolationOverride
import dev.drosh.linux.GuestLauncher
import dev.drosh.linux.GuestSpec
import dev.drosh.linux.ProotRunner
import dev.drosh.linux.UbuntuBootstrap
import dev.drosh.linux.UbuntuSetupState
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.drosh.terminal.session.input.EngineTerminalWriter
import dev.drosh.terminal.session.input.TerminalWriter
import dev.drosh.terminal.session.input.TermuxTerminalWriter
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
    private val settingsStore: SettingsStore? = null,
    private val backgroundImages: BackgroundImageStore? = null,
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
        val focusedSession = focusedSession()
        commandState.bind(focusedSession)
        // Same reasoning: `dedit` is a command, so it belongs to the session that
        // ran it — the focused one — not to whichever tab happens to be showing.
        editorRequests.bind(focusedSession)
    }

    /**
     * The session the focused pane runs, or null when it runs on the engine.
     *
     * An engine-backed pane has no session of the app's kind: its session
     * lives in the engine, and the command marks are the engine's own. So the
     * publishers that follow a session are bound to nothing while such a
     * pane has the focus — they would otherwise follow a session that is on
     * no screen.
     */
    private fun focusedSession(): TerminalSession? {
        val slot = focusedPane.value
        if (enginePanes.containsKey(slot)) return null
        return sessionForSlot(slot)
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
     *
     * Null when the focused pane runs on the engine: that session is not one
     * of the app's kind, and pointing a keystroke at a session that is on no
     * screen would be worse than pointing it at nothing.
     */
    val currentSession: TerminalSession?
        get() = focusedSession()

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

    /**
     * A terminal session's subprocess ended.
     *
     * @property exitCode the process's exit code.
     * @property reason why the session could not run, when the app knows. A
     *   missing guest runtime is a packaging fact, and the exit code alone
     *   ("exit 1") says nothing about it.
     */
    data class ProcessExitEvent(val exitCode: Int, val reason: String? = null)

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
     * The engine-backed pane of each slot, if that slot has one.
     *
     * The map above cannot hold them: Termux's view *attaches* to a session
     * the manager owns, while the engine's pane owns its view *and* its
     * session. One registry per shape keeps the Termux path exactly as it
     * was — nothing in it knows the engine exists — while a host that hosts
     * an engine pane gets the same two calls it has always used for a pane.
     */
    private val enginePanes = LinkedHashMap<PaneSlot, EnginePane>()

    /**
     * The watch on the focused engine pane's viewport.
     *
     * The engine's view has no scroll callback, so the offset is read from
     * the engine's own state instead; see [startEngineWatch]. It runs only
     * while an engine pane has the focus.
     */
    private var engineWatchJob: Job? = null

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

    /** The one blink clock, and the phase it is on. See [applyCursorBlinkRate]. */
    private var blinkJob: Job? = null
    private var blinkPhase: Boolean = false

    /** Last type behaviour from the file, replayed onto panes as they register. */
    private var boldBrightens: Boolean = true
    private var ligatures: Boolean = true

    /**
     * Whether the block engine is following this session's output at all.
     *
     * The wire is a transcript poller: on every chunk of output it serialises
     * the whole transcript, strips ANSI from it, diffs it against the previous
     * one and runs the prompt regexes. That work is proportional to the size of
     * the scrollback, and it was paid on every chunk whether or not the user
     * was looking at blocks — so a long `apt install` in classic mode kept
     * re-reading its own backlog, once per 64 KB of output, long after the
     * install was over.
     *
     * Classic mode never reads anything the wire produces (the input bar that
     * consumes `awaitingShellInput` lives on the block side of the screen), so
     * with the setting off the wire does not run. Defaults on until the setting
     * says otherwise, so a first launch that has not read the setting yet
     * behaves as it always did.
     */
    private var blockEngineEnabled: Boolean = true

    /** The theme's background, as a colour. Held so a late pane gets it too. */
    private var terminalBgColor: Int = DEFAULT_TERMINAL_BG_COLOR

    /** Applies [color] to [view] as the terminal's own background. */
    private fun applyTerminalBackground(view: TerminalView, color: Int) {
        view.setBackgroundColor(color)
    }

    /**
     * True while a background image is behind the terminal.
     *
     * The view's own background has to be transparent for the image to show:
     * the renderer paints no background for default cells, so the view's is
     * what shows — and an opaque one hides the image behind it completely.
     * The theme's colour returns the moment the image is gone.
     */
    private fun backgroundImageActive(): Boolean =
        backgroundImages?.imageFile?.value != null

    /** Re-applies every pane's background: the image, or the theme colour. */
    private fun applyTerminalBackgrounds() {
        val transparent = backgroundImageActive()
        paneViews.values.forEach { view ->
            if (transparent) {
                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                applyTerminalBackground(view, terminalBgColor)
            }
        }
    }

    /**
     * Starts — or stops — the one blink clock.
     *
     * The clock lives here rather than in each view: a view's own blinker is
     * armed against the emulator that happened to exist at the time, and the
     * emulator is replaced on every resize — the keyboard opening, a pane
     * swap, a session restart — which is exactly when the cursor stopped
     * blinking. One clock that re-applies the phase to whatever emulator each
     * pane holds right now cannot be orphaned by any of that, and every pane
     * blinks in step because there is only one of it.
     */
    private fun applyCursorBlinkRate(rate: Int) {
        cursorBlinkRateMs = rate
        blinkJob?.cancel()
        if (rate !in VALID_BLINK_RANGE) {
            // Off: the cursor is steady, and a blinker left running would keep
            // toggling a cursor nobody asked to see blink.
            paneViews.values.forEach { view ->
                view.mEmulator?.setCursorBlinkingEnabled(false)
                view.mEmulator?.setCursorBlinkState(true)
                view.invalidate()
            }
            return
        }
        blinkJob = managerScope.launch {
            while (isActive) {
                delay(rate.toLong())
                blinkPhase = !blinkPhase
                paneViews.values.forEach { view ->
                    val emulator = view.mEmulator ?: return@forEach
                    // Re-asserted every tick, not once: a new emulator's
                    // blinking flag starts false, and the flag is what makes
                    // the phase visible at all.
                    emulator.setCursorBlinkingEnabled(true)
                    emulator.setCursorBlinkState(blinkPhase)
                    view.invalidate()
                }
            }
        }
    }

    /** The pane's view, or null when the pane is not on screen. */
    fun viewForPane(slot: PaneSlot): TerminalView? = paneViews[slot]

    /**
     * Host file for a path inside the Linux environment.
     *
     * The rootfs is a plain directory, so "/home/Pictures/x.png" is that
     * directory plus the path — the same mapping the guest-file repository
     * uses, exposed here so the terminal screen can load a background image
     * without reaching into the bootstrap.
     */
    fun resolveGuestPath(guestPath: String): File =
        File(ubuntuBootstrap.rootfsDir, guestPath.removePrefix("/"))

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
        onBlockSessionChanged(sessionIdForSlot(PaneSlot.PRIMARY), promoted)
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
        // The shell behind this session is a new process with a new screen. If it
        // is on screen at all, what it is showing is not what the user scrolled.
        if (index == paneTabIndices[focusedPane.value]) resetViewportState()
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
            val active = computeAltBuffer(slot)
            if (active) next[slot] = true
        }
        _altBufferByPane.value = next
    }

    /**
     * Whether the terminal in [slot] is showing a program rather than a shell.
     *
     * A vendored session answers from its emulator; an engine-backed pane
     * answers from the engine's mode bits, which is where the alternate
     * screen lives on that side.
     */
    private fun computeAltBuffer(slot: PaneSlot): Boolean {
        val engine = enginePanes[slot]
        if (engine != null) {
            return engine.isAltBufferActive()
        }
        return sessionForSlot(slot)?.emulator?.isAlternateBufferActive() ?: false
    }

    /**
     * True while the viewport is at the live edge. This is what the UI
     * collects — there is deliberately no flow of the raw scroll row.
     *
     * A row changes once per row, and a fling through 30 rows emitted 30
     * updates, each one recomposing the whole terminal screen on the frame it
     * landed: the screen collected the row to re-derive, with the same rule,
     * the boolean already published here. The only thing the UI needs is which
     * side of the live edge it is on, so this flips at a threshold and is
     * silent the rest of the time. That is the difference between the bar
     * gliding and the bar stuttering along with your thumb.
     *
     * The threshold is not the edge itself. It is two, and the gap between them
     * is the dead zone — see [chromeIsAtLiveEdge].
     */
    private val _isAtLiveEdge = MutableStateFlow(true)

    val isAtLiveEdge: StateFlow<Boolean> = _isAtLiveEdge.asStateFlow()

    /**
     * Whether the user has moved this terminal's viewport at all since the session
     * it is showing was attached.
     *
     * The top chrome opens **collapsed** — a terminal nobody has touched goes
     * fullscreen and the pills sit in the status bar's band. That is not a scroll
     * position and cannot be expressed as one, because a freshly opened terminal
     * sits at the live edge exactly like a busy one does. Position alone cannot
     * tell "just opened, first screen" from "sitting at the prompt", so the
     * distinction has to be its own piece of state.
     *
     * One-way: the first scroll of a session is the moment it stops being "new",
     * and nothing in the app should make that untrue. Cleared by
     * [resetViewportState] wherever the session on screen is replaced.
     */
    private val _hasScrolled = MutableStateFlow(false)

    val hasScrolled: StateFlow<Boolean> = _hasScrolled.asStateFlow()

    /**
     * Back to "a terminal nobody has touched".
     *
     * Called wherever the focused pane's contents are replaced rather than
     * scrolled. Also puts the live edge back to true so the two flags cannot
     * disagree: a new session is at its live edge by definition, and leaving the
     * old answer in place would let a stale `isAtLiveEdge = false` reach the
     * chrome before the next scroll event corrects it.
     */
    private fun resetViewportState() {
        _isAtLiveEdge.value = true
        _hasScrolled.value = false
    }

    /**
     * Which guest isolation engine launches the user's shell.
     *
     * The user's choice, made in the setup wizard and kept here by a
     * collector, so switching it takes effect for the next session without a
     * restart. A device test can override it with a file in the app's own
     * storage, which wins over the setting — a test that cannot be sure which
     * engine it is testing is not a test:
     *
     *     adb shell run-as dev.drosh.my sh -c 'echo chroot_ng > files/guest_backend'
     */
    @Volatile
    private var guestIsolation: GuestIsolation = GuestIsolation.DEFAULT

    /**
     * The launcher for the engine the user chose.
     *
     * Built per session rather than cached: the choice is a setting, and a
     * session that starts after the switch must use the new engine.
     */
    private fun guestLauncher(): GuestLauncher =
        when (guestIsolation) {
            GuestIsolation.PROOT ->
                ProotRunner(ubuntuBootstrap, appContext.applicationInfo.nativeLibraryDir)

            GuestIsolation.CHROOT_NG ->
                ChrootNgRunner(
                    ubuntuBootstrap.rootfsDir,
                    appContext.applicationInfo.nativeLibraryDir,
                )
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

    /** The shell's locale, from the file; "" leaves the image's default. */
    private var shellLocale: String = ""

    private var motdMode: MotdMode = MotdMode.PlainText
    private var motdText: String = MotdDefaults.DEFAULT_MOTD_TEXT

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
        // The isolation engine is a setting, so it is read from the store rather
        // than asked for at every session: the store's flow keeps the value
        // fresh, and a device test's file override still wins over it.
        managerScope.launch {
            settingsRepository.guestIsolation.collect { chosen ->
                guestIsolation = GuestIsolationOverride.read(appContext) ?: chosen
            }
        }
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
            blockEngineWire?.takeIf { blockEngineEnabled }?.onSessionTextChanged(session, persistentId)
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

        settingsStore?.settings
            ?.onEach { settings ->
                shellLocale = settings.shell.locale
                boldBrightens = settings.terminal.boldBrightens
                ligatures = settings.terminal.ligatures
                val bg = parseHexColor(settings.terminal.palette.background)
                if (bg != null && bg != terminalBgColor) {
                    terminalBgColor = bg
                    applyTerminalBackgrounds()
                }
                paneViews.values.forEach { view ->
                    view.setTerminalTypeBehaviour(boldBrightens, ligatures)
                }
            }
            ?.launchIn(managerScope)

        // An import is not a settings change, so the image's own flow is what
        // tells the panes a background arrived or left.
        backgroundImages?.imageFile
            ?.onEach { applyTerminalBackgrounds() }
            ?.launchIn(managerScope)

        settingsRepository.cursorBlinkRateMs
            .onEach { rate ->
                applyCursorBlinkRate(rate)
            }
            .launchIn(managerScope)

        settingsRepository.motdMode
            .onEach { mode -> motdMode = mode }
            .launchIn(managerScope)

        settingsRepository.useBlockEngine
            .onEach { enabled ->
                if (blockEngineEnabled == enabled) return@onEach
                blockEngineEnabled = enabled
                // Turning the engine on mid-session: the wire stopped following
                // the screen while it was off, so its diff anchor describes a
                // transcript from before. Without a re-seed the first chunk
                // after the switch would diff the whole backlog and post all of
                // it as new output.
                if (enabled) reanchorBlockWire()
            }
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

    /**
     * Re-seeds the wire's diff anchor against every live session.
     *
     * Only meaningful when the engine is on; called the moment it is turned
     * back on, because everything the wire knew about the screen went stale
     * while it was off.
     */
    private fun reanchorBlockWire() {
        val wire = blockEngineWire ?: return
        irisSessions.forEach { session ->
            wire.reanchor(session.terminalSession)
        }
    }

    /**
     * Points the wire at a different session, when it is following at all.
     *
     * Re-seeding costs a full transcript read, which is the very work the
     * gate avoids — so a session switch in classic mode does not pay it.
     *
     * [shellState] is the source the shell's state comes from, when the
     * terminal reports one: an engine-backed session has the engine's marks
     * to read, a vendored session has none and the wire keeps inferring the
     * state from the screen.
     */
    private fun onBlockSessionChanged(
        sessionId: String?,
        session: TerminalSession?,
        shellState: ShellStateSource? = null,
    ) {
        if (!blockEngineEnabled) return
        blockEngineWire?.onSessionChanged(sessionId, session, shellState)
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
        // The pane may have been given its session before its view existed —
        // the layout is restored from storage before the first composition
        // builds anything, and a pane arriving in the overlay window has no
        // composition of its own to wait for — so attach now rather than waiting
        // for a change that has already happened.
        sessionForSlot(slot)?.let { view.attachSession(it) }

        // No blink call here: the manager's clock applies its phase to every
        // current view on the next tick, which is also how a pane registered
        // after a resize gets the right one.
        // The renderer is rebuilt by every size and typeface change, so the
        // type behaviour is held and replayed rather than applied once.
        view.setTerminalTypeBehaviour(boldBrightens, ligatures)
        // The terminal paints no background of its own for default cells -
        // the view's is what shows, and it is what a theme changes. With an
        // image behind it, the view's is transparent so the image shows.
        applyTerminalBackgrounds()

        // A slot with an engine-backed pane is the engine's: the view that is
        // registering does not get to report the selection or the scroll
        // position for it. Two reporters for one slot are two opinions about
        // the same terminal.
        if (slot == focusedPane.value && !enginePanes.containsKey(slot)) activatePaneView(slot, view)
    }

    /**
     * Makes [view] the one that reports scroll position, selection and pastes.
     *
     * Detaching first matters: the previous pane keeps a listener that writes the
     * same flows, so without this, scrolling the background pane would move the
     * chrome the user is not even reading.
     *
     * Only the focused pane reports: the selection, the clipboard and the
     * keyboard all mean "the terminal the user is looking at", and a pane the
     * user is not looking at must not answer for any of them.
     */
    private fun activatePaneView(slot: PaneSlot, view: TerminalView) {
        paneViews.forEach { (other, otherView) ->
            if (other != slot) {
                otherView.onScrollPositionChanged = null
            }
        }
        sessionClient.terminalView = view
        // Whichever pane takes focus is the one being read, and it starts at its
        // first screen as far as the chrome is concerned.
        resetViewportState()
        publishScroll(view)
        view.onScrollPositionChanged = { topRow ->
            if (slot == focusedPane.value) {
                val atEdge = chromeIsAtLiveEdge(topRow, _isAtLiveEdge.value)
                if (atEdge != _isAtLiveEdge.value) _isAtLiveEdge.value = atEdge
                // "Has been touched" means **has left the first screen**, not
                // "moved at all".
                //
                // Setting it on the first row put the two thresholds a row apart:
                // one flick up turned the status bar on, and the fifth row turned
                // it off again -- both inside a single gesture, which is the
                // flapping. Gating it on the same threshold the live-edge flag
                // uses means one direction change is one transition.
                //
                // Safe to read off this callback even though it also runs on
                // every chunk of PTY output: `onScreenUpdated` snaps `mTopRow`
                // back to the live edge before reporting, so `atEdge` is true for
                // anything it fires and the shell's own output can never answer
                // this with yes.
                if (!atEdge) _hasScrolled.value = true
            }
        }
        bindSelectionMenu(view, slot)
    }

    /** Republishes a view's live-edge state immediately. */
    private fun publishScroll(view: TerminalView) {
        // A session restored straight into the middle of its scrollback would
        // otherwise start with a stale "at the edge" and only correct itself on
        // the next scroll.
        val atEdge = chromeIsAtLiveEdge(view.mTopRow, _isAtLiveEdge.value)
        if (atEdge != _isAtLiveEdge.value) _isAtLiveEdge.value = atEdge
    }

    /**
     * Moves keyboard focus to [slot].
     *
     * Focus is what makes a pane the active one app-wide, so this also rebinds
     * the flows that describe "the current terminal" and republishes the active
     * session id. A pane with no session cannot take focus; there would be
     * nothing to be active about.
     *
     * A slot with an engine-backed pane goes to the engine's path: the pane
     * owns its session and its view, and the manager talks to the pane
     * rather than to a view.
     */
    fun focusPane(slot: PaneSlot) {
        val engine = enginePanes[slot]
        if (engine != null) {
            focusEnginePane(slot, engine)
            return
        }
        focusTermuxPane(slot)
    }

    /**
     * Moves keyboard focus to the Termux pane in [slot].
     *
     * The path the manager has always had; the engine's own path is
     * [focusEnginePane]. The two are kept apart on purpose: the Termux pane
     * attaches to a session the manager owns and reports through its view's
     * callbacks, while the engine's pane owns its session and reports
     * through the engine's own numbers. Sharing one method would mean
     * asking "which kind of pane is this" in every step of it.
     */
    private fun focusTermuxPane(slot: PaneSlot) {
        val view = paneViews[slot] ?: return
        if (sessionForSlot(slot) == null) return
        if (_focusedPane.value == slot) {
            view.requestFocus()
            return
        }
        // The engine pane that had the focus is told that it lost it.
        endEnginePaneFocus()
        _focusedPane.value = slot
        _selectionBounds.value = null
        _hasSelection.value = false
        activatePaneView(slot, view)
        onBlockSessionChanged(sessionIdForSlot(slot), sessionForSlot(slot))
        publishAltBufferState()
        publishActiveId()
        view.requestFocus()
    }

    /**
     * Moves keyboard focus to the engine-backed pane in [slot].
     *
     * The same bookkeeping as the Termux path — the viewport state is reset,
     * the selection is cleared, the active session is published and the
     * terminal is told that it has the focus — with the engine's own facts
     * in place of the view's.
     *
     * A pane with no running session cannot take the focus, exactly as a
     * Termux pane with no session cannot: there would be nothing to be
     * active about.
     */
    private fun focusEnginePane(slot: PaneSlot, pane: EnginePane) {
        if (pane.state != EnginePaneState.RUNNING) {
            return
        }
        if (_focusedPane.value == slot) {
            pane.focus()
            return
        }
        // The engine pane that had the focus is told that it lost it.
        endEnginePaneFocus()
        _focusedPane.value = slot
        _selectionBounds.value = null
        _hasSelection.value = false
        activateEnginePane(pane)
        // The block engine reads the shell's state from the engine's marks
        // when the session runs on the engine; a vendored session has no
        // marks to read and the wire keeps inferring from the screen.
        onBlockSessionChanged(null, null, EngineShellStateSource(pane.session))
        publishAltBufferState()
        publishActiveId()
        pane.notifyFocus(true)
        pane.focus()
    }

    /**
     * Ends the engine pane's turn: it is told that the focus left it, and
     * its scroll watch stops.
     *
     * The focus can leave an engine pane for a Termux pane, and an engine
     * pane can be unregistered while it has the focus — in both cases the
     * shell on the other side has to hear that the terminal is no longer the
     * one being typed in.
     */
    private fun endEnginePaneFocus() {
        engineWatchJob?.cancel()
        engineWatchJob = null
        enginePanes[focusedPane.value]?.notifyFocus(false)
    }

    /**
     * Makes [pane] the one that reports scroll position and selection.
     *
     * The engine's counterpart of [activatePaneView]. The same three things
     * happen — the viewport state is reset, the scroll position is published
     * and the selection is published — but they come from the engine's own
     * numbers instead of from a view's callbacks.
     */
    private fun activateEnginePane(pane: EnginePane) {
        // The Termux views stop reporting: the engine's pane is the one that
        // reports now, and two panes answering for the same chrome would be
        // two opinions about the same terminal.
        paneViews.values.forEach { view ->
            view.onScrollPositionChanged = null
            view.installSelectionMenu(enabled = false, listener = null)
        }
        pane.selectionListener = { hasSelection ->
            publishEngineSelection(pane, hasSelection)
        }
        resetViewportState()
        publishEngineScroll(pane)
        startEngineWatch(pane)
    }

    /**
     * Publishes the engine pane's selection.
     *
     * Only the focused pane publishes, exactly as the Termux path does: the
     * selection menu is drawn once, over whichever pane has it, and the
     * bounds are in that pane's own pixels.
     */
    private fun publishEngineSelection(pane: EnginePane, hasSelection: Boolean) {
        if (enginePanes[focusedPane.value] !== pane) return
        val bounds = if (hasSelection) pane.selectionBounds() else null
        // A rectangle has no equality of its own, so two rectangles with the
        // same edges would look like two different answers — and a flow that
        // publishes a change would then publish one every time it is asked.
        if (sameBounds(_selectionBounds.value, bounds)) return
        _selectionBounds.value = bounds
        _hasSelection.value = bounds != null
    }

    /**
     * Whether two rectangles cover the same area.
     *
     * [android.graphics.Rect] compares by identity, so the manager compares
     * the edges itself.
     */
    private fun sameBounds(first: android.graphics.Rect?, second: android.graphics.Rect?): Boolean {
        if (first == null || second == null) {
            return first == null && second == null
        }
        return first.left == second.left &&
            first.top == second.top &&
            first.right == second.right &&
            first.bottom == second.bottom
    }

    /**
     * Republishes the chrome's scroll state from the engine's own offset.
     *
     * The engine counts the rows the viewport sits *above* the live edge (0
     * at the live edge), while the chrome's rule counts them downwards from
     * it (0 at the live edge, negative in the history). One is the other
     * with the sign the vendored view uses, so the rule below is the rule
     * the Termux path has always used.
     */
    private fun publishEngineScroll(pane: EnginePane) {
        if (enginePanes[focusedPane.value] !== pane) return
        val topRow = engineTopRow(pane.scrollOffset())
        val atEdge = chromeIsAtLiveEdge(topRow, _isAtLiveEdge.value)
        if (atEdge != _isAtLiveEdge.value) _isAtLiveEdge.value = atEdge
        // "Has been touched" means "has left the first screen", the same as
        // the Termux path reads off its scroll callback.
        if (!atEdge) _hasScrolled.value = true
    }

    /**
     * Watches the focused engine pane and republishes what the chrome shows.
     *
     * The engine's view has no scroll callback — the vendored view has
     * `onScrollPositionChanged`, the engine's does not — so the offset is
     * read from the engine's own state instead: the engine reports how far
     * its viewport is from the live edge in the state array, and it rewrites
     * that number with every frame it draws. The selection is read the same
     * way, because the engine reports a selection change when the user picks
     * or clears one, but not while a finger drags the edge of it.
     *
     * A poll, then, and not a callback: the engine has no way to say "the
     * viewport moved", and a second opinion about where the viewport is
     * would be worse than reading the engine's. The proper fix is a
     * callback on the engine's view, the shape the vendored one has; until
     * then this reads the same fact, a few times a second, and only while an
     * engine pane has the focus.
     */
    private fun startEngineWatch(pane: EnginePane) {
        engineWatchJob?.cancel()
        engineWatchJob = managerScope.launch {
            while (isActive) {
                publishEngineScroll(pane)
                publishEngineSelection(pane, pane.hasSelection())
                delay(ENGINE_SCROLL_POLL_MILLIS)
            }
        }
    }

    fun unregisterPaneView(slot: PaneSlot) {
        val view = paneViews.remove(slot) ?: return
        // Drop the callbacks before dropping the reference, or the view keeps a
        // strong reference to this manager after the pane is gone.
        view.onScrollPositionChanged = null
        view.installSelectionMenu(enabled = false, listener = null)

        // A slot with an engine-backed pane keeps the focus it has: the pane
        // owns the slot, and the view that is going away was never the one
        // reporting for it.
        if (enginePanes.containsKey(slot)) return

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
            _isAtLiveEdge.value = true
            _hasScrolled.value = false
        }
        publishActiveId()
    }

    /**
     * The engine-backed pane of [slot], or null when the slot has none.
     *
     * The engine's pane owns its own session, so the manager does not track
     * one for it: the pane is the whole truth about that terminal.
     */
    fun enginePaneFor(slot: PaneSlot): EnginePane? = enginePanes[slot]

    /**
     * The engine-backed pane that has the focus, or null when a Termux pane
     * has it.
     */
    fun focusedEnginePane(): EnginePane? = enginePanes[focusedPane.value]

    /**
     * Where the input of the focused terminal goes.
     *
     * The focused pane may run on either terminal, and the two take bytes in
     * different ways: the vendored session takes a code point or a VT
     * string, the engine's session takes bytes. The writer is the seam — the
     * caller asks the manager which terminal has the focus and writes to the
     * writer it gets back, without knowing which terminal that is.
     *
     * This is the same question the chrome asks for the appearance source,
     * asked the same way: the manager knows what has the focus, and the
     * caller does not.
     *
     * Null when there is no terminal to write to: no pane has the focus, or
     * the pane that has it has no session.
     */
    fun focusedWriter(): TerminalWriter? {
        val engine = focusedEnginePane()
        if (engine != null) {
            return EngineTerminalWriter(engine)
        }
        val session = currentSession ?: return null
        return TermuxTerminalWriter(session)
    }

    /**
     * The app's resume: every engine pane's surface and frame pump come back.
     *
     * The engine's view draws through a GL surface, and a surface that is not
     * resumed draws nothing — so the app has to say when it comes back. The
     * manager is the app's one door to the terminal, so the door is here.
     */
    fun onHostResume() {
        enginePanes.values.forEach { pane -> pane.onHostResume() }
    }

    /**
     * The app's pause: every engine pane's surface sleeps and its frame pump
     * stops.
     */
    fun onHostPause() {
        enginePanes.values.forEach { pane -> pane.onHostPause() }
    }

    /**
     * Registers [pane] as the engine-backed pane of [slot].
     *
     * A pane that was registered for [slot] before is released: the engine's
     * pane owns its session, and nothing else would close it — a displaced
     * pane would keep a PTY alive for as long as the core lives. The Termux
     * path needs none of this, because there the session belongs to the
     * manager and not to the view.
     *
     * Registering the same pane again is a handover and releases nothing.
     *
     * From here the engine's pane owns the slot: the Termux view of that
     * slot stops reporting scroll and selection, because a pane that answers
     * twice is a pane with two opinions.
     */
    fun registerEnginePane(slot: PaneSlot, pane: EnginePane) {
        val previous = enginePanes.put(slot, pane)
        if (previous != null && previous !== pane) {
            previous.selectionListener = null
            previous.release()
        }
        paneViews[slot]?.let { view ->
            view.onScrollPositionChanged = null
            view.installSelectionMenu(enabled = false, listener = null)
        }
        // A pane that arrives at the slot that already has the focus is the
        // active terminal from that moment on, so it is activated the way a
        // focus change activates a pane: the selection and the scroll
        // position are published for it, and the shell is told that it has
        // the focus.
        //
        // Without this, a pane that registers for the focused slot would
        // never be activated: [focusEnginePane] treats a pane that already
        // has the focus as a pane that is already set up, and nothing else
        // would ever set it up.
        if (slot == focusedPane.value && pane.state == EnginePaneState.RUNNING) {
            activateEnginePane(pane)
            pane.notifyFocus(true)
            pane.focus()
        }
    }

    /**
     * Removes the engine-backed pane of [slot].
     *
     * The pane is **not** released: the host that created it owns it and
     * releases it when it is done — a pane that is only being moved to
     * another window comes back here a moment later, and killing its session
     * on the way would throw the session away with it.
     *
     * A pane that had the focus cannot keep it: the slot it was in has no
     * terminal behind it any more, so the focus moves to whatever is left on
     * screen.
     */
    fun unregisterEnginePane(slot: PaneSlot) {
        if (slot == focusedPane.value) {
            // Before the pane leaves the registry: this is what finds it and
            // tells the shell that the focus is gone.
            endEnginePaneFocus()
        }
        val pane = enginePanes.remove(slot) ?: return
        pane.selectionListener = null
        if (slot != focusedPane.value) return

        _selectionBounds.value = null
        _hasSelection.value = false
        val fallback = PaneSlot.entries.firstOrNull {
            it != slot && slotHasTerminal(it)
        }
        if (fallback != null) {
            _focusedPane.value = fallback
            val fallbackEngine = enginePanes[fallback]
            if (fallbackEngine != null) {
                activateEnginePane(fallbackEngine)
                fallbackEngine.notifyFocus(true)
                fallbackEngine.focus()
            } else {
                paneViews[fallback]?.let { activatePaneView(fallback, it) }
            }
        } else {
            // Nothing on screen left to be active.
            sessionClient.terminalView = null
            _isAtLiveEdge.value = true
            _hasScrolled.value = false
        }
        publishActiveId()
    }

    /**
     * Whether [slot] has a terminal behind it at all.
     *
     * Either kind counts: a slot with a running engine pane has one, and so
     * has a slot with a view that is attached to a session. A slot with
     * neither cannot be focused, and cannot be the fallback for a pane that
     * is going away.
     */
    private fun slotHasTerminal(slot: PaneSlot): Boolean {
        val engine = enginePanes[slot]
        if (engine != null) {
            return engine.state == EnginePaneState.RUNNING
        }
        return paneViews.containsKey(slot) && sessionForSlot(slot) != null
    }

    // --- the clipboard -----------------------------------------------------

    /**
     * Copies the focused pane's selection to the clipboard.
     *
     * The Termux path hands the text to the session client, which owns the
     * clipboard; the engine's pane writes the clipboard itself, because it
     * owns the selection too.
     *
     * False when there is nothing to copy.
     */
    fun copySelection(): Boolean {
        val engine = enginePanes[focusedPane.value]
        if (engine != null) {
            return engine.copySelection()
        }
        val view = paneViews[focusedPane.value] ?: return false
        val text = view.selectedText ?: return false
        view.mTermSession?.onCopyTextToClipboard(text)
        return true
    }

    /**
     * Pastes the clipboard into the focused pane.
     *
     * The Termux path asks the session to paste, which the session client
     * answers by handing the text to the emulator; the engine's pane writes
     * the text to its own session, bracketed when the shell asked for it.
     */
    fun pasteClipboard() {
        val engine = enginePanes[focusedPane.value]
        if (engine != null) {
            engine.pasteClipboard()
            return
        }
        paneViews[focusedPane.value]?.pasteFromClipboard()
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
        onBlockSessionChanged(persistentId, irisSession.terminalSession)
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
     * The live session behind a persistent id, or null when there is none.
     *
     * The reverse of [sessionIdForSlot], and the only way to reach a session that
     * is not currently in a pane: the positional index is what identifies a tab
     * here, and translating it back needs the same private list the other
     * direction starts from.
     *
     * Added for the session-log attachment, which is offered for any live session
     * rather than the one on screen — the whole point of it is reaching the output
     * of a session the user is not currently looking at.
     */
    fun sessionForId(persistentId: String): TerminalSession? {
        val index = getIndexForId(persistentId)
        if (index < 0) return null
        return irisSessions.getOrNull(index)?.terminalSession
    }

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
        onBlockSessionChanged(persistentId, irisSession.terminalSession)
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
     *
     * Null for an engine-backed pane: the engine's session is not a row in
     * the app's store, so there is no id to report, and reporting the id of
     * a session that is on no screen would be worse than reporting nothing.
     */
    fun activePersistentId(): String? {
        val slot = focusedPane.value
        if (enginePanes.containsKey(slot)) return null
        return sessionIdForSlot(slot)
    }

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
        if (blockEngineEnabled) blockEngineWire?.reanchor(replacement)
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
        onBlockSessionChanged(target.persistentId, target.terminalSession)
        paneViews[slot]?.attachSession(target.terminalSession)
        publishAltBufferState()
        publishActiveId()
    }

    /**
     * The command an interactive guest session runs, or null when the guest
     * is not installed.
     *
     * The one place a guest session's exec image is built, and the answer both
     * terminals use: the vendored path runs it on a Java PTY, and the engine's
     * path runs the very same image through [EngineLaunch]. One command, two
     * terminals — a guest that behaved differently per terminal would be a
     * guest nobody could debug.
     *
     * Null rather than a fallback shell when the guest is not installed: the
     * fallback below belongs to the vendored path, which starts a session
     * before the rootfs is there, and the engine has nothing to run without
     * one.
     */
    fun guestCommand(): GuestCommand? {
        if (!ubuntuBootstrap.isInstalled) return null

        ensureShellRc()

        val guestWd = if (projectPath != null) {
            "/sdcard/dev.drosh/${File(projectPath!!).name}"
        } else null

        val shell = effectiveShellPath()
        val integration = DroshShellIntegration.install(appContext, shell, shellLocale)

        return guestLauncher().build(
            GuestSpec(
                workingDirectory = guestWd,
                shell = shell,
                command = prootStartCommand,
                environment = integration.environment,
                shellArgs = integration.shellArgs,
            ),
        )
    }

    /**
     * The plain shell, for when there is no guest to run.
     *
     * The vendored path opens this when the rootfs is not installed, so a user
     * who has not run the setup wizard still gets a terminal. The engine's path
     * must do the same: without it, choosing the engine on a fresh install
     * shows an empty rectangle where the terminal should be, which reads as
     * "the engine is broken" rather than "there is no guest yet".
     */
    fun plainShellCommand(): GuestCommand = GuestCommand(
        executable = PLAIN_SHELL,
        argv = listOf(PLAIN_SHELL),
        cwd = "/",
        environment = listOf(
            "PATH=/system/bin:/system/xbin",
            "HOME=/",
            "TERM=vt100",
        ),
    )

    private fun createNewSession(): TerminalSession {
        // A guest engine whose runtime is not in the APK cannot run the guest,
        // and the session would die with an `ENOENT` that says nothing about
        // the cause. The engine's runtime is fetched by the build, so a missing
        // one is a build that skipped the fetch; the user is told which, rather
        // than watching a shell fail to open.
        if (ubuntuBootstrap.isInstalled && !guestLauncher().isAvailable) {
            reportMissingGuestRuntime()
            return plainShellSession()
        }

        val cmd = guestCommand()
        if (cmd != null) {
            return TerminalSession(
                cmd.executable,
                cmd.cwd,
                cmd.argv.toTypedArray(),
                cmd.environment.toTypedArray(),
                3000,
                sessionClient
            )
        }

        return plainShellSession()
    }

    /**
     * The shell the app falls back to when there is no guest to run.
     *
     * A plain Android shell, with no rootfs and no isolation: it is enough to
     * open a session at all, and it is what the app has always used before the
     * guest was installed.
     */
    private fun plainShellSession(): TerminalSession {
        val command = plainShellCommand()
        return TerminalSession(
            command.executable,
            command.cwd,
            command.argv.toTypedArray(),
            command.environment.toTypedArray(),
            3000,
            sessionClient
        )
    }

    /**
     * Tells the user that the guest engine they chose cannot run here.
     *
     * The runtime is fetched by the build, so a missing one means the build
     * skipped the fetch — a plain Android shell opens in the meantime, and the
     * reason is shown rather than left in a log.
     */
    private fun reportMissingGuestRuntime() {
        val engine = when (guestIsolation) {
            GuestIsolation.PROOT -> "proot"
            GuestIsolation.CHROOT_NG -> "chroot-ng"
        }
        Log.e(TAG, "$engine is not in this build: the guest runtime is missing from the app's native library directory")
        _processExitEvent.value = ProcessExitEvent(EXIT_RUNTIME_MISSING, engine)
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
        // The engine's panes stop watching and stop reporting: a pane that
        // is left registered keeps this manager alive through its selection
        // listener after the screens that built it are gone.
        engineWatchJob?.cancel()
        engineWatchJob = null
        enginePanes.values.forEach { pane -> pane.selectionListener = null }
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

        val cmd = guestLauncher().buildCommand(
            GuestSpec(workingDirectory = guestWd, shell = effectiveShellPath()),
            command,
        )

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
        /** The log tag the terminal's own messages go out under. */
        const val TAG = "DroshTerminal"

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
         * The shell the app falls back to when there is no guest to run.
         *
         * A plain Android shell, with no rootfs and no isolation: enough to
         * open a session at all, and what the app has always used before the
         * guest was installed.
         */
        const val PLAIN_SHELL = "/system/bin/sh"

        /**
         * The exit code the app reports when the guest runtime is missing.
         *
         * Not a real exit code: nothing exited. It is a number the dialog can
         * show, and it is distinct from the codes a shell can return, so a
         * user who reads it knows the session never started.
         */
        const val EXIT_RUNTIME_MISSING = 127

        /**
         * Blinker rate assumed before settings have been read.
         *
         * A view registered in that window would otherwise keep the emulator's
         * own default for the rest of its life, because the settings flow has
         * already emitted and will not emit again. The same value the
         * repository defaults to, so a pane opened before the first settings
         * emission blinks at the rate the user would have chosen anyway.
         */
        const val DEFAULT_CURSOR_BLINK_MS = 500

        /** Matches [TerminalSettings.palette.background]'s default. */
        const val DEFAULT_TERMINAL_BG_COLOR = 0xFF0E0E0E.toInt()

        /**
         * The rates the vendored view accepts, mirroring its own
         * [TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN] /
         * [TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX]. Everything outside
         * this — 0 included — reads as "blinker off".
         */
        val VALID_BLINK_RANGE = 100..2000

        /**
         * How often the focused engine pane's viewport is read, in
         * milliseconds.
         *
         * The engine's view cannot say "the viewport moved", so the offset is
         * polled instead. The chrome's rule has a dead zone of a few rows, so
         * the answer only changes when the viewport crosses a threshold —
         * a tenth of a second is far more often than that happens, and it is
         * the same order as the blink clock the manager already runs.
         */
        const val ENGINE_SCROLL_POLL_MILLIS = 100L
    }
}

/**
 * How far the viewport must move past the live edge before the chrome collapses.
 *
 * In rows, not pixels: the emulator scrolls a row at a time, and a row is the
 * smallest thing the user can actually look at.
 */
private const val CHROME_COLLAPSE_ROWS = -5
/** How close to the live edge the viewport has to come for the chrome to return. */
private const val CHROME_EXPAND_ROWS = -1

/**
 * The chrome's row for an engine viewport offset.
 *
 * The engine counts the rows the viewport sits *above* the live edge — 0 at
 * the live edge, positive in the scrollback — while the chrome's rule counts
 * them downwards from it: 0 at the live edge, negative in the history. One is
 * the other with the sign the vendored view uses, so the rule the Termux
 * path has always used applies to the engine's number unchanged.
 *
 * @param displayOffset the engine's own offset, in rows.
 */
internal fun engineTopRow(displayOffset: Int): Int = -displayOffset

/**
 * Whether the viewport counts as "at the live edge" for the top chrome.
 *
 * Two thresholds and a dead zone between them, which is the whole point.
 *
 * The chrome is not a decoration that follows the viewport exactly — it *is* the
 * system status bar, which Android animates on its own schedule. Testing
 * `topRow == 0` meant a fling that brushed the boundary changed the answer on
 * every single row it crossed, so the status bar began and ended its own
 * animation several times during one gesture and the whole top of the screen went
 * back and forth. Two things had to be true of a scroll for it to look
 * deliberate: you have to have actually gone somewhere, and you have to have
 * actually come back.
 *
 * `wasAtLiveEdge` is kept inside the dead zone rather than resolved from `topRow`
 * alone, which is what makes it hysteresis rather than just a wider band — a
 * value in the middle is ambiguous, and the previous answer is the least
 * surprising thing to do with an ambiguous one.
 *
 * @param topRow the terminal's first visible row; 0 is the live edge, negative is
 *   the scrollback above it.
 * @param wasAtLiveEdge what the chrome is currently showing.
 */
internal fun chromeIsAtLiveEdge(topRow: Int, wasAtLiveEdge: Boolean): Boolean = when {
    // Deep enough into the scrollback to be reading history rather than nudging
    // the viewport by a line.
    topRow <= CHROME_COLLAPSE_ROWS -> false
    // Back at the live edge, or the row above it.
    topRow >= CHROME_EXPAND_ROWS -> true
    // Between the two: hold still.
    else -> wasAtLiveEdge
}

/**
 * Whether the top chrome is collapsed: the system status bar hidden and the pill
 * row flush with the top of the screen, in the space it vacated.
 *
 * The whole rule.
 *
 *  - **Up** — the viewport is in the scrollback, a session nobody has scrolled
 *    yet, or a TUI owns the terminal. The app goes fullscreen, the pill row moves
 *    up into the band the status bar vacated, and the grid's top padding shrinks to
 *    that band.
 *  - **At the live edge** — the prompt, once the user has actually scrolled and
 *    come back. The status bar is back and the row sits below it at
 *    `statusBarH + 10dp`, floating over the output.
 *
 * **A session that has never been scrolled opens up.** It is not at the live edge
 * in any sense the user can observe -- there is nothing to have come back to. So
 * `hasScrolled` is its own piece of state, and a freshly opened terminal, or one
 * returned to by switching sessions, starts fullscreen.
 *  - **A TUI** (nano, vim, htop) draws fullscreen and has no scrollback, so it is
 *    always collapsed. Stated rather than left implicit because it is the case
 *    where a clock over somebody else's fullscreen interface is most in the way.
 *
 * Dead zone of zero was tried first and is not what this is: one row of movement
 * is one transition, and `mTopRow` is an integer, so there is no half state to
 * resolve. The dead zone is hysteresis — it holds the previous answer between the
 * two thresholds, which is what stops a slow drag from moving the bar several
 * times.
 *
 * @param topRow the focused terminal's first visible row.
 * @param wasAtLiveEdge the hysteresis input [chromeIsAtLiveEdge] resolves from.
 * @param tuiActive the focused pane is showing the alternate screen buffer.
 * @param hasScrolled the user has moved this terminal's viewport at all. False
 *   opens a session collapsed, which is the state a new session is meant to be in.
 */
fun chromeCollapsed(
    topRow: Int,
    wasAtLiveEdge: Boolean,
    tuiActive: Boolean,
    hasScrolled: Boolean,
): Boolean = tuiActive || !hasScrolled || !chromeIsAtLiveEdge(topRow, wasAtLiveEdge)

/**
 * Whether the top chrome is collapsed, from the answer the manager already has.
 *
 * The overload above needs the raw row because that row is what applies the
 * dead zone. The manager applies [chromeIsAtLiveEdge] itself on every scroll and
 * publishes the outcome as `isAtLiveEdge`, which is the same value by
 * construction: the function is idempotent, so re-deriving it from the row it
 * was derived from cannot disagree.
 *
 * Reading that row in the UI is what this exists to avoid. It changes once per
 * row of a fling, and a screen that reads it recomposes per row — both panes,
 * the key bar, the top bar — to arrive at a boolean it already had.
 */
fun chromeCollapsed(
    atLiveEdge: Boolean,
    tuiActive: Boolean,
    hasScrolled: Boolean,
): Boolean = tuiActive || !hasScrolled || !atLiveEdge


/** "#RRGGBB" as a colour, or null when it is not one. */
private fun parseHexColor(hex: String): Int? {
    val body = hex.removePrefix("#")
    if (body.length != 6) return null
    val value = body.toIntOrNull(16) ?: return null
    return 0xFF000000.toInt() or value
}
