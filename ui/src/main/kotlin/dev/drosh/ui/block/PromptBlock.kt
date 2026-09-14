package dev.drosh.ui.block

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
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
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshMenuItemStyle
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockState
import dev.drosh.ui.DroshIcons

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
    var showThreeDot by rememberSaveable { mutableStateOf(false) }
    var showMenu by rememberSaveable { mutableStateOf(false) }
    val onDismissMenu = { showMenu = false; showThreeDot = false }

    val promptText = block.prompt.ifBlank { "$" }
    val outputColor = when (block.state) {
        is BlockState.Error -> DroshTextMuted
        else -> DroshText
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (!promptDir.isNullOrEmpty()) {
            SelectionContainer {
                Text(
                    text = promptDir,
                    color = DroshTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showThreeDot = true },
                    )
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionContainer {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = DroshPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                                append(promptText)
                                append(" ")
                            }
                            withStyle(SpanStyle(color = DroshPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)) {
                                append(block.command)
                            }
                        },
                        color = DroshText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (showThreeDot) {
                    Icon(
                        imageVector = DroshIcons.EllipsisVertical,
                        contentDescription = "Block menu",
                        tint = DroshTextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(onClick = { showMenu = true }),
                    )
                }
            }

            DroshDropdownMenu(
                expanded = showMenu,
                onDismissRequest = onDismissMenu,
                items = listOf(
                    DroshMenuItem(label = "Komutu kopyala", icon = DroshIcons.Copy),
                    DroshMenuItem(label = "Tekrar çalıştır", icon = DroshIcons.Play),
                    DroshMenuItem(label = "Komutu düzenle", icon = DroshIcons.Pencil),
                    DroshMenuItem(label = "Output'u kopyala", icon = DroshIcons.Copy, dividerBefore = true),
                    DroshMenuItem(label = "Dışa aktar", icon = DroshIcons.Download),
                    DroshMenuItem(label = "Block'u sil", icon = DroshIcons.Trash2, style = DroshMenuItemStyle.Destructive, dividerBefore = true),
                ),
                onItemClick = { item ->
                    onDismissMenu()
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
            SelectionContainer {
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
}

@Composable
fun PromptDivider(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(DroshBorderSubtle),
    )
}
