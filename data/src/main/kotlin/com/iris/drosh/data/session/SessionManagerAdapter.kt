package com.iris.irisshell.data.session

import com.iris.irisshell.domain.session.SessionSnapshot
import com.iris.irisshell.domain.session.SessionState
import com.iris.irisshell.terminal.SessionLifecycleCallbacks
import com.iris.irisshell.terminal.TerminalManager
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
 * Adapted for Drosh — com.iris.irisshell
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
    @com.iris.irisshell.data.di.ApplicationScope private val appScope: CoroutineScope,
) : SessionLifecycleCallbacks {

    private var reconcileJob: Job? = null
    private var activeJob: Job? = null
    private var tickerJob: Job? = null

    private var lastNames: Map<String, String> = emptyMap()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeIdFlow: StateFlow<String?> = _activeId.asStateFlow()

    fun start() {
        stop()

        // Reset exit signal — covers fresh process launch where shouldExit
        // might have been true from a previous run that was terminated.
        appScope.launch { sessionRepository.setShouldExit(false) }

        terminalManager.lifecycleCallbacks = this

        reconcileJob = appScope.launch {
            sessionRepository.observeAll().collectLatest { snapshots ->
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
                // Live snapshot capture is deferred until TerminalBuffer API is stable.
                // TODO: Implement captureLiveSnapshot() using emulator.getScreen()
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

            val notLive = currentIds - liveIds
            notLive.forEach { id ->
                val snapshot = snapshots.firstOrNull { it.id == id }
                if (snapshot?.state != SessionState.Closed) {
                    terminalManager.addTabWithId(id, snapshot?.name ?: "")
                    sessionRepository.updateState(id, SessionState.Running)
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

        // If Room has no sessions at all, ensure a default exists.
        // This runs on every observeAll() emission, covering both initial
        // startup and relaunch after app exit (process may be reused,
        // start() not called again). create() resets shouldExit to false.
        if (currentIds.isEmpty()) {
            val defaultId = sessionRepository.create("Default")
            sessionRepository.setActiveId(defaultId)
        }
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
     * Called by [TerminalManager] when the last live PTY session exits or is
     * explicitly closed and [irisSessions] becomes empty.
     *
     * Following Termux's pattern (TermuxService.updateNotification →
     * requestStopService), we signal the UI layer to exit rather than
     * auto-creating a replacement. The UI observes [SessionRepository.shouldExit]
     * and finishes the Activity when it becomes true.
     */
    override fun onLastSessionExited() {
        appScope.launch {
            sessionRepository.setShouldExit(true)
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
