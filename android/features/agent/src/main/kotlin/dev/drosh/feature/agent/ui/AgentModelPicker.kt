package dev.drosh.feature.agent.ui

import dev.drosh.ui.components.MOTION_MS
import dev.drosh.ui.components.glassRow
import dev.drosh.ui.components.ActionButton
import dev.drosh.ui.components.FlatButton
import dev.drosh.ui.components.IconAction
import dev.drosh.ui.components.CollapsibleRow
import android.content.Intent

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceLow
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshWarning
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentTodo
import dev.drosh.domain.agent.AgentTodoStatus
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRule
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.TerminalProjection
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.feature.agent.ui.components.DiffBlock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.rememberCoroutineScope
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshTrack
import dev.drosh.theme.DroshTileSelected
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.LlmModel
import dev.drosh.feature.agent.ui.components.AgentIconPill
import dev.drosh.feature.agent.ui.components.AgentPill
import dev.drosh.feature.agent.ui.components.AgentSelectableText
import dev.drosh.feature.agent.ui.components.AgentToolPill
import dev.drosh.feature.agent.ui.components.DroshStatusBarVisible
import dev.drosh.feature.agent.ui.components.ProvideAgentGlass
import dev.drosh.ui.session.SessionLogSource
import dev.drosh.ui.session.SessionLogTarget
import dev.drosh.ui.components.rememberAgentClipboard
import dev.drosh.feature.agent.ui.components.rememberAgentGlass
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.drosh.feature.agent.ui.components.MarkdownText
import androidx.compose.ui.res.stringResource
import dev.drosh.ui.R
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush

/**
 * The model picker, as the same sheet the language picker is.
 *
 * One bottom-sheet shape for the whole app: the same grabber, the same
 * capsule search, the same row with a circle of the model's initials and
 * the same check. The model sheet used to be its own shape — a scrim, its
 * own surface, its own row — which made two pickers on two screens look
 * like two apps.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    models: List<LlmModel>,
    selectedId: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val matches = remember(query, models) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models
        else models.filter { model ->
            model.id.lowercase().contains(q) ||
                (model.label?.lowercase()?.contains(q) == true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DroshTile,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DroshTrack),
            )
        },
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.agent_model),
                color = DroshText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp),
            )

            // The same capsule the language sheet searches through.
            Row(
                modifier = Modifier
                    .padding(horizontal = 22.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DroshTilePressed)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    imageVector = DroshIcons.Search,
                    contentDescription = null,
                    tint = DroshTextMuted,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_search_models),
                            color = DroshTextMuted,
                            fontSize = 14.5.sp,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = DroshText, fontSize = 14.5.sp),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when {
                loading -> {
                    Text(
                        text = stringResource(R.string.agent_models_loading),
                        color = DroshTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(22.dp),
                    )
                }
                matches.isEmpty() -> {
                    // The way out of an empty picker: the keys live in settings,
                    // and a sheet that can only be dismissed is a dead end.
                    Text(
                        text = stringResource(R.string.agent_no_models),
                        color = DroshTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.agent_open_settings), color = DroshPrimary)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(matches, key = { it.id }) { model ->
                            ModelOptionRow(
                                model = model,
                                selected = model.id == selectedId,
                                onClick = { onSelect(model.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun ModelOptionRow(
    model: LlmModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) DroshTileSelected else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The model's initials, in the language sheet's own circle.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (selected) DroshPrimary else DroshTilePressed),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = modelInitials(model),
                color = if (selected) DroshTile else DroshTextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label ?: model.id,
                color = if (selected) DroshPrimary else DroshText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.id,
                color = DroshTextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) SettingsCheck()
    }
}

/** Two letters from the model's label, or its id when there is no label. */
internal fun modelInitials(model: LlmModel): String {
    val words = (model.label ?: model.id).split(' ', '-', '_', ':', '/').filter { it.isNotBlank() }
    val initials = words.take(2).map { it.first().uppercase() }.joinToString("")
    return initials.ifEmpty { model.id.take(2).uppercase() }
}

@Composable
internal fun ErrorBanner(message: String, onDismiss: () -> Unit, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroshError.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshText,
            modifier = Modifier.weight(1f),
            // Announced when it appears: a failed run the user cannot hear about
            // is a failed run they do not know about.
        )
        FlatButton(
            text = stringResource(R.string.agent_fix),
            onClick = onFix,
            modifier = Modifier.width(72.dp),
        )
        IconAction(
            icon = DroshIcons.X,
            contentDescription = stringResource(R.string.action_close),
            onClick = onDismiss,
        )
    }
}

