package com.iris.irisshell.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iris.irisshell.domain.terminal.BootstrapProgress
import com.iris.irisshell.domain.terminal.ObserveBootstrapUseCase
import com.iris.irisshell.domain.terminal.TriggerBootstrapUseCase
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
 * Holds the bootstrap state for `BootstrapStepperScreen` and `SetupRecoveryScreen`.
 *
 * Exposes:
 *   - [progress]         : `StateFlow<BootstrapProgress>` — feeds the stepper + recovery UI
 *   - [liveLogs]         : `StateFlow<List<String>>`     — feeds the live-log drawer
 *   - [isLogDrawerOpen]  : toggle state for the log drawer
 *
 * Actions (\"retry\", \"re-download\", \"reset\") are delegated straight through
 * [TriggerBootstrapUseCase].
 */
@HiltViewModel
class BootstrapViewModel @Inject constructor(
    observeBootstrap: ObserveBootstrapUseCase,
    private val triggerBootstrap: TriggerBootstrapUseCase,
) : ViewModel() {

    val progress: StateFlow<BootstrapProgress> = observeBootstrap.progress()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = BootstrapProgress.initial(),
        )

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    private val _isLogDrawerOpen = MutableStateFlow(false)
    val isLogDrawerOpen: StateFlow<Boolean> = _isLogDrawerOpen.asStateFlow()

    init {
        observeBootstrap.liveLogs()
            .onEach { line ->
                _liveLogs.value = (_liveLogs.value + line).takeLast(LOG_DRAWER_LIMIT)
            }
            .launchIn(viewModelScope)
    }

    fun setLogDrawerOpen(open: Boolean) {
        _isLogDrawerOpen.value = open
    }

    fun toggleLogDrawer() {
        _isLogDrawerOpen.value = !_isLogDrawerOpen.value
    }

    fun retry() {
        runTrigger { triggerBootstrap.retry() }
    }

    fun reDownloadRootfs() {
        runTrigger { triggerBootstrap.reDownloadRootfs() }
    }

    fun resetEverything() {
        runTrigger { triggerBootstrap.resetEverything() }
    }

    private fun runTrigger(block: () -> Unit) {
        viewModelScope.launch {
            block()
        }
    }

    private companion object {
        const val LOG_DRAWER_LIMIT = 200
    }
}
