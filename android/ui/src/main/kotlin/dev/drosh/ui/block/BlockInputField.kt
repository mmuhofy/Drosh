package dev.drosh.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons

/**
 * The line editor at the bottom of block mode.
 *
 * [promptLabel] and [promptSuffix] show the shell prompt. When an interactive
 * program owns the terminal the caller passes [programPrompt] instead, which
 * swaps in that program's marker and drops the command wording: the line goes
 * to the program, not to a shell.
 *
 * ## Prediction
 *
 * [history] is this session's commands, most recent first. As a line is typed,
 * up to [PREDICTION_LIMIT] earlier commands that start with it are offered above
 * the field; tapping one fills the line in — it never runs it. The suggestions
 * are the user's own lines, so [predictionEnabled] decides only whether the
 * strip is drawn and the history consulted.
 *
 * They are hidden while an interactive program owns the terminal: a program's
 * input is not a shell line, and suggesting a command there would be wrong.
 */
@Composable
fun BlockInputField(
    onSubmit: (String) -> Unit,
    enabled: Boolean = true,
    promptLabel: String = "iris",
    promptSuffix: String = "$",
    programPrompt: String? = null,
    history: List<String> = emptyList(),
    predictionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    val predictions = remember(text, history, predictionEnabled, enabled, programPrompt) {
        if (predictionEnabled && enabled && programPrompt == null) {
            predictCommands(text, history)
        } else {
            emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (predictions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                predictions.forEach { suggestion ->
                    Text(
                        text = suggestion,
                        color = DroshTextMuted,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(DroshSurfaceVariant)
                            .clickable(enabled = enabled) { text = suggestion }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        PredictionInputRow(
            text = text,
            onTextChange = { text = it },
            enabled = enabled,
            focused = focused,
            onFocusedChange = { focused = it },
            promptLabel = promptLabel,
            promptSuffix = promptSuffix,
            programPrompt = programPrompt,
            onSubmit = {
                if (text.isNotBlank()) {
                    onSubmit(text)
                    text = ""
                }
            },
        )
    }
}

/** The prompt, the field and the send button — the row that was here before. */
@Composable
private fun PredictionInputRow(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    focused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    promptLabel: String,
    promptSuffix: String,
    programPrompt: String?,
    onSubmit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val marker = programPrompt
            Text(
                text = when {
                    marker != null -> if (focused) "$marker " else "$marker "
                    focused -> "$promptLabel$ ▸"
                    else -> "$promptLabel$promptSuffix"
                },
                color = DroshPrimary,
                fontFamily = LocalFontSet.current.mono,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        text = when {
                            !enabled -> "block mode disabled"
                            programPrompt != null -> "input…"
                            else -> "type a command…"
                        },
                        color = DroshTextMuted,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = 13.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { if (enabled) onTextChange(it) },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = DroshText,
                        fontFamily = LocalFontSet.current.mono,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    cursorBrush = SolidColor(DroshPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onFocusedChange(it.isFocused) },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { onSubmit() },
                    ),
                )
            }
            if (text.isNotBlank() && enabled) {
                IconButton(
                    onClick = onSubmit,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.ArrowUp,
                        contentDescription = "Send",
                        tint = DroshPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Box(modifier = Modifier.size(32.dp))
            }
        }
    }
}
