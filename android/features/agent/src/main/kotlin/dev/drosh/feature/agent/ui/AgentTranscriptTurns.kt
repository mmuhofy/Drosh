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

internal fun rowKey(row: TranscriptRow): String = when (row) {
    is TranscriptRow.Turn -> "t_${row.prompt.id}"
    is TranscriptRow.Single -> "m_${row.message.id}"
}

/**
 * One row of the transcript.
 *
 * A turn is a prompt and everything the model did about it: the tool pills, the
 * reasoning, the answer. Grouping on the prompt rather than on the tool calls
 * keeps the conversation reading as a conversation — the user asked, the agent
 * worked, the agent answered — and it is what makes the prompt bubble
 * inescapable: it is the first thing in its row, and nothing is drawn above it.
 *
 * [Single] is anything outside a turn: the reasoning that arrived before a prompt
 * was ever sent, or a notice raised by something that was not the model
 * answering a question.
 */
internal sealed interface TranscriptRow {

    data class Turn(
        val prompt: ChatMessage.User,
        val steps: List<ChatMessage>,
    ) : TranscriptRow

    data class Single(val message: ChatMessage) : TranscriptRow
}

/**
 * Walks the transcript into turns.
 *
 * Computed here rather than in the transcript because it is a property of what is
 * on screen: the grouping is what the user reads, so it has to agree with what
 * the list is about to draw.
 *
 * Every prompt starts a turn, including one with no answer under it yet — a turn
 * that is still running shows its prompt and whatever has arrived so far, which
 * is the one moment the prompt most needs to be on screen.
 */
internal fun groupTranscript(messages: List<ChatMessage>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message !is ChatMessage.User) {
            rows += TranscriptRow.Single(message)
            index += 1
            continue
        }
        var cursor = index + 1
        while (cursor < messages.size && messages[cursor] !is ChatMessage.User) {
            cursor += 1
        }
        rows += TranscriptRow.Turn(
            prompt = message,
            steps = messages.subList(index + 1, cursor).toList(),
        )
        index = cursor
    }
    return rows
}

/**
 * A turn: the prompt, and everything the agent did about it.
 *
 * ## The prompt is always drawn
 *
 * It used to be one row among the rest, which meant a turn that ran fifteen tools
 * pushed the question it was answering off the top of the screen, and the user
 * had to scroll back to remember what they had asked. Now the prompt is the row's
 * own header, so it cannot be separated from its answer: the answer lands
 * directly under the question that earned it, and the answer's quick actions land
 * directly under that.
 *
 * ## The flow this buys
 *
 * Message, then the model's work, then the answer, then the answer's quick
 * actions — in one row, in that order, for every turn on the screen.
 *
 * The steps are tighter than the gap between turns, 8dp rather than the list's
 * 18, so what one prompt caused reads as one thing while a new prompt is what
 * starts a new thought.
 */
@Composable
internal fun TurnBlock(
    prompt: ChatMessage.User,
    steps: List<ChatMessage>,
    onAnswer: (String, ApprovalDecision) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    /** Edits what the user sent: the draft comes back for another go. */
    onEdit: (String) -> Unit,
    onInspectTool: (ChatMessage.ToolCall) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        UserBubble(prompt, onCopy = onCopy, onShare = onShare, onEdit = onEdit)

        if (steps.isEmpty()) return@Column

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            steps.forEach { step ->
                MessageRow(
                    message = step,
                    onAnswer = onAnswer,
                    onCopy = onCopy,
                    onShare = onShare,
                    onRetry = onRetry,
                    onEdit = onEdit,
                    onInspectTool = onInspectTool,
                )
            }
        }
    }
}
