package dev.drosh.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
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
 * There are no choices to collect: [startBootstrap] launches the shell-script
 * pipeline with its defaults — extract the rootfs, prepare bash, start the
 * shell. This view model only drives that pipeline and exposes what the setup
 * screen renders: progress, live logs and the log-drawer toggle. The theme is
 * a Settings concern and is deliberately absent here.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    observeBootstrap: ObserveBootstrapUseCase,
    private val triggerBootstrap: TriggerBootstrapUseCase,
    private val firstLaunch: ObserveFirstLaunchUseCase,
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
                triggerBootstrap.start()
                firstLaunch.markCompleted()
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
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
