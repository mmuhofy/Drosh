package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary

/**
 * Rename dialog, styled to match the reference app's: a raised panel, the
 * field drawn *darker* than the panel rather than lighter, and a filled
 * confirm beside a quiet cancel.
 *
 * Colours are measured rather than chosen — panel #292929, field #1F1F1F,
 * cancel #3E3E3E, confirm near-white — which is why the field is the darkest
 * part of the dialog rather than the brightest. An inline field in the row
 * cannot do this: the panel around it is the drawer's, not a dialog's.
 */
@Composable
fun RenameSessionDialog(
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun confirm() {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) onConfirm(trimmed) else onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Rename session",
                color = DroshText,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(color = DroshText, fontSize = 15.sp),
                    cursorBrush = SolidColor(DroshPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        confirm()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DroshSurface)
                        .padding(horizontal = 14.dp)
                        .focusRequester(focusRequester),
                )
                if (value.isBlank()) {
                    Text(
                        text = "A name is required",
                        color = DroshTextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(
                label = "Done",
                fill = DroshText,
                contentColor = DroshBackground,
                onClick = {
                    focusManager.clearFocus()
                    confirm()
                },
            )
        },
        dismissButton = {
            DialogButton(
                label = "Cancel",
                fill = DroshOutline,
                contentColor = DroshText,
                onClick = {
                    focusManager.clearFocus()
                    onDismiss()
                },
            )
        },
    )
}

/** A filled pill. Material's text buttons carry no fill of their own. */
@Composable
private fun DialogButton(
    label: String,
    fill: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
