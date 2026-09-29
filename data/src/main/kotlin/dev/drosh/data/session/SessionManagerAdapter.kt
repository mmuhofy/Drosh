package dev.drosh.data.session

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
     * Called when the shell pid is assigned. Currently a no-op — Room's
     * schema does not yet store pid. Reserved for future Process Cinema
     * (§15) and session monitoring features.
     */
    override fun onSessionPidChanged(persistentId: String?, pid: Int) {
    }

    private companion object {
        const val SNAPSHOT_TICK_MS = 500L
        const val DEFAULT_SESSION_NAME = "Default"
    }
}
