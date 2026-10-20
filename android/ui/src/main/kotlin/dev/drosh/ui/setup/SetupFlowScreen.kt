package dev.drosh.ui.setup

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.ui.setup.onboarding.scenes.AboutScene
import dev.drosh.ui.setup.onboarding.scenes.ThemeScene
import dev.drosh.ui.setup.onboarding.scenes.WelcomeScene
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator
import dev.drosh.ui.setup.stages.BootstrapStage
import dev.drosh.ui.setup.stages.PackageSelectionStage

@Composable
fun SetupFlowScreen(
    onReady: () -> Unit,
    onSetupFailed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    if (progress.isFailed) {
        onSetupFailed()
        return
    }

    val slideDistance = 32
    val durationEnter = 280
    val durationExit = 250

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                val isForward = targetState > initialState
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
            label = "setup-flow",
        ) { page ->
            when (page) {
                0 -> WelcomeScene(
                    onNext = { currentPage = 1 },
                    hasAnimated = true,
                )
                1 -> AboutScene(
                    onNext = { currentPage = 2 },
                )
                2 -> ThemeScene(
                    // The bootstrap has not started yet, and it is the point
                    // after which the palette can be written to the rootfs copy
                    // of settings.toml. Nothing to do on this page — the write
                    // is the wizard's, triggered by bootstrap reaching ready.
                    onNext = { currentPage = 3 },
                    viewModel = viewModel,
                )
                3 -> PackageSelectionStage(
                    onNext = { currentPage = 4 },
                    viewModel = viewModel,
                )
                4 -> BootstrapStage(
                    // Applying the theme is the last thing before the terminal
                    // opens, and the callback is what orders them: the session
                    // that appears is already drawn in the palette that was
                    // picked, rather than the default for a moment and then the
                    // right one.
                    onReady = {
                        viewModel.applySelectedTheme(onApplied = onReady)
                    },
                    onSetupFailed = onSetupFailed,
                    viewModel = viewModel,
                )
            }
        }

        WormPageIndicator(
            pageCount = 5,
            currentPage = currentPage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        )
    }
}
