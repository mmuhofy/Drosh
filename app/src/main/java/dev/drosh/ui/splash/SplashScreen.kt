package dev.drosh.ui.splash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
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

    LaunchedEffect(Unit) {
        // A fixed floor, plus a little more the first time round so the word is
        // never caught mid-draw on its only appearance.
        val drawTime = WORD.length * CHAR_MS + FLASH_MS
        delay(maxOf(MINIMUM_HOLD_MS, drawTime))
        onFinished()
    }

    LaunchedEffect(revealed) {
        if (revealed == 0) return@LaunchedEffect
        val char = WORD[revealed - 1]
        lit = revealed
        delay(if (char == ' ' || char == '.' || char == ',') PUNCTUATION_PAUSE_MS else CHAR_MS)
        revealed += 1
    }

    // Hold the newest letter lit for a beat, then let it settle to the same
    // colour as the letters before it.
    LaunchedEffect(lit) {
        if (lit == 0) return@LaunchedEffect
        delay(FLASH_MS)
        lit = 0
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            cursorBrush = SolidColor(DroshPrimary),
            textStyle = TextStyle(
                color = DroshText,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            ),
            modifier = Modifier,
            visualTransformation = { text ->
                if (lit == 0 || lit > text.length) {
                    text
                } else {
                    // Light the newest letter and leave the rest alone.
                    val litChar = text[lit - 1]
                    AnnotatedString.Builder().apply {
                        append(text.substring(0, lit - 1))
                        withStyle(SpanStyle(color = DroshPrimary)) { append(litChar) }
                        if (lit < text.length) append(text.substring(lit))
                    }.toAnnotatedString()
                }
            },
        )
    }
}
