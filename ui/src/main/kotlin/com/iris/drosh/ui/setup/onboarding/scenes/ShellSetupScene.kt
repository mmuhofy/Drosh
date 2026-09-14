package com.iris.irisshell.ui.setup.onboarding.scenes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshBackground
import com.iris.irisshell.design.system.DroshOnPrimary
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.design.system.DroshSurfaceVariant
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.design.system.DroshTextSecondary
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.ui.setup.onboarding.components.SetupButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sayfa 4 — Shell Setup (shown only when Zsh is selected).
 *
 * Displays a simulated "Oh My Zsh"-style boot sequence with step indicators
 * for cloning the repo, installing plugins, and generating the config.
 */
@Composable
fun ShellSetupScene(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var isSettingUp by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    val progressAnim by animateFloatAsState(
        targetValue = if (isSettingUp) 1f else 0f,
        animationSpec = tween(durationMillis = 800),
    )

    if (completed) {
        LaunchedEffect(Unit) {
            delay(500)
            onContinue()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Kabuk Kurulumu",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
            ),
            color = DroshText,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Oh My Zsh + zsh-autosuggestions + zsh-syntax-highlighting",
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            ),
            color = DroshTextMuted,
            modifier = Modifier.padding(horizontal = 28.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(16.dp),
            color = DroshSurface,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DroshSurfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progressAnim)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(DroshPrimary),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                SetupStep("Oh My Zsh klonlanıyor", 1, if (progressAnim >= 0.33f) SetupStepStatus.Ok else SetupStepStatus.Pending)
                Spacer(modifier = Modifier.height(12.dp))
                SetupStep("Eklentiler yükleniyor", 2, if (progressAnim >= 0.66f) SetupStepStatus.Ok else SetupStepStatus.Pending)
                Spacer(modifier = Modifier.height(12.dp))
                SetupStep(".zshrc oluşturuluyor", 3, if (progressAnim >= 1f) SetupStepStatus.Ok else SetupStepStatus.Pending)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        SetupButton(
            text = if (isSettingUp) "Hazırlanıyor..." else if (completed) "Tamam" else "Başlat",
            onClick = {
                if (!isSettingUp && !completed) {
                    isSettingUp = true
                    coroutineScope.launch {
                        delay(1200)
                        completed = true
                    }
                }
            },
            enabled = !isSettingUp && !completed,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

enum class SetupStepStatus { Pending, Ok }

@Composable
private fun SetupStep(label: String, stepNum: Int, status: SetupStepStatus) {
    val isDone = status == SetupStepStatus.Ok
    val circleBg = if (isDone) DroshPrimary else DroshSurfaceVariant
    val circleFg = if (isDone) DroshOnPrimary else DroshTextSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(circleBg),
        ) {
            if (isDone) {
                Text(
                    text = "\u2713",
                    color = circleFg,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    ),
                )
            } else {
                Text(
                    text = stepNum.toString(),
                    color = circleFg,
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
            ),
            color = if (isDone) DroshText else DroshTextMuted,
        )
    }
}
