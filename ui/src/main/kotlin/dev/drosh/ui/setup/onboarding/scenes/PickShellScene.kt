package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator

@Composable
fun PickShellScene(
    onBack: () -> Unit,
    onStartSetup: (ShellChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedShell by remember { mutableStateOf(ShellChoice.Zsh) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            WormPageIndicator(
                pageCount = 3,
                currentPage = 2,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Pick your shell",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = DroshText,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Zsh comes with Oh My Zsh and plugins. Bash is lightweight and familiar.",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextMuted,
            )

            Spacer(modifier = Modifier.height(40.dp))

            ShellSelectorCard(
                icon = DroshIcons.Terminal,
                label = "Zsh",
                subtitle = "Oh My Zsh + plugins included",
                isSelected = selectedShell == ShellChoice.Zsh,
                onSelect = { selectedShell = ShellChoice.Zsh },
            )
            Spacer(modifier = Modifier.height(12.dp))
            ShellSelectorCard(
                icon = DroshIcons.SquareTerminal,
                label = "Bash",
                subtitle = "Lightweight, no extras",
                isSelected = selectedShell == ShellChoice.Bash,
                onSelect = { selectedShell = ShellChoice.Bash },
            )

            Spacer(modifier = Modifier.weight(1f))

            PillButton(
                text = "Start setup",
                onClick = { onStartSetup(selectedShell) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun ShellSelectorCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = DroshSurface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) DroshPrimary else DroshTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = if (isSelected) DroshPrimary else DroshText,
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    ),
                    color = DroshTextMuted,
                )
            }
        }
    }
}
