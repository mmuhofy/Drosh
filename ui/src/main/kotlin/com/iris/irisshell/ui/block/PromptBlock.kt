package com.iris.irisshell.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.IrisBorderSubtle
import com.iris.irisshell.design.system.IrisDropdownMenu
import com.iris.irisshell.design.system.IrisError
import com.iris.irisshell.design.system.IrisMenuItem
import com.iris.irisshell.design.system.IrisMenuItemStyle
import com.iris.irisshell.design.system.IrisPrimary
import com.iris.irisshell.design.system.IrisText
import com.iris.irisshell.design.system.IrisTextMuted
import com.iris.irisshell.design.system.IrisTextSecondary
import com.iris.irisshell.domain.block.Block
import com.iris.irisshell.domain.block.BlockState
import com.iris.irisshell.ui.IrisIcons

@Composable
fun PromptBlock(
    block: Block,
    promptDir: String? = null,
    modifier: Modifier = Modifier,
    onCopyCommand: () -> Unit = {},
    onCopyOutput: () -> Unit = {},
    onRerunCommand: (String) -> Unit = {},
    onEditCommand: (String) -> Unit = {},
    onExportOutput: () -> Unit = {},
    onDeleteBlock: () -> Unit = {},
) {
    val promptText = block.prompt.ifBlank { "$" }
    val outputColor = when (block.state) {
        is BlockState.Error -> IrisTextMuted
        else -> IrisText
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!promptDir.isNullOrEmpty()) {
            Text(
                text = promptDir,
                color = IrisTextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            var contextOpen by rememberSaveable { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { contextOpen = true },
                        onLongClick = { contextOpen = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = IrisPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                            append(promptText)
                            append(" ")
                        }
                        withStyle(SpanStyle(color = IrisPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                            append(block.command)
                        }
                    },
                    color = IrisText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                if (contextOpen) {
                    IconButton(
                        onClick = { /* menu handled below */ },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = IrisIcons.EllipsisVertical,
                            contentDescription = "Block menu",
                            tint = IrisTextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            IrisDropdownMenu(
                expanded = contextOpen,
                onDismissRequest = { contextOpen = false },
                items = listOf(
                    IrisMenuItem(label = "Komutu kopyala", icon = IrisIcons.Copy),
                    IrisMenuItem(label = "Tekrar çalıştır", icon = IrisIcons.Play),
                    IrisMenuItem(label = "Komutu düzenle", icon = IrisIcons.Pencil),
                    IrisMenuItem(label = "Output'u kopyala", icon = IrisIcons.Copy, dividerBefore = true),
                    IrisMenuItem(label = "Dışa aktar", icon = IrisIcons.Download),
                    IrisMenuItem(label = "Block'u sil", icon = IrisIcons.Trash2, style = IrisMenuItemStyle.Destructive, dividerBefore = true),
                ),
                onItemClick = { item ->
                    contextOpen = false
                    when (item.label) {
                        "Komutu kopyala" -> onCopyCommand()
                        "Tekrar çalıştır" -> onRerunCommand(block.command)
                        "Komutu düzenle" -> onEditCommand(block.command)
                        "Output'u kopyala" -> onCopyOutput()
                        "Dışa aktar" -> onExportOutput()
                        "Block'u sil" -> onDeleteBlock()
                    }
                },
            )
        }

        if (block.outputLines.isNotEmpty()) {
            Text(
                text = block.outputLines.joinToString("\n"),
                color = outputColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun PromptDivider(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(IrisBorderSubtle),
    )
}
