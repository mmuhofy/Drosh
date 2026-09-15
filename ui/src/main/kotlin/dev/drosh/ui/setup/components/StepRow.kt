package dev.drosh.ui.setup.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.terminal.BootstrapStep
import dev.drosh.domain.terminal.StepState
import dev.drosh.ui.setup.theme.SetupPalette

/**
 * A single row in the setup stepper.
 *
 * Renders: [StepStateIcon] → title + optional subLine + 3dp connector line.
 *
 * The connector line colour is animated: blue if this step is done or active,
 * muted otherwise. Alpha animates from 0.3f → 1f based on step state.
 *
 * Per Drosh style — minimal, no card chrome. Each step is just text + a marker.
 *
 * @param showConnector  false for the last row to suppress trailing connector.
 */
@Composable
fun StepRow(
    step: BootstrapStep,
    title: String,
    subLine: String?,
    state: StepState,
    showConnector: Boolean,
    modifier: Modifier = Modifier,
) {
    val connectorAlpha by animateFloatAsState(
        targetValue = if (state == StepState.Done || state == StepState.Active) 1f else 0.3f,
        animationSpec = tween(300),
        label = "connector-alpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            StepStateIcon(state = state, size = 32.dp)
            if (showConnector) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .width(3.dp)
                        .height(38.dp)
                        .alpha(connectorAlpha)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    SetupPalette.Primary.copy(alpha = 0.5f),
                                    SetupPalette.SurfaceVariant.copy(alpha = 0.2f),
                                ),
                            ),
                        ),
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = when (state) {
                    StepState.Pending -> SetupPalette.TextMuted
                    StepState.Active -> SetupPalette.Primary
                    StepState.Done -> SetupPalette.Text
                    StepState.Failed -> SetupPalette.Error
                },
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 16.sp,
                    fontWeight = when (state) {
                        StepState.Active -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                ),
            )
            if (!subLine.isNullOrBlank() && state != StepState.Pending) {
                Text(
                    text = subLine,
                    color = SetupPalette.TextSecondary,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    maxLines = 2,
                )
            }
        }
    }
}
