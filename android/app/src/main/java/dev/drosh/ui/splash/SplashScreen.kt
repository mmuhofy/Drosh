package dev.drosh.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTrack
import kotlinx.coroutines.delay

private const val WORD = "Drosh"
private const val CHAR_MS = 110L
/** How long the newest letter stays lit before it settles. */
private const val FLASH_MS = 380L
private const val MINIMUM_HOLD_MS = 1500L

/**
 * The cold-start screen: the name, typed out one letter at a time, over a
 * hairline that fills as the work behind it finishes.
 *
 * The word is drawn by [Text], not a text field — the old screen used a
 * read-only `BasicTextField`, which is a focusable view, and a focusable
 * view at cold start is what put the keyboard up over the splash.
 *
 * [ready] is the app's own work (the first-launch flag, the settings
 * file's first read). The word types at its own pace regardless; when
 * both have finished, the screen holds a beat and leaves. Nothing here
 * waits on a timer alone and nothing leaves before the minimum: the two
 * are joined, so a slow disk shows the hairline doing the waiting rather
 * than a frozen word.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    ready: Boolean = false,
) {
    var revealed by remember { mutableIntStateOf(0) }
    var lit by remember { mutableIntStateOf(0) }
    val shown = WORD.take(revealed)

    // The hairline's own progress toward [ready]: it never moves backward,
    // and it eases the last stretch so a finish does not snap.
    val hairline = remember { Animatable(0f) }
    LaunchedEffect(ready) {
        if (ready) {
            hairline.animateTo(1f, tween(durationMillis = 420, easing = LinearEasing))
        } else {
            hairline.animateTo(0.85f, tween(durationMillis = 1400, easing = LinearEasing))
        }
    }

    LaunchedEffect(Unit) {
        for (index in 1..WORD.length) {
            revealed = index
            lit = index
            delay(CHAR_MS)
        }
        delay(FLASH_MS)
        lit = 0
        // Never flash: hold the remainder of the minimum however quick the typing.
        delay(MINIMUM_HOLD_MS - (WORD.length * CHAR_MS + FLASH_MS))
    }

    LaunchedEffect(ready, revealed) {
        if (ready && revealed == WORD.length) {
            delay(320)
            onFinished()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        contentAlignment = Alignment.Center,
    ) {
        // Read out here: a `remember` block is not a composable scope, and a
        // colour read inside one is a composable call outside one.
        val restColor = DroshText
        val litColor = DroshPrimary

        // One column: the word, and under it the hairline. Both are centred
        // on the same axis so the pair reads as one mark.
        ColumnCentered {
            val text = remember(shown, lit, restColor, litColor) {
                if (lit == 0 || lit > shown.length) {
                    AnnotatedString(shown)
                } else {
                    AnnotatedString.Builder().apply {
                        append(shown.substring(0, lit - 1))
                        withStyle(SpanStyle(color = litColor)) { append(shown[lit - 1]) }
                        if (lit < shown.length) append(shown.substring(lit))
                    }.toAnnotatedString()
                }
            }
            Text(
                text = text,
                color = DroshText,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )

            Box(
                modifier = Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth(0.34f)
                    .height(2.dp)
                    .background(DroshTrack),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(hairline.value.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(DroshPrimary),
                )
            }

            Text(
                text = "Your phone is a Unix machine",
                color = DroshTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** A centred column that does not need its own modifier from the caller. */
@Composable
private fun ColumnCentered(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        content()
    }
}
