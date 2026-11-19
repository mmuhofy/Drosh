package dev.drosh.linux

import dev.drosh.domain.terminal.RootfsDownloadProgress
import dev.drosh.domain.terminal.SetupPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Internal bridge that lets `:data` observe bootstrap state + logs without
 * `ui/` ever importing `guest/UbuntuSetupState` or `UbuntuBootstrap`.
 *
 * The `:data` layer calls [runBootstrap] to drive the pipeline. The port
 * forwards each state transition and each emitted log line through typed
 * flows. The `:data` repository translates these into the `:domain` shape
 * (`BootstrapProgress` + log `Flow<String>`).
 *
 * Contravariant design: the bootstrap instance is owned by `MainActivity`'s
 * DI graph (Hilt); the port is the thin facade over it.
 */
class BootstrapStatePort(private val bootstrap: UbuntuBootstrap) {

    private val _state = MutableStateFlow<UbuntuSetupState>(
        if (bootstrap.isInstalled) UbuntuSetupState.Ready else UbuntuSetupState.Idle,
    )
    val state: StateFlow<UbuntuSetupState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /**
     * Byte progress from the running download. Dropping the oldest sample is
     * deliberate: the UI only needs the newest, and a slow observer must never
     * stall a chunk writer. The flow is process-scoped, so a recreated Activity
     * rebinds to the same ongoing download.
     */
    private val _rootfsDownload = MutableSharedFlow<RootfsDownloadProgress>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rootfsDownload: SharedFlow<RootfsDownloadProgress> = _rootfsDownload.asSharedFlow()

    /**
     * Whether the guest's filesystem is actually installed — the rootfs and
     * its completion marker, not the first-launch flag. Lets the navigation
     * gate send a device with a stale flag but an incomplete guest back to
     * setup instead of into an empty terminal.
     */
    fun isInstalled(): Boolean = bootstrap.isInstalled

    /**
     * Drives the whole pipeline in the caller's coroutine, so a foreground
     * service (not an Activity) owns the lifetime and cancellation. Safe to call
     * more than once: an installed guest short-circuits to `Ready`.
     */
    suspend fun runBootstrap(
        preferences: SetupPreferences = SetupPreferences.defaults(),
    ): UbuntuSetupState {
        if (bootstrap.isInstalled) {
            _state.value = UbuntuSetupState.Ready
            return UbuntuSetupState.Ready
        }
        _state.value = UbuntuSetupState.Idle
        bootstrap.install(
            preferences = preferences,
            onState = { _state.value = it },
            onLog = { _logs.tryEmit(it) },
            onDownloadProgress = { _rootfsDownload.tryEmit(it) },
        )
        return _state.value
    }
}
