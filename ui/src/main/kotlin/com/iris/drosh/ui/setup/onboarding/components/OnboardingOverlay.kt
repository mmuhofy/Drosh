package com.iris.irisshell.ui.setup.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.iris.irisshell.design.system.OutfitFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Floating bottom banner shared by all onboarding scenes.
 *
 *   ──────── banner card (SurfaceVariant) ────────
 *   │  Caption text (muted, monospace)            │
 *   │  ┌──────────────┐                            │
 *   │  │   Continue   │                            │
 *   │  └──────────────┘                            │
 *
 * `Continue` calls [onContinue]; the caller decides what "Continue" means —
 * either advance scene or finish onboarding.
 */
@Composable
fun OnboardingOverlay(
    caption: String,
    continueLabel: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SetupPalette.Surface)
                .padding(20.dp),
        ) {
            Text(
                text = caption,
                color = SetupPalette.TextSecondary,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SetupPalette.Primary,
                    contentColor = SetupPalette.OnPrimary,
                ),
            ) {
                Text(
                    text = continueLabel,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

/**
 * Top-left Skip anchor — visible on every scene.
 *
 * Tap = [onSkip] (typically: finishOnboarding and route to bootstrap stepper).
 */
@Composable
fun SkipAnchor(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 44.dp, start = 24.dp, end = 24.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onSkip)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(SetupPalette.TextMuted),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Skip",
                    color = SetupPalette.TextMuted,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}
