package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.DroshIcons
import androidx.compose.material3.TextButton

@Composable
fun AboutScene(
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
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(100.dp))

            Text(
                text = "More than a terminal",
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
                text = "A Unix environment on your phone — Zsh, Bash, and the tools you already know.",
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

            FeatureItem(
                icon = DroshIcons.Highlight,
                label = "Full Zsh + Oh My Zsh",
                iconTint = DroshPrimary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            FeatureItem(
                icon = DroshIcons.Package,
                label = "Install packages (apt)",
                iconTint = DroshPrimary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            FeatureItem(
                icon = DroshIcons.Code,
                label = "vim, git, curl, and more",
                iconTint = DroshTextSecondary,
                comingSoon = true,
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                PageIndicator(activeIndex = 1, pageCount = 3)

                TextButton(
                    onClick = onNext,
                    modifier = Modifier
                        .height(48.dp)
                        .padding(start = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Next",
                        style = TextStyle(
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        ),
                        color = DroshOnPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = DroshIcons.ArrowRight,
                        contentDescription = null,
                        tint = DroshOnPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    comingSoon: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = DroshSurfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
            ),
            color = if (comingSoon) DroshTextMuted else DroshText,
        )

        if (comingSoon) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = DroshTextSecondary, fontSize = 11.sp)) {
                        append("Coming soon")
                    }
                },
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
