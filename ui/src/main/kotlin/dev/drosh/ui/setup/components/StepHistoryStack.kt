package dev.drosh.ui.setup.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.BootstrapStep
import dev.drosh.domain.terminal.StepState
import dev.drosh.ui.setup.label

@Composable
fun StepHistoryStack(
    progress: BootstrapProgress,
    modifier: Modifier = Modifier,
) {
    val ordered = listOf(
        BootstrapStep.Extracting,
        BootstrapStep.Configuring,
        BootstrapStep.InstallingPackages,
        BootstrapStep.InstallingOhMyZsh,
        BootstrapStep.Optimizing,
    )

    val completed = ordered.filter { step ->
        progress.stepStates[step] == StepState.Done
    }

    val activeStep = progress.currentStep.takeIf { it !in setOf(BootstrapStep.Idle, BootstrapStep.Failed) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (completed.isNotEmpty()) {
            Text(
                text = "Completed",
                color = DroshTextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            completed.forEachIndexed { index, step ->
                HistoryStepRow(step = step, label = step.label())
                if (index < completed.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        if (activeStep != null && activeStep != BootstrapStep.Ready) {
            if (completed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            ActiveStepCard(
                step = activeStep,
                message = progress.currentMessage,
                subSteps = progress.stepStates.mapKeys { it.key.label() },
                percent = progress.percent,
            )
        }
    }
}

@Composable
private fun HistoryStepRow(
    step: BootstrapStep,
    label: String,
    modifier: Modifier = Modifier,
) {
    val connectorAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(300),
        label = "history-connector",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepStateIcon(
            state = StepState.Done,
            size = 24.dp,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            color = DroshText,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun ActiveStepCard(
    step: BootstrapStep,
    message: String,
    subSteps: Map<String, StepState>,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DroshSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepStateIcon(
                    state = StepState.Active,
                    size = 28.dp,
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = step.label(),
                    color = DroshPrimary,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "$percent%",
                    color = DroshTextMuted,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                color = DroshTextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                ),
            )

            if (step == BootstrapStep.InstallingPackages) {
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DroshSurfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = percent.coerceIn(0, 100) / 100f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        DroshPrimary,
                                        DroshPrimary.copy(alpha = 0.7f),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}
