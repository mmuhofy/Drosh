package com.iris.irisshell.ui.block

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iris.irisshell.ui.DroshIcons
import com.iris.irisshell.design.system.DroshBackground
import com.iris.irisshell.design.system.DroshBuild
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.design.system.DroshText
import kotlinx.coroutines.launch

@Composable
internal fun JumpToBottom(
    visible: Boolean,
    listState: LazyListState,
    targetIndex: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && targetIndex >= 0,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(180)),
        modifier = modifier,
    ) {
        val scope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(DroshBuild, CircleShape)
                .clickable {
                    scope.launch { listState.animateScrollToItem(targetIndex) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.ArrowDown,
                contentDescription = "Jump to bottom",
                tint = DroshText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
