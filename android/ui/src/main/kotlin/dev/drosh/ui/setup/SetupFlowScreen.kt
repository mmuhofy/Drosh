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
import androidx.compose.runtime.LaunchedEffect
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
import dev.drosh.ui.setup.onboarding.scenes.TerminalReadyScene
import dev.drosh.ui.setup.onboarding.scenes.WelcomeScene
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator
import dev.drosh.ui.setup.stages.BootstrapStage

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

    // The bootstrap finishing is what opens the last page: the stage below
    // shows the work, and the moment it reports ready the flow moves on its
    // own rather than asking the user to find a button in the log drawer's
    // shadow. Keyed on readiness alone so a retry that ends ready again does
    // not drag the user back out of the page they are already reading.
    LaunchedEffect(progress.isReady) {
        if (progress.isReady && currentPage == 1) {
            currentPage = 2
        }
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
                    onNext = {
                        // The theme is picked on this page, so the bootstrap it
                        // starts is launched with the palette already held in
                        // the wizard — the write happens when the rootfs lands,
                        // and the terminal opens in it.
                        viewModel.startBootstrap()
                        currentPage = 1
                    },
                    viewModel = viewModel,
                )
                1 -> BootstrapStage(
                    onSetupFailed = onSetupFailed,
                    viewModel = viewModel,
                )
                2 -> TerminalReadyScene(
                    // Applying the theme is the last thing before the terminal
                    // opens, and the callback is what orders them: the session
                    // that appears is already drawn in the palette that was
                    // picked, rather than the default for a moment and then the
                    // right one. The write is idempotent — the wizard already
                    // fired it when the bootstrap reported ready — so this is
                    // the ordering guarantee, not a second write.
                    onReady = {
                        viewModel.applySelectedTheme(onApplied = onReady)
                    },
                )
            }
        }

        WormPageIndicator(
            pageCount = 3,
            currentPage = currentPage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        )
    }
}
