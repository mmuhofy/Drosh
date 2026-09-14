package com.iris.irisshell.ui.setup.onboarding.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Fullscreen fake terminal used as the visual backdrop of every onboarding
 * scene.
 *
 * Why fake (not a real Termux-mount) — at onboarding time the terminal is not
 * yet attached to a session, and the proot'd Ubuntu rootfs hasn't been
 * bootstrapped. A Compose-canvas placeholder lets us sell the "show, don't
 * tell" experience without risking a half-mounted TermuxView.
 *
 * Rendering: a single Canvas draws every line at a fixed grid (col=COL_WIDTH,
 * row=ROW_HEIGHT). The cursor is a small rect underneath the line indicated
 * by [cursorPosition]. Blink alpha runs on a single infinite transition so
 * recomposition never desyncs the blink phase.
 */
@Composable
fun TerminalBackdrop(
    lines: List<String>,
    cursorPosition: CursorPos,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

    val infinite = rememberInfiniteTransition(label = "cursor-blink")
    val alpha by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursor-alpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SetupPalette.Background),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 80.dp),
        ) {
            val rowHeightPx = ROW_HEIGHT_DP.dp.toPx()

            var cursorX = 0f
            var cursorY = 0f

            lines.forEachIndexed { index, line ->
                val y = (index * rowHeightPx)
                if (line.isNotEmpty()) {
                    val result = measurer.measure(
                        text = line,
                        style = textStyle,
                        maxLines = 1,
                    )
                    drawText(
                        textLayoutResult = result,
                        topLeft = Offset(0f, y),
                    )
                    if (index == cursorPosition.line) {
                        cursorX = result.size.width.toFloat()
                        cursorY = y
                    }
                } else if (index == cursorPosition.line) {
                    cursorX = 0f
                    cursorY = y
                }
            }

            drawRect(
                color = SetupPalette.Text.copy(alpha = alpha),
                topLeft = Offset(cursorX, cursorY),
                size = Size(CURSOR_WIDTH_DP.dp.toPx(), rowHeightPx),
            )
        }

        Text(
            text = "iris@shell:~",
            style = textStyle.copy(color = SetupPalette.TextMuted),
            modifier = Modifier
                .padding(start = 24.dp, top = 28.dp),
        )
    }
}

/**
 * Grid coordinates for the cursor — see TerminalBackdrop doc.
 *
 * `column` is a hint for callers that want to position before the end of
 * the line. The Canvas uses the measured width of the line text to anchor
 * the cursor; callers may set column >=0 so the cursor offsets that many
 * cells from the line start.
 */
data class CursorPos(val line: Int, val column: Int = 0)

internal const val COL_WIDTH_DP = 9
internal const val ROW_HEIGHT_DP = 24
internal const val CURSOR_WIDTH_DP = 10

internal fun terminalTextStyle(fontSize: androidx.compose.ui.unit.TextUnit): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = fontSize,
        letterSpacing = 0.sp,
    )
