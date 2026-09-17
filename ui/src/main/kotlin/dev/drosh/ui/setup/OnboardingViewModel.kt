package dev.drosh.ui.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
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
 * the walkthrough. The actual bootstrap is NOT started here — that's deferred
 * to [SetupWizardViewModel] so the user can review and adjust their package
 * profile and shell choice in the 3-stage setup wizard before PRoot + Ubuntu
 * rootfs installation begins.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val firstLaunch: ObserveFirstLaunchUseCase,
    private val pinLock: PinLockRepository,
) : ViewModel() {

    val isCompleted: StateFlow<Boolean> = firstLaunch.isCompleted()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun finishOnboarding() {
        viewModelScope.launch {
            try {
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
