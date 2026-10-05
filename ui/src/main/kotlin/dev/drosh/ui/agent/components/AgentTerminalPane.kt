package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.domain.agent.TerminalLine

/**
 * A read-only view of what the agent ran.
 *
 * Deliberately not a terminal: there is no prompt, no cursor, and nothing here
 * accepts input. It is a transcript of commands and their output, styled like a
 * terminal so it reads as one.
 *
 * The distinction is the point. A row that looks like an input and is not is
 * worse than no terminal at all — the user taps it, nothing happens, and the
 * surface stops being trustworthy. Everything here is text.
 */
@Composable
fun AgentTerminalPane(
    lines: List<TerminalLine>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        items(lines.size) { index ->
            TerminalRow(line = lines[index])
        }
    }
}

@Composable
private fun TerminalRow(line: TerminalLine) {
    when (line) {
        is TerminalLine.Divider -> {
            Spacer(Modifier.height(10.dp))
        }

        is TerminalLine.Command -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A filled block, the way a shell renders a prompt, rather than a
                // character that would need escaping in every font.
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(DroshSuccess),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = line.text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DroshSuccess,
                )
            }
        }

        is TerminalLine.Output -> {
            if (line.text.isEmpty()) return
            Text(
                text = line.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp),
            )
        }

        is TerminalLine.Status -> {
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (line.failed && line.code < 0) "✕ failed" else "✓ exit ${line.code}",
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = if (line.failed) DroshError else DroshTextMuted,
                modifier = Modifier.padding(start = 15.dp),
            )
        }
    }
}

/** Shown when the agent has not run anything yet. */
@Composable
fun AgentTerminalEmpty(
    modifier: Modifier = Modifier,
    running: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (running) "çalışıyor…" else "henüz komut çalıştırılmadı",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = DroshTextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (running) {
                "çıktı burada belirir"
            } else {
                "agent bir komut çalıştırdığında burada görünür"
            },
            fontSize = 11.sp,
            color = DroshOutline,
        )
    }
}

/** Which pane of an agent chat is showing. */
enum class AgentPane { CHAT, TERMINAL }

/**
 * Two-pane switch.
 *
 * A segmented row rather than a tab bar: there are two views of the same thing,
 * not two destinations, and a full-width bar with an underline would imply
 * somewhere else to go. Claude Code's is a small toggle for the same reason.
 */
@Composable
fun AgentPaneToggle(
    pane: AgentPane,
    onChange: (AgentPane) -> Unit,
    showTerminal: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PaneTab(
            label = "Sohbet",
            selected = pane == AgentPane.CHAT,
            onClick = { onChange(AgentPane.CHAT) },
            modifier = Modifier.weight(1f),
        )
        if (showTerminal) {
            PaneTab(
                label = "Terminal",
                selected = pane == AgentPane.TERMINAL,
                onClick = { onChange(AgentPane.TERMINAL) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PaneTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DroshSurfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) DroshText else DroshTextMuted,
        )
    }
}
