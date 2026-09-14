package com.iris.irisshell.ui.setup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Expandable live-log card.
 *
 * - Collapsed (default): a 44dp tall chip — status dot + label + line count + chevron.
 * - Expanded: a scrollable panel of mono-space log lines (max 320dp).
 *
 * Tail behavior: when new lines come in while expanded, we auto-scroll to
 * the bottom (unless the user has scrolled up — then we respect them).
 *
 * @param lines           Latest N lines from `BootstrapViewModel.liveLogs`.
 * @param expanded        Open / closed state (driven by `BootstrapViewModel.isLogDrawerOpen`).
 * @param onToggleOpen    Called when the user taps the header.
 */
@Composable
fun LiveLogCard(
    lines: List<String>,
    expanded: Boolean,
    onToggleOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header chip — always visible, 44dp tall.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SetupPalette.SurfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = SetupPalette.Outline,
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onToggleOpen)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val statusColor = if (lines.isNotEmpty()) SetupPalette.Success else SetupPalette.TextDisabled
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Live Log",
                color = SetupPalette.TextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${lines.size} ${if (lines.size == 1) "line" else "lines"}",
                color = SetupPalette.TextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                ),
            )

            val chevronRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = tween(280),
                label = "chevron-rotate",
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "▾",
                color = SetupPalette.TextMuted,
                style = TextStyle(fontSize = 14.sp),
                modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(220)) +
                expandVertically(animationSpec = tween(280)),
            exit = fadeOut(animationSpec = tween(180)) +
                shrinkVertically(animationSpec = tween(220)),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SetupPalette.Background)
                    .border(
                        width = 1.dp,
                        color = SetupPalette.Outline,
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                if (lines.isEmpty()) {
                    EmptyLogHint()
                } else {
                    LogScrollable(lines)
                }
            }
        }
    }
}

@Composable
private fun EmptyLogHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Waiting for logs…",
            color = SetupPalette.TextMuted,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
        )
    }
}

@Composable
private fun LogScrollable(lines: List<String>) {
    val state = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && !state.canScrollBackward) {
            state.animateScrollToItem(lines.size - 1)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        state = state,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = lines, key = { it.hashCode() }) { line ->
            Text(
                text = line,
                color = when {
                    line.startsWith("✓") -> SetupPalette.Success
                    line.startsWith("✗") || "FAILED" in line -> SetupPalette.Error
                    line.startsWith("⚠") -> SetupPalette.Warning
                    line.startsWith("→") -> SetupPalette.Primary
                    line.startsWith("    │") -> SetupPalette.MonoLog
                    else -> SetupPalette.TextSecondary
                },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

@Suppress("unused")
private val ChevronSpacer: Modifier = Modifier
