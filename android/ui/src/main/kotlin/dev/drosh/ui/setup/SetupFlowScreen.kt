package dev.drosh.ui.setup

import androidx.compose.animation.AnimatedContent
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
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator

/**
 * The two-screen onboarding flow: a presentation of Drosh, then the one-time
 * install. There is no theme page and no third "ready" page — the install
 * screen hands straight off to the terminal when the bootstrap finishes.
 *
 * Failure leaves the flow entirely: [onSetupFailed] opens the recovery route,
 * which stays available as the diagnostic path.
 */
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

    // The bootstrap finishing is the hand-off. Keyed on the page as well as
    // readiness: a device that was already installed can report ready before
    // the user leaves the welcome screen, and the transition into the setup
    // page must still open the terminal rather than showing a finished bar.
    LaunchedEffect(progress.isReady, currentPage) {
        if (progress.isReady && currentPage == 1) {
            onReady()
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
        AnimatedContent(
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
                (slideIn + fadeIn(animationSpec = tween(durationMillis = durationEnter))) togetherWith
                    (slideOut + fadeOut(animationSpec = tween(durationMillis = durationExit)))
            },
            label = "setup-flow",
        ) { page ->
            when (page) {
                0 -> WelcomeScreen(
                    onGetStarted = {
                        // One action: start the shell-script pipeline with its
                        // defaults, then show its progress. No choices to collect.
                        viewModel.startBootstrap()
                        currentPage = 1
                    },
                )
                1 -> SetupScreen(viewModel = viewModel)
            }
        }

        WormPageIndicator(
            pageCount = 2,
            currentPage = currentPage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        )
    }
}
