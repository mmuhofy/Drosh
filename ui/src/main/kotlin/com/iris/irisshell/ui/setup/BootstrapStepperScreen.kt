package com.iris.irisshell.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.OutfitFontFamily
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.irisshell.domain.terminal.BootstrapProgress
import com.iris.irisshell.domain.terminal.BootstrapStep
import com.iris.irisshell.domain.terminal.StepState
import com.iris.irisshell.ui.setup.components.LiveLogCard
import com.iris.irisshell.ui.setup.components.SetupHeroMark
import com.iris.irisshell.ui.setup.components.StepRow
import com.iris.irisshell.ui.setup.label
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Full-screen bootstrap stepper.
 *
 * Layout (top to bottom):
 *  - Hero: `SetupHeroMark` + `"Iris Shell"` + tagline
 *  - Stepper: 5 StepRows + connectors
 *  - Current step message (blue caption)
 *  - Determinate progress bar + ETA
 *  - LiveLogCard (collapsed by default, expandable to a 320dp drawer)
 *
 * When the progress becomes Failed, the host (MainActivity) routes to
 * [SetupRecoveryScreen] instead — this composable always assumes progress
 * (Ready / In-progress) inputs.
 */
@Composable
fun BootstrapStepperScreen(
    onReady: () -> Unit,
    onSetupFailed: () -> Unit,
    viewModel: BootstrapViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val liveLogs by viewModel.liveLogs.collectAsStateWithLifecycle()
    val isLogDrawerOpen by viewModel.isLogDrawerOpen.collectAsStateWithLifecycle()

    // Side-effects: when fully ready, fire onReady; when failed, fire onSetupFailed.
    if (progress.isReady) {
        onReady()
        return
    } else if (progress.isFailed) {
        onSetupFailed()
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SetupPalette.Background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SetupHeroMark(sizeDp = 80.dp)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Iris Shell",
                color = SetupPalette.Text,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                ),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Your phone is a Unix machine. Finally.",
                color = SetupPalette.TextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 14.sp,
                ),
            )

            Spacer(modifier = Modifier.height(40.dp))

            StepperList(progress)

            Spacer(modifier = Modifier.height(24.dp))

            CurrentMessage(progress)

            Spacer(modifier = Modifier.height(16.dp))

            ProgressBlock(progress)

            Spacer(modifier = Modifier.height(32.dp))

            LiveLogCard(
                lines = liveLogs,
                expanded = isLogDrawerOpen,
                onToggleOpen = { viewModel.toggleLogDrawer() },
            )
        }
    }
}

@Composable
private fun StepperList(progress: BootstrapProgress) {
    val ordered = listOf(
        BootstrapStep.Extracting,
        BootstrapStep.Configuring,
        BootstrapStep.InstallingPackages,
        BootstrapStep.InstallingOhMyZsh,
        BootstrapStep.Optimizing,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        ordered.forEachIndexed { index, step ->
            val stateForStep = progress.stepStates[step] ?: StepState.Pending
            StepRow(
                step = step,
                title = step.label(),
                subLine = if (stateForStep == StepState.Active && step == progress.currentStep)
                    progress.currentMessage
                else null,
                state = stateForStep,
                showConnector = index != ordered.lastIndex,
            )
        }
    }
}

@Composable
private fun CurrentMessage(progress: BootstrapProgress) {
    AnimatedContent(
        targetState = progress.currentMessage,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)))
        },
        label = "current-message",
    ) { msg ->
        Text(
            text = msg,
            color = SetupPalette.Primary,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ProgressBlock(progress: BootstrapProgress) {
    val pct = progress.percent.coerceIn(0, 100)
    val etaSec = (progress.estimatedRemainingMs ?: 0L) / 1000L

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SetupPalette.SurfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct / 100f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SetupPalette.Primary),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$pct%",
                color = SetupPalette.TextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = when {
                    progress.isReady -> "Done"
                    etaSec <= 0L -> "Finalizing…"
                    else -> "~$etaSec s left"
                },
                color = SetupPalette.TextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

/** Tiny alias to compose Row quickly without adding the prod wrapper. */
@Composable
private fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        content = content,
    )
}

// Re-export for callers that want to use the WindowInsets scanner — kept
// to satisfy parity with `Surface` usage in MainActivity.
@Suppress("unused")
internal val TopInsetRef: Boolean = true
@Suppress("unused")
internal val BottomInsetRef: Boolean = true
