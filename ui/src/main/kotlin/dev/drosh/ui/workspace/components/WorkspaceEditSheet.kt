package dev.drosh.ui.workspace.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBuild
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning
import dev.drosh.domain.workspace.WORKSPACE_COLOR_SEED_COUNT
import dev.drosh.domain.workspace.WorkspaceEdit
import dev.drosh.domain.workspace.WorkspacePath
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.workspace.WorkspaceViewModel

/**
 * The six accents a workspace can carry.
 *
 * What is stored is a seed *index*, not a colour, and that is the point: a hex
 * value in the database would survive a theme change that made it unreadable,
 * while a workspace list is read at a glance where one project looking like
 * another is the failure that matters. Every entry below is a token that already
 * carries a dark and a light value, so a seed resolves correctly in both.
 *
 * The last slot is deliberately the neutral outline — a project with no opinion
 * about colour still needs one, and the six are spaced so no two adjacent
 * workspaces are hard to tell apart.
 *
 * A function rather than a top-level `val` on purpose: these tokens read through
 * a composition local and there is no value to hold outside a composition. See
 * `design-system/.../DroshColors.kt`.
 */
@Composable
private fun workspaceSeedPalette(): List<Color> = listOf(
    DroshPrimary,
    DroshSuccess,
    DroshWarning,
    DroshError,
    DroshBuild,
    DroshOutline,
)

/**
 * The accent for a seed.
 *
 * Clamped rather than indexed: the value comes out of a database column that
 * nothing forces to be in range, and a modulo would silently relabel every
 * project the moment the palette grew.
 */
@Composable
fun workspaceSeedColor(seed: Int): Color =
    workspaceSeedPalette()[seed.coerceIn(0, WORKSPACE_COLOR_SEED_COUNT - 1)]

/**
 * Create or edit a workspace.
 *
 * A bottom sheet rather than a screen: this is four fields, and it is always
 * opened from a list the user is still thinking about. Leaving that list to fill
 * in a name and come back is more navigation than the task is worth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEditSheet(
    state: WorkspaceViewModel.EditorState,
    onDraftChange: ((WorkspaceEdit) -> WorkspaceEdit) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = state.draft
    val nameValid = draft.name.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DroshBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(
                text = if (state.isNew) "Yeni proje" else "Projeyi düzenle",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
            Spacer(Modifier.height(14.dp))

            SheetField(
                value = draft.name,
                onValueChange = { value -> onDraftChange { it.copy(name = value) } },
                label = "Proje adı",
                placeholder = "ör. myapp",
                singleLine = true,
                imeAction = ImeAction.Next,
                // Deliberately not an error state. The field is empty on a sheet
                // the user has only just opened, and turning it red before the
                // first keypress is accusing them of something they have not done.
                // The disabled button below is the actual signal.
                supporting = "zorunlu",
            )

            Spacer(Modifier.height(10.dp))

            SheetField(
                value = draft.rootPath,
                onValueChange = { value -> onDraftChange { it.copy(rootPath = value) } },
                label = "Klasör",
                placeholder = WorkspacePath.DEFAULT_ROOT,
                singleLine = true,
                imeAction = ImeAction.Next,
                isMonospace = true,
                // Shown rather than silently applied. The stored path is
                // normalised, so `myapp/` and `/myapp` are one workspace — and the
                // user should be able to see which spelling was kept.
                supporting = WorkspacePath.normalise(draft.rootPath),
            )

            Spacer(Modifier.height(10.dp))

            SheetField(
                value = draft.description,
                onValueChange = { value -> onDraftChange { it.copy(description = value) } },
                label = "Not (isteğe bağlı)",
                placeholder = "ör. staging deploy buraya",
                singleLine = false,
                imeAction = ImeAction.Done,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "RENK",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.9.sp,
                color = DroshTextMuted,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                repeat(WORKSPACE_COLOR_SEED_COUNT) { seed ->
                    val color = workspaceSeedColor(seed)
                    val selected = draft.colorSeed == seed
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = DroshText,
                                        shape = CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onDraftChange { it.copy(colorSeed = seed) } }
                            .semantics {
                                contentDescription = "renk ${seed + 1}" +
                                    if (selected) ", seçili" else ""
                            },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            ActionButton(
                text = if (state.isNew) "Proje oluştur" else "Kaydet",
                onClick = onSave,
                enabled = nameValid,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isNew) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Session'lar kalıcı değildir. Proje, yalnızca session'ları " +
                        "gruplar; arkasında çalışan bir süreç tutmaz.",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = DroshTextMuted,
                )
            } else {
                Spacer(Modifier.height(8.dp))
                FlatButton(
                    text = "Projeyi sil",
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    tint = DroshError,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Session'lar silinmez — yalnızca bu proje bağları kalkar.",
                    fontSize = 11.sp,
                    color = DroshTextMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp, color = DroshTextSecondary) },
            placeholder = {
                Text(
                    placeholder,
                    fontSize = 13.sp,
                    color = DroshTextMuted,
                    fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                )
            },
            singleLine = singleLine,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = DroshText,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            ),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            isError = isError,
            shape = RoundedCornerShape(11.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DroshPrimary,
                unfocusedBorderColor = DroshOutline,
                errorBorderColor = DroshError,
                focusedContainerColor = DroshBackground,
                unfocusedContainerColor = DroshBackground,
                errorContainerColor = DroshBackground,
                cursorColor = DroshPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 56.dp else 84.dp),
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = supporting,
                fontSize = 10.sp,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                color = if (isError) DroshError else DroshTextMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
