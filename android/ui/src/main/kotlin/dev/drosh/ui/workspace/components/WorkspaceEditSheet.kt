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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning
import dev.drosh.design.system.DroshBuild
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.workspace.WORKSPACE_COLOR_SEED_COUNT
import dev.drosh.domain.workspace.WorkspaceEdit
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.workspace.WorkspaceViewModel
import kotlinx.coroutines.launch

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
 * A bottom sheet rather than a screen: this is three fields, and it is always
 * opened from a list the user is still thinking about. Leaving that list to fill
 * in a name and come back is more navigation than the task is worth.
 *
 * There is no folder field. A workspace's directory is a label the domain
 * documents as one — never `cd`-ed into on the user's behalf — and a session
 * starts in whatever directory its own shell starts in, which is chosen in the
 * agent sheet where the session is made. A field here would read as "your shells
 * run here", which is the one thing the app does not do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEditSheet(
    state: WorkspaceViewModel.EditorState,
    onDraftChange: ((WorkspaceEdit) -> WorkspaceEdit) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onToggleArchive: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * The write that failed, as a string resource, shown in the sheet.
     *
     * A modal sheet is a window of its own, so the screen's snackbar would sit
     * behind it: the sheet is where the user is looking and where the button
     * they pressed is, so that is where the failure goes. The sheet stays open —
     * a sheet that closed on a write that did not happen claimed a change the
     * app did not make — and the Save button below the message is the retry.
     */
    errorMessage: Int? = null,
) {
    val draft = state.draft
    val nameValid = draft.name.isNotBlank()

    // Delete asks first. The row is not recoverable — there is no undelete, the
    // workspace id is gone — and a caption under the button is not a
    // confirmation. Archiving sits above it for the same reason: it is what most
    // people actually want, and it is reversible.
    var confirmDelete by remember { mutableStateOf(false) }

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
                text = stringResource(
                    if (state.isNew) R.string.projects_create_title
                    else R.string.projects_edit_title,
                ),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
            Spacer(Modifier.height(14.dp))

            SheetField(
                value = draft.name,
                onValueChange = { value -> onDraftChange { it.copy(name = value) } },
                label = stringResource(R.string.projects_name_label),
                placeholder = stringResource(R.string.projects_name_placeholder),
                singleLine = true,
                imeAction = ImeAction.Next,
                // Deliberately not an error state. The field is empty on a sheet
                // the user has only just opened, and turning it red before the
                // first keypress is accusing them of something they have not done.
                // The disabled button below is the actual signal.
                supporting = stringResource(R.string.projects_name_supporting),
            )

            Spacer(Modifier.height(10.dp))

            SheetField(
                value = draft.description,
                onValueChange = { value -> onDraftChange { it.copy(description = value) } },
                label = stringResource(R.string.projects_note_label),
                placeholder = stringResource(R.string.projects_note_placeholder),
                singleLine = false,
                imeAction = ImeAction.Done,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.projects_colour_label),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.9.sp,
                color = DroshTextMuted,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                repeat(WORKSPACE_COLOR_SEED_COUNT) { seed ->
                    val color = workspaceSeedColor(seed)
                    val selected = draft.colorSeed == seed
                    // Hoisted out of the semantics block: `stringResource` is a
                    // composable call and the block is not one.
                    val swatchCd = stringResource(R.string.projects_colour_cd, seed + 1) +
                        if (selected) {
                            stringResource(R.string.projects_colour_selected)
                        } else {
                            ""
                        }
                    // 44dp, the touch minimum, not the 34dp circle it draws. Six
                    // swatches across a phone screen was what forced the smaller
                    // target, and a colour you cannot reliably pick is a colour
                    // the palette does not really have.
                    Box(
                        modifier = Modifier
                            .size(44.dp)
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
                            .semantics { contentDescription = swatchCd },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            if (errorMessage != null) {
                Text(
                    text = stringResource(errorMessage),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = DroshError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }

            ActionButton(
                text = stringResource(
                    if (state.isNew) R.string.projects_action_create
                    else R.string.action_save,
                ),
                onClick = onSave,
                enabled = nameValid,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    if (state.isNew) R.string.projects_persistence_note
                    else R.string.projects_archive_note,
                ),
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = DroshTextMuted,
            )

            if (!state.isNew) {
                Spacer(Modifier.height(10.dp))

                FlatButton(
                    text = stringResource(
                        if (state.isArchived) R.string.projects_action_unarchive
                        else R.string.projects_action_archive,
                    ),
                    onClick = onToggleArchive,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(6.dp))

                FlatButton(
                    text = stringResource(R.string.projects_action_delete),
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    tint = DroshError,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.projects_delete_body),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = DroshTextMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = DroshSurface,
            titleContentColor = DroshText,
            textContentColor = DroshTextSecondary,
            title = {
                Text(
                    text = stringResource(R.string.projects_delete_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.projects_delete_body),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = DroshError,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = DroshTextSecondary,
                    )
                }
            },
        )
    }
}

/**
 * One labelled field.
 *
 * Each carries a [bringIntoViewRequester] and follows the focus: the sheet
 * scrolls, and a field focused by the keyboard's "next" is otherwise below the
 * bottom edge of a sheet the keyboard has just shrunk.
 */
@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    singleLine: Boolean,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }

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
                )
            },
            singleLine = singleLine,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = DroshText,
                fontFamily = LocalFontSet.current.sans,
            ),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
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
                .heightIn(min = if (singleLine) 56.dp else 84.dp)
                .bringIntoViewRequester(bringIntoView)
                .onFocusEvent { focus ->
                    if (focus.isFocused) {
                        scope.launch { bringIntoView.bringIntoView() }
                    }
                },
        )
        if (supporting != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = supporting,
                fontSize = 10.sp,
                color = DroshTextMuted,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
