package dev.drosh.ui.setup.stages

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BorderStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.SetupWizardViewModel

@Composable
fun PackageSelectionStage(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val shellChoice by viewModel.shellChoice.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(horizontal = 28.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Spacer(modifier = Modifier.height(32.dp))

        ShellOptionCard(
            icon = DroshIcons.Terminal,
            title = "Zsh",
            description = "Modern, feature-rich and highly customizable",
            badgeText = "Recommended",
            isSelected = shellChoice == ShellChoice.Zsh,
            onSelect = { viewModel.selectShell(ShellChoice.Zsh) },
        )

        Spacer(modifier = Modifier.height(12.dp))

        ShellOptionCard(
            icon = DroshIcons.SquareTerminal,
            title = "Bash",
            description = "Stable, reliable and widely compatible",
            badgeText = null,
            isSelected = shellChoice == ShellChoice.Bash,
            onSelect = { viewModel.selectShell(ShellChoice.Bash) },
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Shell",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
            ),
            color = DroshTextMuted,
            modifier = Modifier.align(Alignment.Start),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.startBootstrap()
                onNext()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DroshPrimary,
            ),
        ) {
            Text(
                text = "Continue",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshOnPrimary,
            )
            }
    }
}

@Composable
private fun ShellOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    badgeText: String?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) DroshPrimary else DroshBorderSubtle,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
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
                    tint = if (isSelected) DroshPrimary else DroshTextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = if (isSelected) DroshPrimary else DroshText,
                    )
                    badgeText?.let { badge ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = DroshPrimary,
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = badge,
                                style = TextStyle(
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                ),
                                color = DroshOnPrimary,
                            )
                        }
                    }
                }

                Text(
                    text = description,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = DroshTextMuted,
                )
            }

            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.radioButtonColors(
                    selectedColor = DroshPrimary,
                    unselectedColor = DroshTextMuted,
                ),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
