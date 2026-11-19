package dev.drosh.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.RootfsDownloadProgress
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State for the two onboarding screens.
 *
 * There are no choices to collect: [startBootstrap] streams the single setup
 * script into the guest — extract the rootfs, then configure it. This view
 * model only drives that pipeline and exposes what the setup screen renders:
 * progress, live logs and the log-drawer toggle. The theme is a Settings
 * concern and is deliberately absent here.
 *
 * The first-launch flag is deliberately not written here: reaching the
 * terminal is the signal that setup finished, and MainActivity writes it in
 * its `onReady` callback. Marking it when the button is tapped stranded devices
 * that died mid-install with a finished flag and an incomplete guest.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    observeBootstrap: ObserveBootstrapUseCase,
    private val triggerBootstrap: TriggerBootstrapUseCase,
) : ViewModel() {

    val progress: StateFlow<BootstrapProgress> = observeBootstrap.progress()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = BootstrapProgress.initial(),
        )

    val triggerState: StateFlow<TriggerBootstrapUseCase.State> =
        triggerBootstrap.stateFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TriggerBootstrapUseCase.State.NotStarted,
        )

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    /**
     * Latest byte sample from the process-scoped download flow.
     *
     * Null until the first sample, so the screen does not show an empty bar
     * before the download starts. An Activity recreation re-collects the same
     * `ObserveBootstrapUseCase` flow and picks up where the service is now.
     */
    private val _rootfsDownload = MutableStateFlow<RootfsDownloadProgress?>(null)
    val rootfsDownload: StateFlow<RootfsDownloadProgress?> = _rootfsDownload.asStateFlow()

    private val _isLogDrawerOpen = MutableStateFlow(false)
    val isLogDrawerOpen: StateFlow<Boolean> = _isLogDrawerOpen.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        observeBootstrap.liveLogs()
            .onEach { line ->
                _liveLogs.value = (_liveLogs.value + line).takeLast(LOG_DRAWER_LIMIT)
            }
            .launchIn(viewModelScope)
        observeBootstrap.rootfsDownload()
            .onEach { _rootfsDownload.value = it }
            .launchIn(viewModelScope)
    }

    fun toggleLogDrawer() {
        _isLogDrawerOpen.value = !_isLogDrawerOpen.value
    }

    fun setLogDrawerOpen(open: Boolean) {
        _isLogDrawerOpen.value = open
    }

    fun startBootstrap() {
        viewModelScope.launch {
            try {
                // The service de-duplicates a second start: an already-running
                // job is not relaunched, so a recreated screen cannot double-
                // download the same rootfs. Clear the old sample so the new
                // attempt starts its bar from zero.
                _rootfsDownload.value = null
                if (triggerBootstrap.state != TriggerBootstrapUseCase.State.Running) {
                    triggerBootstrap.start()
                }
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /** The only thing that stops a background setup: an explicit user action. */
    fun cancelSetup() {
        triggerBootstrap.cancelSetup()
        _rootfsDownload.value = null
    }

    fun retry() {
        viewModelScope.launch {
            triggerBootstrap.retry()
        }
    }

    fun reDownloadRootfs() {
        viewModelScope.launch {
            triggerBootstrap.reDownloadRootfs()
        }
    }

    fun resetEverything() {
        viewModelScope.launch {
            triggerBootstrap.resetEverything()
        }
    }

    private companion object {
        const val LOG_DRAWER_LIMIT = 200
    }
}
