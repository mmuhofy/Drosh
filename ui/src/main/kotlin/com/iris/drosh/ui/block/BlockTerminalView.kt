package com.iris.irisshell.ui.block

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.irisshell.design.system.DroshBackground
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.domain.block.Block
import kotlinx.coroutines.flow.StateFlow

@Composable
fun BlockTerminalView(
    blocks: StateFlow<List<Block>>,
    onToggleCollapsed: (blockId: String) -> Unit,
    onCommandSubmitted: (prompt: String, command: String) -> Unit,
    onCopyCommand: (Block) -> Unit = {},
    onCopyOutput: (Block) -> Unit = {},
    onRerunCommand: (String) -> Unit = {},
    onEditCommand: (String) -> Unit = {},
    onExportOutput: (Block) -> Unit = {},
    onDeleteBlock: (String) -> Unit = {},
    promptLabel: String = "iris",
    onUrlClick: (String) -> Unit = {},
    searchQuery: String? = null,
    currentMatchBlockId: String? = null,
    modifier: Modifier = Modifier,
    extraBar: @Composable () -> Unit = {},
) {
    val list by blocks.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val shouldAutoScroll by remember(list.size) {
        derivedStateOf {
            list.isEmpty() || listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= list.size - 2
        }
    }
    LaunchedEffect(list.size) {
        if (shouldAutoScroll && list.isNotEmpty()) {
            listState.animateScrollToItem(list.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(DroshBackground)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
            ) {
                items(list, key = { it.id }) { block ->
                    PromptBlock(block = block, modifier = Modifier.padding(vertical = 2.dp))
                    if (block.id != list.lastOrNull()?.id) {
                        PromptDivider()
                    }
                }
            }
            val isScrolling = listState.isScrollInProgress
            if (isScrolling && list.size > 4) {
                SimpleScrollbar(
                    listState = listState,
                    itemCount = list.size,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 3.dp)
                        .fillMaxHeight()
                        .width(3.dp),
                )
            }
            JumpToBottom(
                visible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 < list.size - 2,
                listState = listState,
                targetIndex = list.size - 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp),
            )
        }
        BlockInputField(
            onSubmit = { cmd ->
                onCommandSubmitted("", cmd)
            },
            promptLabel = promptLabel,
        )
        extraBar()
    }
}

@Composable
private fun SimpleScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val info = listState.layoutInfo
    val total = itemCount.coerceAtLeast(1)
    val visible = info.visibleItemsInfo.size.coerceAtLeast(1).coerceAtMost(total)
    val thumbFraction = (visible.toFloat() / total).coerceIn(0.08f, 1f)
    val firstVisible = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    val progress = if (total - visible <= 0) 0f
    else (firstVisible.toFloat() / (total - visible)).coerceIn(0f, 1f)
    Box(modifier = modifier.background(DroshSurface.copy(alpha = 0.4f), RoundedCornerShape(2.dp))) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(thumbFraction)
                .padding(top = (progress * (1f - thumbFraction) * 320f).dp.coerceAtLeast(0.dp))
                .background(DroshPrimary.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
        )
    }
}
