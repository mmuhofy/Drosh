package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.components.PillButton

/**
 * The last page: the work is done, the terminal is one tap away.
 *
 * The bootstrap used to end inside its own progress page — a "Continue to
 * terminal" button appearing under the log drawer the moment the percentage
 * hit 100. That made the finish a state of the installing screen rather than
 * a screen of its own, and a user reading the logs could miss that anything
 * had happened. This page exists so the finish is visible: the flow advances
 * here on its own when the bootstrap reports ready, and the button is the one
 * remaining decision.
 *
 * There is deliberately nothing else to decide here. Everything the user
 * picked — the palette, which this page is about to write — was chosen on the
 * welcome page; this screen only says it is done.
 */
@Composable
fun TerminalReadyScene(
    onReady: () -> Unit,
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
            Spacer(modifier = Modifier.weight(1.2f))

            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(
                        color = DroshPrimary.copy(alpha = 0.15f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.Terminal,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Your terminal is ready",
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    letterSpacing = 0.5.sp,
                ),
                color = DroshText,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Ubuntu is installed and configured. The palette you picked is already saved — the terminal opens in it.",
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextSecondary,
            )

            Spacer(modifier = Modifier.weight(1f))

            PillButton(
                text = "Open terminal",
                onClick = onReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
    }
}
