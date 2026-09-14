package com.iris.irisshell.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.irisshell.design.system.DroshBuild
import com.iris.irisshell.design.system.DroshDropdownMenu
import com.iris.irisshell.design.system.DroshError
import com.iris.irisshell.design.system.DroshMenuItem
import com.iris.irisshell.design.system.DroshMenuItemStyle
import com.iris.irisshell.design.system.DroshOutline
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSuccess
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.domain.block.Block
import com.iris.irisshell.domain.block.BlockState
import com.iris.irisshell.ui.DroshIcons

@Composable
fun BlockCard(
    block: Block,
    isActive: Boolean,
    onCopy: () -> Unit,
    onCopyCommand: () -> Unit,
    onCopyOutput: () -> Unit,
    onRerun: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onToggleCollapse: () -> Unit,
    onUrlClick: (String) -> Unit = {},
    searchQuery: String? = null,
    isCurrentMatchBlock: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var contextOpen by remember { mutableStateOf(false) }
    val accentColor = when (block.state) {
        is BlockState.Success -> if (isActive) DroshPrimary else DroshSuccess
        is BlockState.Error -> if (isActive) DroshPrimary else DroshError
        BlockState.Running -> DroshBuild
        else -> DroshOutline
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DroshSurface, RoundedCornerShape(6.dp))
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(accentColor.copy(alpha = if (isActive) 0.8f else 0.6f)),
            )
            Column(modifier = Modifier.fillMaxWidth().padding(start = 10.dp)) {
                BlockHeader(
                    block = block,
                    onCopy = onCopy,
                    onToggleCollapse = onToggleCollapse,
                )
                BlockBody(
                    block = block,
                    onLongClick = { contextOpen = true },
                    onUrlClick = onUrlClick,
                    searchQuery = searchQuery,
                    isCurrentMatchBlock = isCurrentMatchBlock,
                )
            }
        }

        DroshDropdownMenu(
            expanded = contextOpen,
            onDismissRequest = { contextOpen = false },
            items = listOf(
                DroshMenuItem(
                    label = "Komutu kopyala",
                    icon = DroshIcons.Copy,
                ),
                DroshMenuItem(
                    label = "Tekrar çalıştır",
                    icon = DroshIcons.Play,
                ),
                DroshMenuItem(
                    label = "Komutu düzenle",
                    icon = DroshIcons.Pencil,
                ),
                DroshMenuItem(
                    label = "Output'u kopyala",
                    icon = DroshIcons.Copy,
                    dividerBefore = true,
                ),
                DroshMenuItem(
                    label = "Dışa aktar",
                    icon = DroshIcons.Download,
                ),
                DroshMenuItem(
                    label = "Block'u sil",
                    icon = DroshIcons.Trash2,
                    style = DroshMenuItemStyle.Destructive,
                    dividerBefore = true,
                ),
            ),
            onItemClick = { item ->
                when (item.label) {
                    "Komutu kopyala" -> onCopyCommand()
                    "Tekrar çalıştır" -> onRerun()
                    "Komutu düzenle" -> onEdit()
                    "Output'u kopyala" -> onCopyOutput()
                    "Dışa aktar" -> onExport()
                    "Block'u sil" -> onDelete()
                }
            },
        )
    }
}
