package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.setup.onboarding.components.DroshLogo
import dev.drosh.ui.setup.onboarding.components.SetupButton
import dev.drosh.ui.setup.onboarding.components.SkipAnchor

/**
 * Scene 1 — Welcome.
 *
 * Hero: DroshLogo (stylized shell icon with cursor blink + float).
 * Body:  Tagline under the logo.
 * Action: "Başla →" button at the bottom.
 * Skip:   top-left SkipAnchor.
 *
 * No TerminalBackdrop — the new flow uses clean, minimalist surfaces.
 */
@Composable
fun WelcomeScene(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
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
            Spacer(modifier = Modifier.height(80.dp))

            DroshLogo(size = 96.dp, tint = DroshPrimary)

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Drosh",
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
                text = "Telefonunu bir Unix makinesi yap.\n" +
                    "Artık sonunda.",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextMuted,
            )

            Spacer(modifier = Modifier.weight(1f))

            SetupButton(
                text = "Başla →",
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }

        SkipAnchor(onSkip = onSkip)
    }
}
