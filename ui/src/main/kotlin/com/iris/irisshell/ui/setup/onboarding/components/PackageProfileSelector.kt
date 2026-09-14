package com.iris.irisshell.ui.setup.onboarding.components

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurfaceVariant
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.design.system.DroshTextSecondary
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.domain.terminal.PackageProfile

/**
 * Radio-group selector for package profile (Minimal, Developer, Custom).
 *
 * When Custom is selected, expands to a 2-column checkbox grid of packages
 * the user can individually select.
 */
@Composable
fun PackageProfileSelector(
    selected: PackageProfile,
    customPackages: Set<String>,
    onProfileSelected: (PackageProfile) -> Unit,
    onCustomPackageToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Paket Profili",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
            color = DroshTextMuted,
        )
        Spacer(modifier = Modifier.height(10.dp))

        ProfileOption(
            label = "Minimal",
            subtitle = "~3 dk · zsh, git, curl, nano",
            selected = selected == PackageProfile.Minimal,
            onSelect = { onProfileSelected(PackageProfile.Minimal) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProfileOption(
            label = "Developer",
            subtitle = "~8 dk · + vim, python3, nodejs, htop, tree, wget",
            selected = selected == PackageProfile.Developer,
            onSelect = { onProfileSelected(PackageProfile.Developer) },
        )
        Spacer(modifier = Modifier.height(8.dp))
        ProfileOption(
            label = "Custom",
            subtitle = "Kendin seç",
            selected = selected == PackageProfile.Custom,
            onSelect = { onProfileSelected(PackageProfile.Custom) },
        )

        if (selected == PackageProfile.Custom) {
            Spacer(modifier = Modifier.height(12.dp))
            CustomPackageGrid(
                packages = CUSTOM_PACKAGES,
                selected = customPackages,
                onToggle = onCustomPackageToggled,
            )
        }
    }
}

@Composable
private fun ProfileOption(
    label: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = if (selected) DroshPrimary.copy(alpha = 0.08f) else DroshSurfaceVariant,
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
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = DroshPrimary,
                    unselectedColor = DroshTextSecondary,
                ),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
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
        }
    }
}

@Composable
private fun CustomPackageGrid(
    packages: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    val cols = 2
    val rows = (packages.size + cols - 1) / cols

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Paketler",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            ),
            color = DroshTextMuted,
        )
        Spacer(modifier = Modifier.height(8.dp))

        for (rowIndex in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (colIndex in 0 until cols) {
                    val idx = rowIndex * cols + colIndex
                    if (idx < packages.size) {
                        val pkg = packages[idx]
                        Box(modifier = Modifier.weight(1f)) {
                            PackageCheckboxItem(
                                label = pkg,
                                checked = selected.contains(pkg),
                                onCheckedChange = { onToggle(pkg) },
                            )
                        }
                    }
                }
            }
            if (rowIndex < rows - 1) Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun PackageCheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = DroshPrimary,
                uncheckedColor = DroshTextSecondary,
                checkmarkColor = Color.Black,
            ),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
            ),
            color = DroshText,
        )
    }
}

private val CUSTOM_PACKAGES = listOf(
    "vim", "python3", "nodejs", "ruby",
    "golang", "rust", "docker", "htop",
    "tmux", "tree", "ffmpeg", "imagemagick",
)
