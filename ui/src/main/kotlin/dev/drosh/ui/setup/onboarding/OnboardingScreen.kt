package dev.drosh.ui.setup.onboarding

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import dev.drosh.design.system.DroshBackground
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.SetupPreferences
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.ui.setup.OnboardingViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.drosh.ui.setup.onboarding.scenes.AboutScene
import dev.drosh.ui.setup.onboarding.scenes.PickShellScene
import dev.drosh.ui.setup.onboarding.scenes.WelcomeScene
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onCompleted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    var scene by remember { mutableStateOf(OnboardingSceneKind.Welcome) }
    var hasAnimated by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var shellChoice by remember { mutableStateOf(ShellChoice.Zsh) }

    val finish: (ShellChoice) -> Unit = { chosenShell ->
        coroutineScope.launch {
            viewModel.finishOnboarding(
                SetupPreferences(
                    userName = "user",
                    shellChoice = chosenShell,
                    packageProfile = PackageProfile.Developer,
                    customPackages = emptySet(),
                )
            )
            onCompleted()
        }
    }

    val advance: () -> Unit = {
        scene.next()?.let { scene = it } ?: finish(shellChoice)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = scene,
            transitionSpec = {
                val slideDistance = 32
                val tweenIn = tween(durationMillis = 280, easing = androidx.compose.animation.core.EaseInOut)
                val tweenOut = tween(durationMillis = 250, easing = androidx.compose.animation.core.EaseInOut)

                val isForward = targetState.ordinal > initialState.ordinal
                val slideIn = if (isForward) {
                    slideInHorizontally(
                        animationSpec = tweenIn,
                        initialOffsetX = { -slideDistance },
                    )
                } else {
                    slideInHorizontally(
                        animationSpec = tweenIn,
                        initialOffsetX = { slideDistance },
                    )
                }
                val slideOut = if (isForward) {
                    slideOutHorizontally(
                        animationSpec = tweenOut,
                        targetOffsetX = { -slideDistance },
                    )
                } else {
                    slideOutHorizontally(
                        animationSpec = tweenOut,
                        targetOffsetX = { slideDistance },
                    )
                }
                val fadeIn = fadeIn(animationSpec = tweenIn)
                val fadeOut = fadeOut(animationSpec = tweenOut)

                (slideIn + fadeIn) togetherWith (slideOut + fadeOut)
            },
            label = "onboarding-scene",
        ) { current ->
            when (current) {
                OnboardingSceneKind.Welcome ->
                    WelcomeScene(
                        onNext = advance,
                        hasAnimated = hasAnimated,
                    )
                OnboardingSceneKind.About ->
                    AboutScene(
                        onNext = advance,
                    )
                OnboardingSceneKind.PickShell ->
                    PickShellScene(
                        onStartSetup = { shell ->
                            hasAnimated = true
                            finish(shell)
                        },
                    )
            }
        }

        LaunchedEffect(Unit) {
            hasAnimated = true
        }
    }
}
