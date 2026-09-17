package dev.drosh.ui.setup.stages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.SetupWizardViewModel
import dev.drosh.ui.setup.components.PackageProfilePresetCard
import dev.drosh.ui.setup.components.PillButton
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator

@Composable
fun PackageSelectionStage(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val profile by viewModel.packageProfile.collectAsStateWithLifecycle()
    val shellChoice by viewModel.shellChoice.collectAsStateWithLifecycle()
    val customPackagesText by viewModel.customPackagesText.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .padding(horizontal = 28.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        WormPageIndicator(
            pageCount = 3,
            currentPage = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Packages",
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
            text = "Choose what to install during setup",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            ),
            color = DroshTextMuted,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            PackageProfilePresetCard(
                profile = PackageProfile.Minimal,
                isSelected = profile == PackageProfile.Minimal,
                onSelect = { viewModel.selectProfile(PackageProfile.Minimal) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PackageProfilePresetCard(
                profile = PackageProfile.Standard,
                isSelected = profile == PackageProfile.Standard,
                onSelect = {
                    viewModel.selectProfile(PackageProfile.Standard)
                    viewModel.setCustomPackagesText("")
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PackageProfilePresetCard(
                profile = PackageProfile.Full,
                isSelected = profile == PackageProfile.Full,
                onSelect = {
                    viewModel.selectProfile(PackageProfile.Full)
                    viewModel.setCustomPackagesText("")
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            PackageProfilePresetCard(
                profile = PackageProfile.Custom,
                isSelected = profile == PackageProfile.Custom,
                onSelect = { viewModel.selectProfile(PackageProfile.Custom) },
            )

            AnimatedVisibility(
                visible = profile == PackageProfile.Custom,
                enter = fadeIn(animationSpec = tween(220)) +
                    expandVertically(animationSpec = tween(280)),
                exit = fadeOut(animationSpec = tween(180)) +
                    shrinkVertically(animationSpec = tween(220)),
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Additional packages",
                        style = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = DroshTextMuted,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    var text by remember { mutableStateOf(customPackagesText) }
                    TextField(
                        value = text,
                        onValueChange = {
                            text = it
                            viewModel.setCustomPackagesText(it)
                        },
                        placeholder = {
                            Text(
                                text = "e.g. curl, jq, tmux",
                                style = TextStyle(
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 13.sp,
                                ),
                                color = DroshTextMuted,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = DroshSurfaceVariant,
                            focusedIndicatorColor = DroshPrimary,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = DroshPrimary,
                            textColor = DroshText,
                        ),
                        textStyle = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontSize = 14.sp,
                        ),
                        supportingText = {
                            Text(
                                text = "Comma-separated apt package names",
                                style = TextStyle(
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 11.sp,
                                ),
                                color = DroshTextMuted,
                            )
                        },
                        keyboardOptions = KeyboardOptions.Default,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

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
        Spacer(modifier = Modifier.height(10.dp))

        ShellSelectorCard(
            icon = DroshIcons.Terminal,
            label = "Zsh",
            subtitle = "Oh My Zsh + plugins included",
            isSelected = shellChoice == ShellChoice.Zsh,
            onSelect = { viewModel.selectShell(ShellChoice.Zsh) },
        )
        Spacer(modifier = Modifier.height(12.dp))
        ShellSelectorCard(
            icon = DroshIcons.SquareTerminal,
            label = "Bash",
            subtitle = "Lightweight, no extras",
            isSelected = shellChoice == ShellChoice.Bash,
            onSelect = { viewModel.selectShell(ShellChoice.Bash) },
        )

        Spacer(modifier = Modifier.weight(1f))

        PillButton(
            text = "Continue",
            onClick = {
                viewModel.startBootstrap()
                onNext()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun ShellSelectorCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
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
        modifier = modifier
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

            Column(modifier = Modifier.weight(1f)) {
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

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DroshIcons.Check,
                        contentDescription = null,
                        tint = DroshPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
