package dev.drosh.ui.setup.stages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.components.PillButton
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator

@Composable
fun SetupOverviewStage(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                currentPage = 0,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(36.dp))

            Icon(
                imageVector = DroshIcons.Terminal,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(64.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Drosh",
                color = DroshText,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                ),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Your phone is a Unix machine. Finally.",
                color = DroshTextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 14.sp,
                ),
            )

            Spacer(modifier = Modifier.height(40.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SummaryRow(
                    icon = DroshIcons.Globe,
                    label = "Ubuntu",
                    value = "24.04 LTS",
                    onClick = { },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SummaryRow(
                    icon = DroshIcons.SquareTerminal,
                    label = "Shell",
                    value = "Zsh",
                    onClick = { },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SummaryRow(
                    icon = DroshIcons.Package,
                    label = "Packages",
                    value = "Standard profile",
                    onClick = onNext,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            PillButton(
                text = "Start setup",
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = DroshSurface,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(1.dp, DroshBorderSubtle, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = DroshSurface,
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = label,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
                color = DroshTextSecondary,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = value,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                ),
                color = DroshText,
            )
        }
    }
}
