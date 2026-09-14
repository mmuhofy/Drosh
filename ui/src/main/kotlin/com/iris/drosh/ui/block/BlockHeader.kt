package com.iris.irisshell.ui.block

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshTextSecondary
import com.iris.irisshell.domain.block.Block
import com.iris.irisshell.domain.block.BlockState
import com.iris.irisshell.domain.block.NetworkDelta
import com.iris.irisshell.ui.DroshIcons

@Composable
fun BlockHeader(
    block: Block,
    onCopy: () -> Unit,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExitLabel(block)
        Spacer(Modifier.width(6.dp))
        DurationLabel(block)
        if (block.networkDelta.hasTraffic) {
            Spacer(Modifier.width(6.dp))
            NetworkLabel(block.networkDelta)
        }
        Spacer(modifier = Modifier.weight(1f))
        CopyButton(onCopy)
        Spacer(Modifier.width(2.dp))
        CollapseButton(block.isCollapsed, onToggleCollapse)
    }
}

@Composable
private fun ExitLabel(block: Block) {
    val state = block.state
    val isRunning = state is BlockState.Running
    val (label, color) = when (state) {
        BlockState.Running -> "running" to Color(0xFF3B82F6)
        is BlockState.Success -> "exit ${state.exitCode}" to Color(0xFF22C55E)
        is BlockState.Error -> "exit ${state.exitCode}" to Color(0xFFEF4444)
        BlockState.Cancelled -> "cancelled" to Color(0xFFA0A0A0)
        BlockState.Idle -> "idle" to Color(0xFF787878)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isRunning) {
            RunningDot(color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun RunningDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "running-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

@Composable
private fun DurationLabel(block: Block) {
    val text = formatDuration(block.elapsedMs(System.currentTimeMillis()))
    Text(
        text = text,
        color = DroshTextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun NetworkLabel(delta: NetworkDelta) {
    val text = buildString {
        if (delta.rxBytes > 0) append("↓ ${formatBytes(delta.rxBytes)}")
        if (delta.rxBytes > 0 && delta.txBytes > 0) append("  ")
        if (delta.txBytes > 0) append("↑ ${formatBytes(delta.txBytes)}")
    }
    Text(
        text = text,
        color = DroshTextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun CopyButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 18.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DroshIcons.Copy,
            contentDescription = "Copy",
            tint = DroshTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun CollapseButton(isCollapsed: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 18.dp),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = DroshIcons.ChevronDown,
            contentDescription = if (isCollapsed) "Expand" else "Collapse",
            tint = DroshTextSecondary.copy(alpha = 0.7f),
            modifier = Modifier
                .size(18.dp)
                .rotate(if (isCollapsed) -90f else 0f),
        )
    }
}

private fun formatDuration(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val sec = ms / 1000
    if (sec < 60) return "${sec}s"
    val min = sec / 60
    val remSec = sec % 60
    return if (min < 60) "${min}m ${remSec}s"
    else {
        val hr = min / 60
        val remMin = min % 60
        "${hr}h ${remMin}m"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.1f GB", gb)
}
