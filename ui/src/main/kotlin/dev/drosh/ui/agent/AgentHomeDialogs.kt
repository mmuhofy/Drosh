package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.AgentChat
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.TOUCH_TARGET

/**
 * Rename a chat.
 *
 * A dialog rather than an inline field: the row shows a preview and a status, and
 * turning the name into an editable field in place means the preview and the status
 * have to disappear while editing and come back correctly after.
 */
@Composable
fun RenameChatDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurface,
        titleContentColor = DroshText,
        textContentColor = DroshTextSecondary,
        title = { Text("Chat adı", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    text = "Bu ad Agent Home listesinde ve oturumda görünür.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = DroshTextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TOUCH_TARGET)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DroshSurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = initial,
                            fontSize = 14.sp,
                            color = DroshTextMuted,
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = DroshText),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Chat adı" },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                // Renaming to nothing would read as a delete, which is not what the
                // button says.
                enabled = name.isNotBlank(),
            ) {
                Text("Kaydet", color = if (name.isNotBlank()) DroshPrimary else DroshTextMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Vazgeç", color = DroshTextSecondary)
            }
        },
    )
}

/**
 * Confirm deleting a chat.
 *
 * Names the chat and says what goes with it. A delete whose consequence is not
 * stated reads as a button that might be something else, and the transcript is
 * not recoverable.
 */
@Composable
fun DeleteChatDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurface,
        titleContentColor = DroshText,
        textContentColor = DroshTextSecondary,
        title = {
            Text(
                text = "Bu sohbet silinsin mi?",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Text(
                text = "\"$name\" ve tüm konuşma geçmişi kalıcı olarak silinecek. Bu geri alınamaz.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Sil", color = DroshError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Vazgeç", color = DroshTextSecondary)
            }
        },
    )
}

/**
 * The menu a long press opens, pinned under the row.
 *
 * Rendered in the screen's own layer rather than as a popup so it cannot be
 * dismissed by a tap that lands outside it, which on a list this dense would
 * swallow the tap the user aimed at the row behind it.
 */
@Composable
fun RowContextMenu(
    chat: AgentChat,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .padding(top = 120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DroshSurface)
                .padding(4.dp),
        ) {
            Text(
                text = chat.name,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextMuted,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            MenuRow(label = "Yeniden adlandır", tint = DroshText, onClick = onRename)
            MenuRow(label = "Sil", tint = DroshError, onClick = onDelete)
        }
    }
}

@Composable
private fun MenuRow(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = tint,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The working directory new chats start in.
 *
 * Editable in place rather than behind a settings screen, because it is per-task
 * and almost always the same value — a trip to Settings to change it each time
 * would be the difference between a one-tap task and a four-tap one.
 */
@Composable
fun DirectoryChip(
    directory: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(directory) { mutableStateOf(directory) }

    if (editing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DroshSurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DroshText,
                    ),
                    cursorBrush = SolidColor(DroshPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Çalışma dizini" },
                )
            }
            Spacer(Modifier.width(6.dp))
            FlatButton(
                text = "Kaydet",
                onClick = {
                    onChange(draft)
                    editing = false
                },
                modifier = Modifier.width(72.dp),
            )
        }
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DroshSurfaceLow)
            .clickable { editing = true }
            .semantics { contentDescription = "Çalışma dizini: $directory. Değiştirmek için dokun." }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            text = directory,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshTextMuted,
            maxLines = 1,
        )
    }
}
