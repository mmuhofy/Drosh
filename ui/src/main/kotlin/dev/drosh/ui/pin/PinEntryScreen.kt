package dev.drosh.ui.pin

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary

/**
 * Modern minimalist 4-digit PIN UI.
 *
 * - Dot-style filled boxes with outline
 * - Hidden numeric input field (transparent text + password transform)
 * - Focus auto-requested
 * - onPinReady fires once when 4 digits entered
 */
@Composable
fun PinEntryScreen(
    title: String = "Enter PIN",
    subtitle: String? = null,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
    onPinReady: (pin: String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var pin by rememberSaveable { mutableStateOf("") }

    val onPinReadyState = rememberUpdatedState(onPinReady)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(pin) {
        if (pin.length == 4) {
            keyboardController?.hide()
            onPinReadyState.value(pin)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = DroshTextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(28.dp))

            PinDotBoxes(
                pin = pin,
                length = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            HiddenPinField(
                pin = pin,
                onPinChange = { newPin ->
                    if (newPin.length <= 4 && newPin.all { it.isDigit() }) {
                        pin = newPin
                    } else if (newPin.isEmpty()) {
                        pin = ""
                    }
                },
                focusRequester = focusRequester,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0f),
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = DroshError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            onCancel?.let { cancel ->
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Cancel",
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable(onClick = cancel),
                )
            }
        }
    }
}

@Composable
private fun PinDotBoxes(
    pin: String,
    length: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(length) { index ->
            val filled = index < pin.length
            val dotColor by animateColorAsState(
                targetValue = if (filled) DroshPrimary else Color.Transparent,
                animationSpec = tween(200),
                label = "dotColor",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        color = dotColor,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .border(
                        border = BorderStroke(1.5.dp, if (filled) DroshPrimary else DroshOutline),
                        shape = RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
}

@Composable
private fun HiddenPinField(
    pin: String,
    onPinChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TextField(
        value = pin,
        onValueChange = onPinChange,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = TextStyle(
            color = Color.Transparent,
            fontSize = 24.sp,
        ),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            autoCorrect = false,
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        singleLine = true,
        maxLines = 1,
    )
}

/**
 * Two-step PIN setup flow (enter + confirm).
 * Uses [PinEntryScreen] twice; shows error inline if PINs differ.
 */
@Composable
fun PinSetupScreen(
    modifier: Modifier = Modifier,
    onPinSet: (pin: String) -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var step by remember { mutableStateOf(SetupStep.Enter) }
    var pin1 by rememberSaveable { mutableStateOf("") }
    var pin2 by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val onPinSetState = rememberUpdatedState(onPinSet)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            SetupStep.Enter -> {
                PinEntryScreen(
                    title = "Set a PIN",
                    subtitle = "4-digit PIN to lock the app",
                    modifier = Modifier.weight(1f),
                    onPinReady = { entered ->
                        pin1 = entered
                        step = SetupStep.Confirm
                        error = null
                    },
                    onCancel = onSkip,
                )
            }
            SetupStep.Confirm -> {
                PinEntryScreen(
                    title = "Confirm PIN",
                    subtitle = "Re-enter your PIN",
                    errorMessage = error,
                    modifier = Modifier.weight(1f),
                    onPinReady = { entered ->
                        if (entered == pin1) {
                            keyboardController?.hide()
                            onPinSetState.value(entered)
                        } else {
                            error = "PINs do not match"
                            pin2 = ""
                        }
                    },
                    onCancel = onSkip,
                )
            }
        }
    }
}

private enum class SetupStep {
    Enter,
    Confirm,
}
