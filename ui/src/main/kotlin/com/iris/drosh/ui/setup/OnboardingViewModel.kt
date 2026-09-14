package com.iris.irisshell.ui.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iris.irisshell.domain.settings.PinLockRepository
import com.iris.irisshell.domain.terminal.ObserveFirstLaunchUseCase
import com.iris.irisshell.domain.terminal.SetupPreferences
import com.iris.irisshell.domain.terminal.TriggerBootstrapUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives [OnboardingScreen].
 *
 * Persists [ObserveFirstLaunchUseCase.markCompleted] once the user finishes
 * the wizard. Before marking, it kicks off the real bootstrap via
 * [TriggerBootstrapUseCase.start] with the user's [SetupPreferences] so the
 * PRoot + Ubuntu rootfs pipeline honours the chosen shell, username, and
 * package profile.
 *
 * The bootstrap is started here (not deferred to MainActivity) so that the
 * user's preferences reach [BootstrapStatePort] → [UbuntuBootstrap] immediately
 * upon onboarding completion. MainActivity's auto-trigger is guarded by a
 * state check to avoid double-invocation.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val firstLaunch: ObserveFirstLaunchUseCase,
    private val triggerBootstrap: TriggerBootstrapUseCase,
    private val pinLock: PinLockRepository,
) : ViewModel() {

    val isCompleted: StateFlow<Boolean> = firstLaunch.isCompleted()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun finishOnboarding(preferences: SetupPreferences) {
        viewModelScope.launch {
            try {
                triggerBootstrap.start(preferences)
                firstLaunch.markCompleted()
            } catch (t: Throwable) {
                Log.e(TAG, "finishOnboarding: failed", t)
                throw t
            }
        }
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            pinLock.setPin(pin)
        }
    }

    private companion object {
        const val TAG = "OnboardingVM"
    }
}
