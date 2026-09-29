package dev.drosh.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import android.os.SystemClock
import kotlinx.coroutines.delay

private const val WORD = "Drosh"
private const val CHAR_MS = 150L
/** How long the newest letter stays lit before it settles. */
private const val FLASH_MS = 420L
private const val MINIMUM_HOLD_MS = 1500L
private const val PUNCTUATION_PAUSE_MS = 90L

/**
 * The cold-start screen: the name, typed out one letter at a time with the
 * newest letter lit.
 *
 * Everything else that was here — the mark, a glow, three rotating lines of
 * copy, a filling hairline — is gone. There is one job and it is the word.
 *
 * [onFinished] waits out a minimum hold regardless of how much has been typed,
 * so a quick launch is a brief word rather than a flash of a half-drawn one.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by remember { mutableIntStateOf(0) }
    var lit by remember { mutableIntStateOf(0) }
    val shown = WORD.take(revealed)

    // One coroutine drives the whole thing, in order. This was two effects
    // watching two pieces of state: the effect that revealed a character only
    // ran once something had already been revealed, so it never started, and
    // each one restarting the other meant the letter never stayed lit. Driving
    // it from a single sequence removes the coordination entirely.
    LaunchedEffect(Unit) {
        val started = SystemClock.elapsedRealtime()
        for (index in 1..WORD.length) {
            val char = WORD[index - 1]
            revealed = index
            lit = index
            delay(if (char == ' ' || char == '.' || char == ',') PUNCTUATION_PAUSE_MS else CHAR_MS)
        }

        // Let the last letter sit lit before it settles.
        delay(FLASH_MS)
        lit = 0

        // Never flash: hold the remainder of the minimum however quick the typing.
        val remaining = MINIMUM_HOLD_MS - (SystemClock.elapsedRealtime() - started).toInt()
        if (remaining > 0) delay(remaining.toLong())
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        contentAlignment = Alignment.Center,
    ) {
        // Read out here: a remember block is not composable, and these are
        // part of what it recomputes on.
        val restColor = DroshText
        val litColor = DroshPrimary

        // The lit letter is coloured through the value itself rather than a
        // visual transformation: a transformation only moves glyphs, it cannot
        // restyle part of the string.
        val annotated = remember(shown, lit, restColor, litColor) {
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
        val fieldValue = remember(annotated) {
            TextFieldValue(
                annotatedString = annotated,
                selection = TextRange(shown.length),
            )
        }

        // Wrapped so the word is centred regardless of how wide the field
        // measures: the field itself wraps its content, so left it alone it sits
        // wherever its intrinsic width happens to fall.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                cursorBrush = SolidColor(DroshPrimary),
                textStyle = TextStyle(
                    color = restColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
