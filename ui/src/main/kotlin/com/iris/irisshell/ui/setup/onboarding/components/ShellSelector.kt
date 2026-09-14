package com.iris.irisshell.ui.setup.onboarding.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurfaceVariant
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.domain.terminal.ShellChoice

/**
 * Radio-group selector for choosing a shell (Zsh or Bash).
 *
 * Each option shows:
 * - Radio dot (filled blue when selected)
 * - Shell name (bold)
 * - Optional description subtitle (muted)
 * - Optional "recommended" badge on Zsh
 */
@Composable
fun ShellSelector(
    selected: ShellChoice,
    onSelected: (ShellChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Shell",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = DroshTextMuted,
        )
        Spacer(modifier = Modifier.height(10.dp))

        ShellOption(
            label = "Zsh",
            subtitle = "Güçlü, plugin desteği, modern",
            recommended = true,
            selected = selected == ShellChoice.Zsh,
            onSelect = { onSelected(ShellChoice.Zsh) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ShellOption(
            label = "Bash",
            subtitle = "Klasik, evrensel, sade",
            recommended = false,
            selected = selected == ShellChoice.Bash,
            onSelect = { onSelected(ShellChoice.Bash) },
        )
    }
}

@Composable
private fun ShellOption(
    label: String,
    subtitle: String,
    recommended: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val bgColor = if (selected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant
    val borderColor = if (selected) DroshPrimary else DroshSurfaceVariant

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = bgColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(18.dp),
            ) {
                if (selected) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        drawCircle(color = DroshPrimary)
                    }
                } else {
                    Canvas(modifier = Modifier.size(18.dp)) {
                        drawArc(
                            color = DroshTextMuted.copy(alpha = 0.4f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 1.5f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    ),
                    color = DroshText,
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                    ),
                    color = DroshTextMuted,
                )
            }
            if (recommended) {
                Surface(
                    color = DroshPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(20.dp),
                ) {
                    Text(
                        text = "önerilen",
                        style = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                        ),
                        color = DroshPrimary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}