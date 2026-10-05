package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshWarning
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.AgentChat
import dev.drosh.domain.agent.ChatStatus
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.chrisbanes.haze.hazeSource
import dev.drosh.ui.agent.components.AgentIconPill
import dev.drosh.ui.agent.components.AgentPill
import dev.drosh.ui.agent.components.DroshAgentMark
import dev.drosh.ui.agent.components.ProvideAgentGlass
import dev.drosh.ui.agent.components.rememberAgentGlass
import dev.drosh.ui.agent.components.SectionHeader

/**
 * Agent Home — every agent chat, grouped by whether it needs attention.
 *
 * The grouping is the point: a run in progress and a run waiting on a yes/no
 * decision are the only two rows that require action, so they sit above everything
 * else instead of being hunted for in a list ordered by recency.
 */
@Composable
fun AgentHomeScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentHomeViewModel = hiltViewModel(),
) {
    val grouped by viewModel.grouped.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val directory by viewModel.directory.collectAsStateWithLifecycle()
    val busyChatId by viewModel.busyChatId.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf<AgentChat?>(null) }
    var deleting by remember { mutableStateOf<AgentChat?>(null) }

    // One state for the screen: the list is the blur source, the pills are effects.
    // Sharing it is what keeps this to a single offscreen render.
    val glass = rememberAgentGlass()

    ProvideAgentGlass(glass) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomePillRow(
                directory = directory.orEmpty(),
                onBack = onBack,
                onNewChat = { onNewChat(directory.orEmpty()) },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(glass.state),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasKey == false) {
                    item(key = "nokey") {
                        MissingKeyCard(onOpenSettings = onOpenSettings)
                    }
                }

                if (grouped.running.isNotEmpty()) {
                    item(key = "hdr_running") { SectionHeader("ÇALIŞAN") }
                    items(grouped.running, key = { "run_${it.id}" }) { chat ->
                        ChatCard(
                            chat = chat,
                            busy = chat.id == busyChatId,
                            onClick = { onOpenChat(chat.id) },
                            onLongPress = { viewModel.markBusy(chat.id) },
                        )
                    }
                }

                if (grouped.waiting.isNotEmpty()) {
                    item(key = "hdr_waiting") { SectionHeader("SENİ BEKLİYOR") }
                    items(grouped.waiting, key = { "wait_${it.id}" }) { chat ->
                        ChatCard(
                            chat = chat,
                            busy = chat.id == busyChatId,
                            onClick = { onOpenChat(chat.id) },
                            onLongPress = { viewModel.markBusy(chat.id) },
                        )
                    }
                }

                if (grouped.recent.isNotEmpty()) {
                    item(key = "hdr_recent") { SectionHeader("SON") }
                    items(grouped.recent, key = { "recent_${it.id}" }) { chat ->
                        ChatCard(
                            chat = chat,
                            busy = chat.id == busyChatId,
                            onClick = { onOpenChat(chat.id) },
                            onLongPress = { viewModel.markBusy(chat.id) },
                        )
                    }
                }

                if (grouped.isEmpty && hasKey != false) {
                    item(key = "empty") { EmptyChats() }
                }
            }
        }

        // A full-width bar rather than a floating button: it sits above the
        // navigation bar with predictable spacing, and a FAB on a dark field at
        // the bottom edge competes with the gesture pill for the same 16dp.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DroshSurface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            ActionButton(
                text = "New agent chat",
                onClick = { onNewChat(directory) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            DirectoryChip(
                directory = directory,
                onChange = viewModel::setDirectory,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    // Outside the Box: the menu is a full-screen overlay, and putting it inside
    // the same Column as the list makes the overlay compete for the list's bounds.
    val busyChat = busyChatId?.let { id -> grouped.all().firstOrNull { it.id == id } }
    if (busyChat != null) {
        RowContextMenu(
            chat = busyChat,
            onDismiss = { viewModel.markBusy(null) },
            onRename = {
                viewModel.markBusy(null)
                renaming = busyChat
            },
            onDelete = {
                viewModel.markBusy(null)
                deleting = busyChat
            },
        )
    }

    renaming?.let { chat ->
        RenameChatDialog(
            initial = chat.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(chat.id, name)
                renaming = null
            },
        )
    }

        deleting?.let { chat ->
            DeleteChatDialog(
                name = chat.name,
                onDismiss = { deleting = null },
                onConfirm = {
                    viewModel.delete(chat.id)
                    deleting = null
                },
            )
        }
    }
}

/**
 * The home screen's control row, and the workspace row under it.
 *
 * Same reasoning as the chat's pill row: no top bar. The list of chats is the
 * screen, and a bar above it spent 56dp restating what the app already is.
 *
 * "Yeni" is the one primary action, so it is the one filled pill on the screen.
 * Everything else — the back arrow, the working directory — is quiet, because a row
 * with three equally loud controls has no primary action at all.
 */
@Composable
private fun HomePillRow(
    directory: String,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentIconPill(
                icon = DroshIcons.ArrowLeft,
                contentDescription = "Geri",
                onClick = onBack,
                tint = DroshTextSecondary,
            )

            // A label, not a disabled pill. This is the screen's own name, so it
            // is not a destination and there is nothing to tap; a greyed-out pill
            // would promise a tap that does nothing.
            Text(
                text = "Agent",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )

            AgentPill(
                label = "Yeni",
                onClick = onNewChat,
                primary = true,
                leadingIcon = DroshIcons.Plus,
                contentDescription = "Yeni sohbet",
            )
        }

        // The working directory on its own line, because a path is long and a row
        // that has to truncate it is worse than a row that does not have to.
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentPill(
                label = directory.ifBlank { "çalışma dizini" },
                onClick = { /* Directory picking lands with the picker. */ },
                modifier = Modifier.weight(1f, fill = false),
                leadingIcon = DroshIcons.Folder,
                trailingChevron = true,
                contentDescription = "Çalışma dizini: $directory",
            )
        }
    }
}

/**
 * One chat row.
 *
 * Long press opens the rename/delete menu. A row is the only target for either
 * action and there is room for one, so a hidden gesture beats a chevron that
 * competes with the status dot for the same 24dp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatCard(
    chat: AgentChat,
    busy: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val (dotColor, statusLabel) = when (chat.status) {
        ChatStatus.Running -> DroshPrimary to "çalışıyor"
        ChatStatus.WaitingApproval -> DroshWarning to "onay bekliyor"
        ChatStatus.Done -> DroshSuccess to "tamamlandı"
        ChatStatus.Failed -> DroshError to "hata"
        ChatStatus.Idle -> DroshOutline to ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (busy) DroshSurfaceVariant else DroshSurfaceLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            // One description for the whole card: a screen reader reading four
            // separate fragments would not say "vendor-split, çalışıyor, ~/zsh".
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(chat.name)
                    if (statusLabel.isNotEmpty()) append(", ").append(statusLabel)
                    append(", ").append(chat.workingDirectory)
                }
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status is a dot *and* a word — colour alone is not readable by everyone,
        // and it is not readable at all by a screen reader.
        if (chat.status == ChatStatus.Running) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(10.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.name,
                fontSize = 14.sp,
                color = DroshText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
            if (chat.lastMessagePreview.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chat.lastMessagePreview,
                    fontSize = 12.sp,
                    color = DroshTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.workingDirectory,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DroshTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (statusLabel.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = statusLabel,
                        fontSize = 10.sp,
                        color = dotColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissingKeyCard(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceVariant)
            .padding(14.dp),
    ) {
        Text(
            text = "API anahtarı gerekli",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = DroshText,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Agent'ı çalıştırmak için bir OpenRouter anahtarı ekle. " +
                "Cihazda şifreli saklanır.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshTextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        ActionButton(
            text = "Anahtar ekle",
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyChats() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DroshAgentMark(size = 44.dp, tint = DroshOutline)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Henüz agent chat yok",
            fontSize = 15.sp,
            color = DroshText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Bir chat oluştur — kendi terminalini açar,\nterminal session açmana gerek yok.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = DroshTextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    }