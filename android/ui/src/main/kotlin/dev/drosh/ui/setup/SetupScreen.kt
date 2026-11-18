package dev.drosh.ui.setup

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.BootstrapStep
import dev.drosh.domain.terminal.StepState
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.setup.components.LiveLogCard
import dev.drosh.ui.setup.components.StepHistoryStack

/**
 * The second of two onboarding screens: the one-time install, shown while it
 * runs.
 *
 * It is not a wizard page with choices. "Get started" on the welcome screen has
 * already triggered the shell-script pipeline; this screen only reports it —
 * extract the Ubuntu rootfs, prepare bash, write the completion marker. The
 * flow leaves for the terminal on its own when the bootstrap says ready, and
 * the recovery route handles failure.
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val logs by viewModel.liveLogs.collectAsStateWithLifecycle()
    val logDrawerOpen by viewModel.isLogDrawerOpen.collectAsStateWithLifecycle()

    SetupContent(
        progress = progress,
        logs = logs,
        logDrawerOpen = logDrawerOpen,
        onToggleLogDrawer = viewModel::toggleLogDrawer,
        modifier = modifier,
    )
}

@Composable
private fun SetupContent(
    progress: BootstrapProgress,
    logs: List<String>,
    logDrawerOpen: Boolean,
    onToggleLogDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(DroshBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp)
                .padding(top = 52.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = DroshIcons.Terminal,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.welcome_brand),
                    style = TextStyle(
                        fontFamily = LocalFontSet.current.sans,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        letterSpacing = 2.sp,
                    ),
                    color = DroshText,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.setup_title),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    letterSpacing = 0.4.sp,
                ),
                color = DroshText,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.setup_subtitle),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                ),
                color = DroshTextSecondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = progress.currentMessage,
                style = TextStyle(
                    fontFamily = LocalFontSet.current.mono,
                    fontSize = 12.sp,
                ),
                color = DroshPrimary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            ProgressBar(progress = progress)

            Spacer(modifier = Modifier.height(20.dp))

            StepHistoryStack(progress = progress)

            Spacer(modifier = Modifier.weight(1f))

            LiveLogCard(
                lines = logs,
                expanded = logDrawerOpen,
                onToggleOpen = onToggleLogDrawer,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.setup_keep_open_note),
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                ),
                color = DroshTextMuted,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ProgressBar(progress: BootstrapProgress, modifier: Modifier = Modifier) {
    val pct = progress.percent.coerceIn(0, 100)
    val etaSec = (progress.estimatedRemainingMs ?: 0L) / 1000L

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(DroshBorderSubtle.copy(alpha = 0.4f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = pct / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(DroshPrimary),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$pct%",
                style = TextStyle(
                    fontFamily = LocalFontSet.current.mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = DroshTextSecondary,
            )
            Text(
                text = when {
                    progress.isReady -> stringResource(R.string.setup_progress_done)
                    etaSec <= 0L -> stringResource(R.string.setup_progress_finalizing)
                    else -> stringResource(R.string.setup_progress_eta, etaSec)
                },
                style = TextStyle(
                    fontFamily = LocalFontSet.current.sans,
                    fontSize = 12.sp,
                ),
                color = DroshTextMuted,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    SetupContent(
        progress = BootstrapProgress(
            currentStep = BootstrapStep.Configuring,
            stepStates = mapOf(
                BootstrapStep.Extracting to StepState.Done,
                BootstrapStep.Configuring to StepState.Active,
                BootstrapStep.Optimizing to StepState.Pending,
            ),
            percent = 45,
            estimatedRemainingMs = 22_000L,
            currentMessage = "Preparing bash...",
        ),
        logs = listOf("→ Extracting Ubuntu rootfs…", "✓ Rootfs extracted."),
        logDrawerOpen = false,
        onToggleLogDrawer = {},
    )
}
