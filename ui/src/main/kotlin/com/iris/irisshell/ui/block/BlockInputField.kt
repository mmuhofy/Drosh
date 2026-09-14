package com.iris.irisshell.ui.block

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.ui.DroshIcons

@Composable
fun BlockInputField(
    onSubmit: (String) -> Unit,
    enabled: Boolean = true,
    promptLabel: String = "iris",
    promptSuffix: String = "$",
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (focused) "$promptLabel$ ▸" else "$promptLabel$promptSuffix",
                color = DroshPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(
                        text = if (enabled) "type a command…" else "block mode disabled",
                        color = DroshTextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { if (enabled) text = it },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = DroshText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    ),
                    cursorBrush = SolidColor(DroshPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (text.isNotBlank()) {
                                onSubmit(text)
                                text = ""
                            }
                        },
                    ),
                )
            }
            if (text.isNotBlank() && enabled) {
                IconButton(
                    onClick = {
                        onSubmit(text)
                        text = ""
                    },
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
