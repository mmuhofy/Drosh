package com.iris.irisshell.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.domain.input.ExtraKey
import com.iris.irisshell.domain.input.ExtraKeyBarLayout
import com.iris.irisshell.domain.input.InputIntent

/**
 * Popup shown over the keyboard when the user long-presses CTRL or ALT.
 * Lists the preset combos the user can pick — each tap fires
 * [onComboSelected] with the intent list that translates that combo.
 *
 * Anchor: rendered as a [Popup] above its anchor (the triggering
 * button). Dismiss is handled by the caller — we do not intercept
 * outside taps here so the popup stays open until the user picks or
 * the user explicitly dismisses (back gesture / outside-tap outside
 * this Popup).
 *
 * UNTESTED — verify on device.
 */
@Composable
fun ModifierPopup(
    modifier: ExtraKey.Special,
    onComboSelected: (List<InputIntent>) -> Unit,
    onDismiss: () -> Unit,
) {
    val combos = when (modifier) {
        ExtraKey.Special.CTRL -> ExtraKeyBarLayout.ctrlPopupCombos
        ExtraKey.Special.ALT -> ExtraKeyBarLayout.altPopupCombos
    }
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DroshSurface)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${modifier.name} combos",
                color = DroshTextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                combos.forEachIndexed { index, combo ->
                    val label = combo.joinToString(separator = "+") { piece ->
                        when (piece) {
                            is ExtraKey.Special -> "⌃"
                            is ExtraKey.Text -> piece.glyph
                            is ExtraKey.Navigation -> piece.name
                        }
                    }
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onComboSelected(combo.toIntents())
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            color = DroshPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun List<ExtraKey>.toIntents(): List<InputIntent> = map { key ->
    when (key) {
        is ExtraKey.Special -> InputIntent.ArmModifier(key)
        is ExtraKey.Text -> InputIntent.TypeChar(key.glyph.first())
        is ExtraKey.Navigation -> InputIntent.Navigate(key)
    }
}
