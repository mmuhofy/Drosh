package dev.drosh.terminal

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import dev.drosh.core.TerminalConstants
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
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
) {
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
        // Command marks are per session, so the flow follows the active tab.
        commandState.bind(irisSessions.getOrNull(_activeTabIndex.value)?.terminalSession)
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
        val session = irisSessions.getOrNull(_activeTabIndex.value)?.terminalSession ?: return null
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
    val activeTabIndex: StateFlow<Int> = _activeTabIndex.asStateFlow()

    private val _altBufferActive = MutableStateFlow(false)
    val altBufferActive: StateFlow<Boolean> = _altBufferActive.asStateFlow()

    /**
     * Synchronous snapshot of the active tab index, intended for UI scaffolds
     * (e.g. the topbar's "1 / N" indicator) that do not need a Flow<T>.
     */
    fun getActiveTabIndexSnapshot(): Int = _activeTabIndex.value

    val tabCount: Int get() = irisSessions.size

    val currentSession: TerminalSession?
        get() = irisSessions.getOrNull(_activeTabIndex.value)?.terminalSession

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

    private var terminalViewRef: TerminalView? = null

    private val _scrollTopRow = MutableStateFlow(0)

    /**
     * The terminal's first visible transcript row. 0 is the live edge, where
     * the prompt is; negative means the viewport has been scrolled back into
     * the scrollback.
     *
     * Fed by [TerminalView.onScrollPositionChanged], which fires on touch,
     * fling, wheel and keyboard scrolling, and again when new output snaps the
     * viewport back to the live edge. The UI uses it to collapse the top bar
     * while the user is reading history.
     */
    val scrollTopRow: StateFlow<Int> = _scrollTopRow.asStateFlow()

    private val _isAtLiveEdge = MutableStateFlow(true)

    /**
     * True while the viewport is at the live edge. This, not [scrollTopRow], is
     * what the UI collects.
     *
     * scrollTopRow changes once per row, and a fling through 30 rows emitted 30
     * updates, each one recomposing the whole terminal screen on the frame it
     * landed. The only thing the UI actually needs is which side of the live
     * edge it is on, so this flips at the boundary and is silent the rest of
     * the time. That is the difference between the bar gliding and the bar
     * stuttering along with your thumb.
     */
    val isAtLiveEdge: StateFlow<Boolean> = _isAtLiveEdge.asStateFlow()

    private val prootRunner: ProotRunner by lazy {
        ProotRunner(ubuntuBootstrap, application.applicationInfo.nativeLibraryDir)
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
                terminalViewRef?.attachSession(irisSessions[idx].terminalSession)
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
            terminalViewRef?.onScreenUpdated()
            commandActivity.onOutput()
            // Recomputed at most a few times per command; the screen has to
            // have changed for it to be worth asking.
            _currentAmbientTint = recomputeAmbientTint()
            val persistentId = getIndexOfSession(session)
                .takeIf { it >= 0 }
                ?.let { irisSessions[it].persistentId }
            blockEngineWire?.onSessionTextChanged(session, persistentId)
        }
        sessionClient.onAltBufferChanged = { isActive ->
            _altBufferActive.value = isActive
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
                terminalViewRef?.mEmulator?.let { emulator ->
                    emulator.setCursorStyle()
                    terminalViewRef?.invalidate()
                }
            }
            .launchIn(managerScope)

        settingsRepository.cursorBlinkRateMs
            .onEach { rate ->
                terminalViewRef?.setTerminalCursorBlinkerRate(rate)
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
     * Called from registerTerminalView rather than from a composable effect:
     * a LaunchedEffect on first composition runs before the AndroidView factory
     * has registered the view, so it returned early and the platform
     * ActionMode stayed on. Binding it where the view is actually attached
     * cannot miss.
     */
    private fun bindSelectionMenu(view: TerminalView) {
        view.installSelectionMenu(enabled = false) {
            // The highlight is painted inside TerminalView.onDraw, so the view
            // has to be invalidated for a selection change to be visible. The
            // handles reposition themselves, which is why select-all appeared
            // to do nothing: nothing moved the pixel content.
            view.invalidate()
            val bounds = view.selectionBounds()
            _selectionBounds.value = bounds
            _hasSelection.value = bounds != null
        }
        view.notifySelectionChanged()
    }

    fun registerTerminalView(view: TerminalView, context: Context) {
        terminalViewRef = view
        sessionClient.clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        sessionClient.terminalView = view
        // Report the current position immediately: a session restored straight
        // into the middle of its scrollback would otherwise start with a stale
        // zero and only correct itself on the next scroll.
        _scrollTopRow.value = view.mTopRow
        view.onScrollPositionChanged = { topRow ->
            _scrollTopRow.value = topRow
            val atEdge = topRow == 0
            if (atEdge != _isAtLiveEdge.value) _isAtLiveEdge.value = atEdge
        }
        bindSelectionMenu(view)
    }

    fun unregisterTerminalView() {
        // Drop the callback before the reference, or the view keeps a strong
        // reference to this manager after the screen is gone.
        terminalViewRef?.onScrollPositionChanged = null
        terminalViewRef = null
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
        _activeTabIndex.value = newIndex
        _sessionCount.value = irisSessions.size
        // A session exists again, so the exit dialog no longer applies.
        _noSessionsLeft.value = false
        // Block mode shares these sessions; point the block store at the new one.
        blockEngineWire?.onSessionChanged(persistentId, irisSession.terminalSession)
        terminalViewRef?.attachSession(irisSession.terminalSession)
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

    /** Currently-active session's persistent id, or null if unknown. */
    fun activePersistentId(): String? =
        irisSessions.getOrNull(_activeTabIndex.value)?.persistentId

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

        if (_activeTabIndex.value == from) {
            _activeTabIndex.value = to
        } else {
            val moved = if (from < to) -1 else 1
            if (_activeTabIndex.value in (minOf(from, to) + 1) until maxOf(from, to) + 1) {
                _activeTabIndex.value += moved
            }
        }
    }

    fun restartCurrentTab() {
        val index = _activeTabIndex.value
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
        terminalViewRef?.attachSession(replacement)
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
        _activeTabIndex.value = 0
        _sessionCount.value = 0
        _altBufferActive.value = false
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

        when {
            index < _activeTabIndex.value -> _activeTabIndex.value--
            index == _activeTabIndex.value && _activeTabIndex.value >= irisSessions.size ->
                _activeTabIndex.value = (irisSessions.size - 1).coerceAtLeast(0)
        }

        terminalViewRef?.let { view ->
            currentSession?.let { view.attachSession(it) }
        }

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

    fun switchTab(index: Int) {
        if (index < 0 || index >= irisSessions.size || index == _activeTabIndex.value) return
        _activeTabIndex.value = index
        // Blocks are stored per session, so switching shows that session's
        // history instead of clearing the engine.
        val target = irisSessions[index]
        blockEngineWire?.onSessionChanged(target.persistentId, target.terminalSession)
        currentSession?.let { terminalViewRef?.attachSession(it) }
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
        when {
            idx < _activeTabIndex.value -> _activeTabIndex.value--
            idx == _activeTabIndex.value && _activeTabIndex.value >= irisSessions.size ->
                _activeTabIndex.value = (irisSessions.size - 1).coerceAtLeast(0)
        }

        terminalViewRef?.let { view ->
            currentSession?.let { view.attachSession(it) }
        }

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
        irisSessions.forEach { it.terminalSession.finishIfRunning() }
        irisSessions.clear()
        idToIndex.clear()
    }

    suspend fun executeCommand(
        command: String,
        timeoutSec: Long = 30L,
        onOutput: (String) -> Unit = {}
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
}
