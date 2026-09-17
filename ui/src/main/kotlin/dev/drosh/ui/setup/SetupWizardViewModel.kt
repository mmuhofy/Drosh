package dev.drosh.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.SetupPreferences
import dev.drosh.domain.terminal.ShellChoice
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

    private val _packageProfile = MutableStateFlow(PackageProfile.Standard)
    val packageProfile: StateFlow<PackageProfile> = _packageProfile.asStateFlow()

    private val _shellChoice = MutableStateFlow(ShellChoice.Zsh)
    val shellChoice: StateFlow<ShellChoice> = _shellChoice.asStateFlow()

    private val _customPackagesText = MutableStateFlow("")
    val customPackagesText: StateFlow<String> = _customPackagesText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        observeBootstrap.liveLogs()
            .onEach { line ->
                _liveLogs.value = (_liveLogs.value + line).takeLast(LOG_DRAWER_LIMIT)
            }
            .launchIn(viewModelScope)
    }

    fun selectProfile(profile: PackageProfile) {
        _packageProfile.value = profile
    }

    fun selectShell(shell: ShellChoice) {
        _shellChoice.value = shell
    }

    fun setCustomPackagesText(text: String) {
        _customPackagesText.value = text
    }

    fun toggleLogDrawer() {
        _isLogDrawerOpen.value = !_isLogDrawerOpen.value
    }

    fun setLogDrawerOpen(open: Boolean) {
        _isLogDrawerOpen.value = open
    }

    fun startBootstrap() {
        val customPackages = _customPackagesText.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val preferences = SetupPreferences(
            userName = "user",
            shellChoice = _shellChoice.value,
            packageProfile = _packageProfile.value,
            customPackages = customPackages,
        )

        viewModelScope.launch {
            try {
                triggerBootstrap.start(preferences)
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
