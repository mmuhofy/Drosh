package dev.drosh.terminal

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import dev.drosh.domain.agent.ToolResult
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

    private var terminalViewRef: TerminalView? = null

    private val prootRunner: ProotRunner by lazy {
        ProotRunner(ubuntuBootstrap, application.applicationInfo.nativeLibraryDir)
    }

    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var prootStartCommand: String = ""

    var projectPath: String? = null

    var shellPath: String = "/bin/zsh"

    init {
        sessionClient.onSessionFinished = { session -> onSessionFinished(session) }
        sessionClient.onTextChanged = { session ->
            terminalViewRef?.onScreenUpdated()
            blockEngineWire?.onSessionTextChanged(session)
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
    }

    fun updateProotStartCommand(command: String) {
        prootStartCommand = command
    }

    fun registerTerminalView(view: TerminalView, context: Context) {
        terminalViewRef = view
        sessionClient.clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        sessionClient.terminalView = view
    }

    fun unregisterTerminalView() {
        terminalViewRef = null
    }

    fun addTab(): TerminalSession = addTabWithId(null, "")

    fun addTabWithId(persistentId: String?, name: String): TerminalSession {
        val irisSession = DroshSession(
            terminalSession = createNewSession(),
            persistentId = persistentId,
            name = name,
        )
        irisSessions.add(irisSession)
        val newIndex = irisSessions.size - 1
        if (persistentId != null) {
            idToIndex[persistentId] = newIndex
        }
        _activeTabIndex.value = newIndex
        _sessionCount.value = irisSessions.size
        terminalViewRef?.attachSession(irisSession.terminalSession)
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
    fun liveSessionIds(): Set<String> =
        irisSessions.mapNotNull { it.persistentId }.toSet()

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
            if (id != null) idToIndex[id] = idx
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

    fun closeTab(index: Int) {
        if (index !in irisSessions.indices) return
        val irisSession = irisSessions[index]
        val persistentId = irisSession.persistentId
        irisSession.terminalSession.finishIfRunning()
        irisSessions.removeAt(index)
        _sessionCount.value = irisSessions.size

        if (persistentId != null) idToIndex.remove(persistentId)

        for (i in index until irisSessions.size) {
            val id = irisSessions[i].persistentId
            if (id != null) idToIndex[id] = i
        }

        when {
            index < _activeTabIndex.value -> _activeTabIndex.value--
            index == _activeTabIndex.value && _activeTabIndex.value >= irisSessions.size ->
                _activeTabIndex.value = (irisSessions.size - 1).coerceAtLeast(0)
        }

        terminalViewRef?.let { view ->
            currentSession?.let { view.attachSession(it) }
        }

        if (irisSessions.isEmpty()) {
            lifecycleCallbacks?.onSessionFinished(persistentId, -1)
            lifecycleCallbacks?.onLastSessionExited()
        }
    }

    fun switchTab(index: Int) {
        if (index < 0 || index >= irisSessions.size || index == _activeTabIndex.value) return
        _activeTabIndex.value = index
        // Block engine state is per-session; reset so the next snapshot
        // is anchored against the new buffer.
        blockEngineWire?.reset()
        currentSession?.let { terminalViewRef?.attachSession(it) }
    }

    fun createSession(): TerminalSession {
        if (irisSessions.isEmpty()) {
            return addTab()
        }
        return irisSessions[_activeTabIndex.value].terminalSession
    }

    private fun createNewSession(): TerminalSession {
        if (ubuntuBootstrap.isInstalled) {
            ensureShellRc()

            val guestWd = if (projectPath != null) {
                "/sdcard/dev.drosh/${File(projectPath!!).name}"
            } else null

            val cmd = prootRunner.build(
            guestWd,
            shell = shellPath,
            startCommand = prootStartCommand,
            environmentHooks = writeShellHooksFile()
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

    private fun writeShellHooksFile(): Map<String, String> {
        val d = "${'$'}"
        val hooksFile = File(appContext.filesDir, "drosh_hooks.zsh")

        // Pre-create completion file to prevent race condition where
        // precmd fires before file exists (causes "no such file" error)
        val completionFile = File(appContext.filesDir, "drosh_cmd_complete")
        appContext.filesDir.mkdirs()
        if (!completionFile.exists()) completionFile.createNewFile()

        val completionPath = completionFile.absolutePath

        val hooksContent = """
            # ── Command completion tracking (ENV injection) ───────────────────────
            # preexec/precmd hooks write "command|elapsed_sec|exit_code" to a
            # file the foreground service monitors. Injected via ${d}ENV variable
            # so user's .zshrc is never modified. Path is app-controlled.
            local __drosh_cf="${completionPath}"
            __drosh_cmd=""
            __drosh_start=0

            preexec() {
              __drosh_cmd="${d}1"
              __drosh_start=${d}(date +%s)
            }

            precmd() {
              local __drosh_code=${d}?
              if [[ -n "${d}__drosh_cmd" && ${d}__drosh_start -gt 0 ]]; then
                local __drosh_elapsed=$(( ${d}(date +%s) - ${d}__drosh_start ))
                { echo "${d}__drosh_cmd|${d}__drosh_elapsed|${d}__drosh_code" >> "${d}__drosh_cf" } 2>/dev/null
                __drosh_cmd=""
                __drosh_start=0
              fi
            }
        """.trimIndent()

        hooksFile.writeText(hooksContent)

        return mapOf("ENV" to hooksFile.absolutePath)
    }

    private fun ensureShellRc() {
        val d = "${'$'}"
        val zshrc = File(ubuntuBootstrap.rootfsDir, "home/.zshrc")

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

                if [[ -z "${d}IRIS_WELCOME_SHOWN" ]]; then
                    export IRIS_WELCOME_SHOWN=1
                    echo ""
                    echo "  ╔══════════════════════════════════════════╗"
                    echo "  ║        Welcome to Iris Code v1.0         ║"
                    echo "  ║     Your AI-powered coding terminal      ║"
                    echo "  ╚══════════════════════════════════════════╝"
                    echo ""
                fi
        """.trimIndent() + "\n"

        if (!zshrc.exists()) {
            zshrc.writeText(cleanTemplate)
        } else {
            // Migrate: remove legacy hooks from old .zshrc
            val content = zshrc.readText()
            if (content.contains("__drosh_cmd") || content.contains("preexec()")) {
                zshrc.writeText(cleanTemplate)
            }
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

        if (persistentId != null) {
            idToIndex.remove(persistentId)
        }

        for (i in idx until irisSessions.size) {
            val id = irisSessions[i].persistentId
            if (id != null) idToIndex[id] = i
        }

        when {
            idx < _activeTabIndex.value -> _activeTabIndex.value--
            idx == _activeTabIndex.value && _activeTabIndex.value >= irisSessions.size ->
                _activeTabIndex.value = (irisSessions.size - 1).coerceAtLeast(0)
        }

        terminalViewRef?.let { view ->
            currentSession?.let { view.attachSession(it) }
        }

        // If no sessions remain, notify the data layer. Following Termux's
        // pattern, this signals the UI to exit rather than auto-creating a
        // replacement (default creation at startup is handled separately
        // by SessionManagerAdapter.start()).
        if (irisSessions.isEmpty()) {
            lifecycleCallbacks?.onLastSessionExited()
        }

        lifecycleCallbacks?.onSessionFinished(persistentId, exitCode)
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

        val cmd = prootRunner.buildBashCommand(command, guestWd, shellPath)

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
