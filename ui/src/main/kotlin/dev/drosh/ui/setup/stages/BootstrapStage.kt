package dev.drosh.ui.setup.stages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.ui.setup.SetupWizardViewModel
import dev.drosh.ui.setup.components.LiveLogCard
import dev.drosh.ui.setup.components.PillButton
import dev.drosh.ui.setup.components.StepHistoryStack
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator

@Composable
fun BootstrapStage(
    onReady: () -> Unit,
    onSetupFailed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val liveLogs by viewModel.liveLogs.collectAsStateWithLifecycle()
    val isLogDrawerOpen by viewModel.isLogDrawerOpen.collectAsStateWithLifecycle()

    if (progress.isFailed) {
        onSetupFailed()
        return
    }

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
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            WormPageIndicator(
                pageCount = 3,
                currentPage = 2,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Installing & configuring",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp,
                ),
                color = DroshText,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = progress.currentMessage,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = DroshPrimary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            StepHistoryStack(progress = progress)

            Spacer(modifier = Modifier.height(16.dp))

            OverallProgressBlock(progress = progress)

            Spacer(modifier = Modifier.height(12.dp))

            if (progress.isReady) {
                PillButton(
                    text = "Continue to terminal",
                    onClick = onReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                )
            }

            LiveLogCard(
                lines = liveLogs,
                expanded = isLogDrawerOpen,
                onToggleOpen = { viewModel.toggleLogDrawer() },
            )
        }
    }
}

@Composable
private fun OverallProgressBlock(
    progress: BootstrapProgress,
    modifier: Modifier = Modifier,
) {
    val pct = progress.percent.coerceIn(0, 100)
    val etaSec = (progress.estimatedRemainingMs ?: 0L) / 1000L

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(DroshBackground.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct / 100f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(DroshPrimary),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$pct%",
                color = DroshTextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = when {
                    progress.isReady -> "Done"
                    etaSec <= 0L -> "Finalizing…"
                    else -> "~$etaSec s left"
                },
                color = DroshTextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
