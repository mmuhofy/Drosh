package com.iris.irisshell.ui.setup.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.iris.irisshell.design.system.IrisBackground
import com.iris.irisshell.domain.terminal.PackageProfile
import com.iris.irisshell.domain.terminal.SetupPreferences
import com.iris.irisshell.domain.terminal.ShellChoice
import com.iris.irisshell.domain.settings.PinLockRepository
import com.iris.irisshell.ui.setup.OnboardingViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.iris.irisshell.ui.setup.onboarding.components.SetupButton
import com.iris.irisshell.ui.setup.onboarding.scenes.DeviceCheckScene
import com.iris.irisshell.ui.setup.onboarding.scenes.PreferencesScene
import com.iris.irisshell.ui.setup.onboarding.scenes.PreferencesState
import com.iris.irisshell.ui.setup.onboarding.scenes.ShellSetupScene
import com.iris.irisshell.ui.setup.onboarding.scenes.WelcomeScene
import com.iris.irisshell.ui.pin.PinSetupScreen
import kotlinx.coroutines.launch

/**
 * Four-scene onboarding wizard:
 *
 *   Welcome → DeviceCheck → Preferences → ShellSetup (Zsh only)
 *
 * State:
 *   - [userName]    → used as shell prompt after bootstrap
 *   - [shellChoice] → Zsh or Bash; drives whether ShellSetup is shown
 *   - [packageProfile] + [customPackages] → sent to bootstrap use case
 *
 * Skip is available on Welcome and DeviceCheck via SkipAnchor, and routes
 * straight to finishOnboarding (same as completing the full wizard).
 */
@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    var scene by remember { mutableStateOf(OnboardingSceneKind.Welcome) }
    val coroutineScope = rememberCoroutineScope()

    var userName by remember { mutableStateOf("muhofy") }
    var shellChoice by remember { mutableStateOf(ShellChoice.Zsh) }
    var packageProfile by remember { mutableStateOf(PackageProfile.Developer) }
    var customPackages by remember { mutableStateOf(setOf<String>()) }

    val finish: () -> Unit = {
        coroutineScope.launch {
            viewModel.finishOnboarding(
                SetupPreferences(
                    userName = userName,
                    shellChoice = shellChoice,
                    packageProfile = packageProfile,
                    customPackages = customPackages,
                )
            )
            onCompleted()
        }
    }

    val advance: () -> Unit = {
        val next = scene.next()
        if (next == OnboardingSceneKind.ShellSetup && shellChoice != ShellChoice.Zsh) {
            scene = OnboardingSceneKind.Security
        } else if (next == OnboardingSceneKind.Security) {
            scene = next
        } else if (next != null) {
            scene = next
        } else {
            finish()
        }
    }

    val skip: () -> Unit = {
        coroutineScope.launch {
            viewModel.finishOnboarding(
                SetupPreferences(
                    userName = userName,
                    shellChoice = shellChoice,
                    packageProfile = packageProfile,
                    customPackages = customPackages,
                )
            )
            onCompleted()
        }
    }

    val startPinSetup: (pin: String) -> Unit = { pin ->
        coroutineScope.launch {
            viewModel.setPin(pin)
            viewModel.finishOnboarding(
                SetupPreferences(
                    userName = userName,
                    shellChoice = shellChoice,
                    packageProfile = packageProfile,
                    customPackages = customPackages,
                )
            )
            onCompleted()
        }
    }

    val prefsState = remember(
        userName, shellChoice, packageProfile, customPackages,
    ) {
        PreferencesState(
            userName = userName,
            shellChoice = shellChoice,
            packageProfile = packageProfile,
            customPackages = customPackages,
            onUserNameChange = { userName = it },
            onShellChoiceChange = { shellChoice = it },
            onPackageProfileChange = { packageProfile = it },
            onCustomPackageToggled = { pkg ->
                customPackages = if (customPackages.contains(pkg)) {
                    customPackages - pkg
                } else {
                    customPackages + pkg
                }
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(IrisBackground),
    ) {
        AnimatedContent(
            targetState = scene,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) togetherWith
                    fadeOut(animationSpec = tween(180)))
            },
            label = "onboarding-scene",
        ) { current ->
            when (current) {
                OnboardingSceneKind.Welcome ->
                    WelcomeScene(onContinue = advance, onSkip = skip)
                OnboardingSceneKind.DeviceCheck ->
                    DeviceCheckScene(onContinue = advance, onSkip = skip)
                OnboardingSceneKind.Preferences ->
                    PreferencesScene(state = prefsState, onContinue = advance)
                OnboardingSceneKind.ShellSetup ->
                    ShellSetupScene(onContinue = advance)
                OnboardingSceneKind.Security ->
                    PinSetupScreen(
                        onPinSet = startPinSetup,
                        onSkip = skip,
                    )
            }
        }
    }
}
