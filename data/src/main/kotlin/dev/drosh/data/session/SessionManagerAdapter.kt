package dev.drosh.data.session

import com.termux.terminal.Logger
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.terminal.SessionLifecycleCallbacks
import dev.drosh.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the persistent session graph (Room) and the runtime PTY session
 * manager ([TerminalManager]). Implements [SessionLifecycleCallbacks] so
 * that when a PTY process exits, Room state is updated — closing the loop
 * that was missing in the prior implementation.
 *
 * Ported from: mmuhofy/IrisCode — data/SessionManagerAdapter.kt
 * Adapted for Drosh — dev.drosh
 *
 * Inspired by Termux's service-level session management pattern
 * (github.com/termux/termux-app, TermuxService.kt + TermuxShellManager.kt),
 * where the service owns the session list and callbacks flow back to keep
 * UI state in sync. When the last session exits or is deleted, the service
 * calls requestStopService() to exit — Drosh mirrors this by setting
 * shouldExit so the UI can finish the Activity.
 */
@Singleton
class SessionManagerAdapter @Inject constructor(
    private val sessionRepository: SessionRepositoryImpl,
    private val terminalManager: TerminalManager,
    @dev.drosh.data.di.ApplicationScope private val appScope: CoroutineScope,
) : SessionLifecycleCallbacks {

    private var reconcileJob: Job? = null
    private var activeJob: Job? = null
    private var tickerJob: Job? = null

    private var lastNames: Map<String, String> = emptyMap()

    /**
     * Guards the one-off session revival in [reconcile]. Without it every
     * reconcile tick with no live session would resurrect one, which is the
     * silent replacement the exit dialog exists to avoid.
     */
    @Volatile
    private var sessionsEstablished: Boolean = false

    /**
     * Set once the persisted active id has been applied to the terminal
     * manager. Until a session by that name is live the switch is a no-op, and
     * the stored value would otherwise be lost for the rest of the process.
     */
    @Volatile
    private var hasAppliedStoredActive: Boolean = false

    private val _activeId = MutableStateFlow<String?>(null)
    val activeIdFlow: StateFlow<String?> = _activeId.asStateFlow()

    fun start() {
        stop()

        // Reset exit signal — covers fresh process launch where shouldExit
        // might have been true from a previous run that was terminated.
        appScope.launch { sessionRepository.setShouldExit(false) }

        terminalManager.lifecycleCallbacks = this
        // TerminalManager owns which session is active; persist whatever it
        // says. Nothing writes the active id back into the manager, so the
        // stored value cannot drift from the live one — which is what made the
        // sidebar highlight a different session than the one on screen.
        terminalManager.onActiveSessionChanged = { id ->
            appScope.launch { sessionRepository.setActiveId(id) }
        }

        reconcileJob = appScope.launch {
            // collect, not collectLatest: reconcile suspends inside its loops
            // (every state update is a Room write), and closing a session writes
            // to the database, which emits again. With collectLatest the new
            // emission cancelled the in-flight pass, abandoning the rest of the
            // sessions it was in the middle of closing — the terminal kept a PTY
            // the sidebar no longer knew about.
            sessionRepository.observeAll().collect { snapshots ->
                reconcile(snapshots)
            }
        }

        activeJob = appScope.launch {
            sessionRepository.observeActiveId().collectLatest { id ->
                _activeId.value = id
                if (id != null) {
                    withContext(Dispatchers.Main.immediate) {
                        terminalManager.switchSessionById(id)
                    }
                }
            }
        }

        tickerJob = appScope.launch {
            while (true) {
                delay(SNAPSHOT_TICK_MS)
                ensureSessionExists()
                // Live snapshot capture is deferred until TerminalBuffer API is stable.
                // TODO: Implement captureLiveSnapshot() using emulator.getScreen()
            }
        }
    }

    /**
     * Launch recovery, retried until a session is actually alive.
     *
     * [reconcile] only runs when Room emits, so it cannot fix a launch that came
     * up empty: nothing in the database changes afterwards, so no further
     * emission ever arrives and the screen stays black. The ticker drives this
     * instead, so a first attempt that lands before the PTY layer is ready is
     * simply followed by another.
     *
     * It stops the moment any session has been live. From then on the user is in
     * charge, so closing every session must not silently conjure a replacement —
     * the exit dialog owns that decision.
     */
    private suspend fun ensureSessionExists() {
        // Cheap gate first. The flags are volatile because this runs on
        // @ApplicationScope, which is Dispatchers.Default.
        if (sessionsEstablished) return

        val snapshots = sessionRepository.observeAll().first()

        // Everything below touches TerminalManager's session list, its
        // id-to-index map and these flags. All of that now happens on the main
        // thread only, which is the invariant the manager relies on and never
        // states in its types. The scope this runs in is a thread pool.
        withContext(Dispatchers.Main.immediate) {
            if (sessionsEstablished) return@withContext
            if (terminalManager.liveSessionIds().isNotEmpty()) {
                sessionsEstablished = true
                return@withContext
            }

            // Only a session that was left open is worth bringing back. When
            // every row is Closed the user closed them on purpose, so a launch
            // starts a fresh default rather than resurrecting one of them.
            val resumable = snapshots.firstOrNull { it.state != SessionState.Closed }

            // A failing spawn must not kill the ticker, or recovery stops for good.
            runCatching {
                if (resumable != null) {
                    sessionRepository.updateState(resumable.id, SessionState.Running)
                    terminalManager.addTabWithId(resumable.id, resumable.name)
                } else {
                    val defaultId = sessionRepository.create(DEFAULT_SESSION_NAME)
                    sessionRepository.setActiveId(defaultId)
                }
            }.onFailure { error ->
                Logger.logWarn(null, "Drosh", "Session recovery attempt failed: ${error.message}")
            }
        }
    }

    fun stop() {
        terminalManager.lifecycleCallbacks = null
        reconcileJob?.cancel()
        activeJob?.cancel()
        tickerJob?.cancel()
        reconcileJob = null
        activeJob = null
        tickerJob = null
    }

    /**
     * Reconciles the persistent Room state with the live terminal sessions.
     *
     * Compares Room's session list against [TerminalManager.liveSessionIds] —
     * the actual set of sessions currently in the PTY layer.
     *  - Sessions restored from Closed → Idle get spawned.
     *  - Sessions that exited (Closed in Room, removed from PTY) are not re-spawned.
     *  - Sessions deleted from Room are closed in the terminal via [closeTab].
     *
     * Inspired by Termux's reconcile in TermuxService, which diffs the
     * live session list against the desired state.
     */
    private suspend fun reconcile(snapshots: List<SessionSnapshot>) {
        val currentIds = snapshots.map { it.id }.toSet()
        val currentNames = snapshots.associate { it.id to it.name }

        withContext(Dispatchers.Main.immediate) {
            val liveIds = terminalManager.liveSessionIds()

            if (liveIds.isNotEmpty()) sessionsEstablished = true

            val notLive = currentIds - liveIds
            notLive.forEach { id ->
                val snapshot = snapshots.firstOrNull { it.id == id }
                if (snapshot?.state != SessionState.Closed) {
                    terminalManager.addTabWithId(id, snapshot?.name ?: "")
                    sessionRepository.updateState(id, SessionState.Running)
                }
            }

            // The stored active id is applied once the session it names is
            // actually live. Applying it earlier is a silent no-op, and nothing
            // re-emits afterwards, so a relaunch would always land on tab 0
            // instead of the session the user left on.
            if (!hasAppliedStoredActive) {
                val storedId = _activeId.value
                if (storedId == null || storedId in terminalManager.liveSessionIds()) {
                    // Nothing to restore, or it is finally live. Either way the
                    // restore is settled and there is no reason to look again.
                    hasAppliedStoredActive = true
                    if (storedId != null) terminalManager.switchSessionById(storedId)
                }
            }

            lastNames.forEach { (id, oldName) ->
                val newName = currentNames[id]
                if (newName != null && newName != oldName && id in liveIds) {
                    val idx = terminalManager.getIndexForId(id)
                    if (idx >= 0) terminalManager.renameTab(idx, newName)
                }
            }

            val stale = liveIds - currentIds
            stale.forEach { id ->
                val idx = terminalManager.getIndexForId(id)
                if (idx >= 0) {
                    terminalManager.closeTab(idx)
                    sessionRepository.updateState(id, SessionState.Closed)
                }
            }

        }

        lastNames = currentNames
    }

    /**
     * Called by [TerminalManager] when a PTY process exits — either naturally
     * or after a kill signal. Updates Room state to Closed so the session
     * system stays consistent and [reconcile] won't try to re-spawn it.
     */
    override fun onSessionFinished(persistentId: String?, exitCode: Int) {
        if (persistentId == null) return
        appScope.launch {
            sessionRepository.updateState(persistentId, SessionState.Closed)
        }
    }

    /**
     * Called when the shell pid is assigned. Currently a no-op — Room's
     * schema does not yet store pid. Reserved for future Process Cinema
     * (§15) and session monitoring features.
     */
    override fun onSessionPidChanged(persistentId: String?, pid: Int) {
    }

    private companion object {
        const val SNAPSHOT_TICK_MS = 500L
    }
}
