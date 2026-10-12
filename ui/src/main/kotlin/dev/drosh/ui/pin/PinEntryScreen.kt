package dev.drosh.ui.pin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary

/**
 * PIN entry, on the screen's own keypad.
 *
 * No text field and no software keyboard: a keypad is what a phone user
 * expects for a number, it works with the keyboard dismissed, and the
 * hidden field that used to fight the IME for focus was the old screen's
 * whole approach. The dots show [pinLength] of them, so a six-digit PIN is
 * entered and read as one.
 */
@Composable
fun PinEntryScreen(
    title: String = "Enter PIN",
    subtitle: String? = null,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
    /** How many digits this screen waits for; the settings file carries it. */
    pinLength: Int = 4,
    onPinReady: (pin: String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    val onPinReadyState = rememberUpdatedState(onPinReady)

    // The keypad is the only way in, so the keyboard has no business being
    // up. The view is asked once, after it is attached: a hide requested
    // during composition is a request to a window that does not exist yet,
    // and the IME arrives anyway.
    val hostView = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        hostView.post { keyboard?.hide() }
    }

    LaunchedEffect(pin) {
        if (pin.length == pinLength) onPinReadyState.value(pin)
    }
    // The error is shown once and then it is the digits' turn: a rejected PIN
    // reddens the row and shakes it, and the next keypress clears both. Held
    // here rather than read straight off the parameter, because the caller's
    // error is a fact about the last attempt, not a state of the row — and a
    // row that stays red after the first mistake punishes every attempt after
    // it with the colour of the one that failed.
    var errorShowing by remember { mutableStateOf(errorMessage != null) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            // The digits leave too: with a full row nothing could grow past
            // pinLength to fire the submit again.
            pin = ""
            errorShowing = true
        } else {
            errorShowing = false
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
                length = pinLength,
                error = errorShowing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            AnimatedVisibility(visible = errorShowing && errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    color = DroshError,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            PinKeypad(
                onDigit = { digit ->
                    errorShowing = false
                    if (pin.length < pinLength) pin += digit
                },
                onDelete = {
                    errorShowing = false
                    if (pin.isNotEmpty()) pin = pin.dropLast(1)
                },
            )

            onCancel?.let { cancel ->
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Cancel",
                    color = DroshTextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = cancel),
                )
            }
        }
    }
}

/**
 * The dots, one per digit.
 *
 * They shake when [error] is up — a wrong PIN is a thing the hand feels as
 * much as it reads, and a silent redraw of the same dots was
 * indistinguishable from nothing happening at all.
 */
@Composable
private fun PinDotBoxes(
    pin: String,
    length: Int,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val shake by animateFloatAsState(
        targetValue = if (error) 12f else 0f,
        animationSpec = keyframes {
            durationMillis = 400
            0f at 0 using LinearEasing
            -14f at 60
            14f at 130
            -8f at 220
            8f at 300
            0f at 400
        },
        label = "pinShake",
    )
    Row(
        modifier = modifier.offset(x = shake.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(length) { index ->
            val filled = index < pin.length
            val dotColor by animateFloatAsState(
                targetValue = if (filled) 1f else 0f,
                animationSpec = tween(160),
                label = "dotFill$index",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .alpha(dotColor.coerceIn(0f, 1f))
                    .background(
                        color = if (error) DroshError.copy(alpha = 0.85f) else DroshPrimary,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .border(
                        border = BorderStroke(1.5.dp, if (error) DroshError else DroshOutline),
                        shape = RoundedCornerShape(8.dp),
                    ),
            )
        }
    }
}

/** A 3x4 pad: 1-9, an empty corner, 0, delete. */
@Composable
private fun PinKeypad(
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        ).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { digit ->
                    PinKey(
                        label = digit,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigit(digit)
                        },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The corner stays empty rather than growing a fourth action: a
            // bottom row wider than the three above it reads as a mistake.
            Spacer(Modifier.weight(1f))
            PinKey(
                label = "0",
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDigit("0")
                },
            )
            PinKey(
                label = "⌫",
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val keyScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(90),
        label = "keyScale",
    )
    Box(
        modifier = modifier
            .aspectRatio(1.25f)
            .scale(keyScale)
            .background(DroshSurfaceVariant, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = DroshText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Two-step PIN setup flow (enter + confirm).
 *
 * The confirm step keeps its own error line: a mistyped PIN caught at the
 * second step is recoverable, and one caught at the lock screen is not.
 */
@Composable
fun PinSetupScreen(
    modifier: Modifier = Modifier,
    onPinSet: (pin: String) -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    var step by remember { mutableStateOf(SetupStep.Enter) }
    var pin1 by rememberSaveable { mutableStateOf("") }
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
                    subtitle = "Type the digits you want to lock the app with",
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
                            onPinSetState.value(entered)
                        } else {
                            error = "PINs do not match"
                        }
                    },
                    onCancel = onSkip,
                )
            }
        }
    }
}

private enum class SetupStep { Enter, Confirm }
