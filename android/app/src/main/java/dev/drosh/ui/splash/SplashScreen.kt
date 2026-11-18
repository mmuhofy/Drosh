package dev.drosh.ui.splash

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
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.LocalFontSet
import kotlinx.coroutines.delay

/** Minimum time the splash stays on screen, so it never appears as a 0-frame flash. */
private const val MINIMUM_HOLD_MS = 550L

/**
 * The cold-start screen: a single, static `>_` terminal prompt on the app
 * background. It draws with [Text], not a text field — a focusable view at
 * cold start would put the keyboard up over the splash.
 *
 * Nothing here types, flashes, blinks, or animates. [onFinished] is called
 * exactly once, after the minimum hold has elapsed and when [ready] (the
 * app's own first-launch flag and settings first read) is true. The caller
 * gates navigation on both that signal and its own readiness check.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    ready: Boolean = false,
) {
    // UNTESTED — verify before use: the once-guard and minimum-hold timing.
    var minimumElapsed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

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
            color = DroshPrimary,
            fontFamily = LocalFontSet.current.mono,
            fontSize = 34.sp,
        )
    }
}
