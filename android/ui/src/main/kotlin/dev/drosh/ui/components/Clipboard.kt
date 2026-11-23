package dev.drosh.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

@Composable
fun rememberAgentClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    return remember(clipboard) {
        { text: String -> clipboard.setText(AnnotatedString(text)) }
    }
}
