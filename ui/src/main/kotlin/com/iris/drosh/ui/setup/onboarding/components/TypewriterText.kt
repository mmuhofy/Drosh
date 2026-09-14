package com.iris.irisshell.ui.setup.onboarding.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.iris.irisshell.ui.setup.onboarding.components.terminalTextStyle
import com.iris.irisshell.ui.setup.theme.SetupPalette
import kotlinx.coroutines.delay

/**
 * Typewriter-style animasyon — verilen [fullText] string'ini [charDelayMs]
 * başına harf harf ekrana basar. Animasyon tamamlanınca [onComplete] çağrılır.
 *
 * Cancellation: parent recomposition ya da component scope bitince
 * otomatik durur (LaunchedEffect cleanup).
 *
 * `onComplete` her recomposition'da aynı `key1` ile yeniden subscribe olur;
 * aynı sahne için tek fire garantili.
 */
@Composable
fun TypewriterText(
    fullText: String,
    charDelayMs: Long = 32L,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = terminalTextStyle(fontSize = 13.sp).copy(
        fontFamily = FontFamily.Monospace,
    ),
) {
    var renderedLength by remember(fullText) { mutableStateOf(0) }
    LaunchedEffect(fullText) {
        renderedLength = 0
        for (i in 1..fullText.length) {
            renderedLength = i
            delay(charDelayMs)
        }
        onComplete()
    }
    Text(
        text = fullText.take(renderedLength),
        color = SetupPalette.Text,
        style = textStyle.copy(fontWeight = FontWeight.Normal),
        modifier = modifier,
    )
}
