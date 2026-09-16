package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.ShellChoice
import androidx.compose.material3.TextButton

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
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(100.dp))

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
                ),
                color = DroshTextMuted,
            )

            Spacer(modifier = Modifier.height(32.dp))

            ShellSelectorCard(
                shell = ShellChoice.Zsh,
                isSelected = selectedShell == ShellChoice.Zsh,
                onSelect = { selectedShell = ShellChoice.Zsh },
            )
            Spacer(modifier = Modifier.height(12.dp))
            ShellSelectorCard(
                shell = ShellChoice.Bash,
                isSelected = selectedShell == ShellChoice.Bash,
                onSelect = { selectedShell = ShellChoice.Bash },
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                PageIndicator(activeIndex = 2, pageCount = 3)

                Spacer(
                    modifier = Modifier
                        .width(16.dp)
                        .height(48.dp),
                )

                TextButton(
                    onClick = { onStartSetup(selectedShell) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Start setup",
                        style = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = DroshOnPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellSelectorCard(
    shell: ShellChoice,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val backgroundColor = if (isSelected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant
    val borderColor = if (isSelected) DroshPrimary else Color.Transparent

    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = DroshText,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .background(
                    brush = if (isSelected) Brush.horizontalGradient(
                        colors = listOf(
                            DroshPrimary.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                    ) else null,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isSelected) DroshPrimary.copy(alpha = 0.12f) else DroshSurface,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (shell == ShellChoice.Zsh) DroshIcons.Terminal else DroshIcons.SquareTerminal,
                    contentDescription = null,
                    tint = if (isSelected) DroshPrimary else DroshTextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = when (shell) {
                        ShellChoice.Zsh -> "Zsh"
                        ShellChoice.Bash -> "Bash"
                    },
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    ),
                    color = if (isSelected) DroshPrimary else DroshText,
                )
                Text(
                    text = when (shell) {
                        ShellChoice.Zsh -> "Oh My Zsh + plugins included"
                        ShellChoice.Bash -> "Lightweight, no extras"
                    },
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    ),
                    color = DroshTextMuted,
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = DroshIcons.Check,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
