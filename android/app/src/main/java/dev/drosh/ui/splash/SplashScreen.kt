package dev.drosh.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.LocalFontSet
import kotlinx.coroutines.delay

/** Minimum time the splash stays on screen, so it never appears as a 0-frame flash. */
private const val MINIMUM_HOLD_MS = 550L

/** Duration of the one-shot fade-in of the prompt, in milliseconds. */
private const val FADE_IN_DURATION_MS = 700

/**
 * The cold-start screen: a single, large `>_` terminal prompt on the app
 * background. It draws with [Text], not a text field — a focusable view at
 * cold start would put the keyboard up over the splash.
 *
 * The prompt is pure white and brightens with a single one-shot fade-in —
 * never a blink, a loop, or a repeated flash. [onFinished] is called exactly
 * once, after the minimum hold has elapsed and when [ready] (the app's own
 * first-launch flag and settings first read) is true. The caller gates
 * navigation on both that signal and its own readiness check.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    ready: Boolean = false,
) {
    // UNTESTED — verify before use: the once-guard, minimum-hold timing,
    // and the one-shot fade-in animation below.
    var minimumElapsed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    // A one-shot fade-IN: start fully transparent and run 0f -> 1f exactly once.
    // This is its own effect so the fade begins on the first frame, independent
    // of the minimum hold and of onFinished gating navigation.
    val promptAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        promptAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = FADE_IN_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    LaunchedEffect(Unit) {
        delay(MINIMUM_HOLD_MS)
        minimumElapsed = true
    }

    LaunchedEffect(ready, minimumElapsed) {
        if (ready && minimumElapsed && !finished) {
            finished = true
            onFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ">_",
            color = Color.White,
            fontFamily = LocalFontSet.current.mono,
            fontSize = 72.sp,
            maxLines = 1,
            modifier = Modifier.alpha(promptAlpha.value),
        )
    }
}
