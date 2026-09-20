package dev.drosh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceContainerLowest
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.DroshIcons

/**
 * Interactive MOTD (Message of the Day) widget rendered in Block Mode.
 *
 * Shown when [MotdMode] is `Compose`: the shell does NOT echo the MOTD;
 * this Composable replaces it with a styled, dismissible card containing:
 *
 *  - the custom MOTD text (monospace, terminal-style)
 *  - action buttons (Agent, Yardım)
 *  - system info (version, RAM, storage)
 *
 * The widget is dismissed via the close button and will not reappear until
 * a new session becomes active (the caller resets [dismissed] on session
 * switch).
 *
 * Inspired by: Warp's welcome banner + system info overlay concept.
 */
@Composable
fun MotdWidget(
    motdText: String,
    systemInfo: SystemInfo?,
    onAgentClick: () -> Unit,
    onHelpClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    if (motdText.isBlank()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DroshSurface, RoundedCornerShape(16.dp))
                .border(
                    width = 1.dp,
                    color = DroshPrimary.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(20.dp),
        ) {
            // ── Header: MOTD text ────────────────────────────────────────────────
            Text(
                text = motdText,
                color = DroshText,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            // ── System Info ────────────────────────────────────────────────────
            if (systemInfo != null) {
                Spacer(Modifier.height(16.dp))
                SystemInfoRow(info = systemInfo)
            }

            // ── Actions ────────────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            ActionButtons(
                onAgentClick = onAgentClick,
                onHelpClick = onHelpClick,
            )
        }

        // ── Close button ────────────────────────────────────────────────────────
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .padding(top = 4.dp, end = 4.dp),
        ) {
            Icon(
                imageVector = DroshIcons.X,
                contentDescription = "Dismiss MOTD",
                tint = DroshTextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun SystemInfoRow(info: SystemInfo) {
    val items = listOf(
        "v${info.version}" to DroshIcons.Info,
        "RAM: ${info.ramAvailable} / ${info.ramTotal}" to DroshIcons.Square,
        "Disk: ${info.storageAvailable} / ${info.storageTotal}" to DroshIcons.Square,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshSurfaceContainerLowest, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        items.forEach { (label, icon) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    color = DroshTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    onAgentClick: () -> Unit,
    onHelpClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ActionButton(
            label = "Agent",
            icon = DroshIcons.SquareTerminal,
            onClick = onAgentClick,
        )
        ActionButton(
            label = "Yardım",
            icon = DroshIcons.Info,
            onClick = onHelpClick,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .weight(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    pressed = true
                    onClick()
                },
            )
            .background(
                color = if (pressed) DroshPrimary.copy(alpha = 0.15f) else DroshSurfaceHigh,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = DroshPrimary.copy(alpha = 0.25f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DroshPrimary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = DroshPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = OutfitFontFamily,
        )
    }
}
