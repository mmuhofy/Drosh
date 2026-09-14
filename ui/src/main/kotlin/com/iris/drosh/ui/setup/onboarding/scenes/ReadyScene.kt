package com.iris.irisshell.ui.setup.onboarding.scenes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iris.irisshell.ui.setup.onboarding.components.CursorPos
import com.iris.irisshell.ui.setup.onboarding.components.OnboardingOverlay
import com.iris.irisshell.ui.setup.onboarding.components.SkipAnchor
import com.iris.irisshell.ui.setup.onboarding.components.TerminalBackdrop

/**
 * Scene 3 — Ready.
 *
 * Last scene before bootstrap kicks off. Terminal shows a static
 * transcript of "iris setup" launching.
 */
@Composable
fun ReadyScene(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = listOf(
        "$ iris setup",
        "",
        "Preparing first launch…",
        "Bootstrap is about to start.",
    )
    Box(modifier = modifier.fillMaxSize()) {
        TerminalBackdrop(
            lines = lines,
            cursorPosition = CursorPos(line = lines.size - 1, column = 16),
        )
        SkipAnchor(onSkip = onSkip)
        OnboardingOverlay(
            caption = "Setting up your Unix environment.\n" +
                "About 3 minutes. You can watch every step.",
            continueLabel = "Start setup",
            onContinue = onContinue,
        )
    }
}
