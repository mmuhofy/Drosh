package dev.drosh.ui.setup

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.drosh.design.system.DroshBackground
import dev.drosh.ui.setup.stages.BootstrapStage
import dev.drosh.ui.setup.stages.PackageSelectionStage
import dev.drosh.ui.setup.stages.SetupOverviewStage

@Composable
fun SetupWizardScreen(
    onReady: () -> Unit,
    onSetupFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stage by rememberSaveable { mutableStateOf(SetupStage.Overview) }

    val slideDistance = 32
    val durationEnter = 280
    val durationExit = 250

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = stage,
            transitionSpec = {
                val isForward = targetState.ordinal > initialState.ordinal
                val slideIn = slideInHorizontally(
                    animationSpec = tween(durationMillis = durationEnter),
                    initialOffsetX = { if (isForward) -slideDistance else slideDistance },
                )
                val slideOut = slideOutHorizontally(
                    animationSpec = tween(durationMillis = durationExit),
                    targetOffsetX = { if (isForward) slideDistance else -slideDistance },
                )
                val fadeInSpec = fadeIn(animationSpec = tween(durationMillis = durationEnter))
                val fadeOutSpec = fadeOut(animationSpec = tween(durationMillis = durationExit))

                (slideIn + fadeInSpec) togetherWith (slideOut + fadeOutSpec)
            },
            label = "setup-wizard-stage",
        ) { current ->
            when (current) {
                SetupStage.Overview ->
                    SetupOverviewStage(
                        onNext = { stage = SetupStage.PackageSelection },
                    )

                SetupStage.PackageSelection ->
                    PackageSelectionStage(
                        onNext = { stage = SetupStage.Bootstrap },
                    )

                SetupStage.Bootstrap ->
                    BootstrapStage(
                        onReady = onReady,
                        onSetupFailed = onSetupFailed,
                    )
            }
        }
    }
}
