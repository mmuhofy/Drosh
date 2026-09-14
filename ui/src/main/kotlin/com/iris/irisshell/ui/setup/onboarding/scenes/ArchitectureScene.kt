package com.iris.irisshell.ui.setup.onboarding.scenes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.iris.irisshell.ui.setup.onboarding.components.OnboardingOverlay
import com.iris.irisshell.ui.setup.onboarding.components.SkipAnchor
import com.iris.irisshell.ui.setup.onboarding.components.TypewriterText

/**
 * Scene 2 — Architecture.
 *
 * Simulated "iris system" command output with typewriter animation. The
 * output uses the device's real primary ABI so the line "Architecture:"
 * reflects what the user is actually running on.
 */
@Composable
fun ArchitectureScene(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arch = remember {
        when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "ARM64 / aarch64"
            "armeabi-v7a" -> "ARMv7 / armhf"
            "x86_64" -> "x86_64 / amd64"
            "x86" -> "x86 / i386"
            else -> "unknown"
        }
    }
    val lines = remember(arch) {
        listOf(
            "$ iris system",
            "",
            " Drosh v1.0",
            " Architecture: $arch",
            " Runtime: PRoot + Ubuntu 24.04",
            " Terminal: termux-emulator (JNI)",
            " Shell: zsh (Oh My Zsh)",
            "",
        )
    }
    val fullText = lines.joinToString(separator = "\n")

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
        ) {
            TypewriterText(
                fullText = fullText,
                charDelayMs = 18L,
                onComplete = { /* completion is signaled by user via Continue */ },
            )
        }
        SkipAnchor(onSkip = onSkip)
        OnboardingOverlay(
            caption = "Real Ubuntu under the hood. No rooting, no Java pipe.",
            continueLabel = "Continue",
            onContinue = onContinue,
        )
    }
}
