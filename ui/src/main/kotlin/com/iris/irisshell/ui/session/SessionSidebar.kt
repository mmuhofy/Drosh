package com.iris.irisshell.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.irisshell.domain.session.SessionSnapshot
import com.iris.irisshell.ui.IrisIcons
import com.iris.irisshell.design.system.IrisBorderSubtle
import com.iris.irisshell.design.system.IrisError
import com.iris.irisshell.design.system.IrisOnPrimary
import com.iris.irisshell.design.system.IrisPrimary
import com.iris.irisshell.design.system.IrisSuccess
import com.iris.irisshell.design.system.IrisSurfaceVariant
import com.iris.irisshell.design.system.IrisText
import com.iris.irisshell.design.system.IrisTextMuted
import com.iris.irisshell.design.system.IrisTextSecondary

/**
 * Slide-in sol sidebar — iOS / Apple Settings tarzı layout (Stitch taslağı),
 * ama renkler tamamen projenin kendi paleti: com.iris.irisshell.ui.theme.IrisTheme.kt.
 *
 * ÖNEMLİ NOT (Muhofy'nin bilmesi gereken bir tutarsızlık):
 * MEMORYBANK.md §5 Primary = #E8C547 (warm gold) diyor, ama gerçek kodda
 * (IrisTheme.kt) Primary = #3B82F6 (mavi) tanımlı. Bu dosya gerçek kodu
 * (canonical, derlenen kaynak) esas alıyor — Memory Bank muhtemelen güncel
 * değil. Bunu ayrıca Memory Bank güncelleme adımında teyit etmen gerekir.
 *
 * Kullanılan gerçek token'lar: IrisSurfaceVariant, IrisPrimary, IrisOnPrimary,
 * IrisText, IrisTextSecondary, IrisTextMuted, IrisBorderSubtle, IrisError,
 * IrisSuccess. Hiçbir renk tahmin/icat edilmedi — hepsi IrisTheme.kt'den.
 *
 * Public API değişmedi: SessionSidebar(isOpen, onDismiss, onOpenSettings).
 * userDisplayName / userInitials opsiyonel, mevcut çağrı yerlerini bozmaz.
 *
 * Rename düzeltmesi: önceki versiyonda onFocusChanged, text field ekrana
 * gelir gelmez isFocused=false ile bir kez tetiklenip anında commit
 * ediyordu (kullanıcı hiçbir şey yazamadan rename modu kapanıyordu). Şimdi
 * sadece GERÇEKTEN focus alındıktan sonra kaybedilirse otomatik commit
 * ediliyor; ayrıca görünür bir onay (✓) butonu eklendi.
 */
@Composable
fun SessionSidebar(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    userDisplayName: String = "User",
    userInitials: String = userDisplayName.take(2).uppercase(),
) {
    val viewModel: SessionSwitcherViewModel = hiltViewModel()
    val config = LocalConfiguration.current
    val sidebarW = remember(config) {
        val sw = config.screenWidthDp
        if (sw > 0) (sw * 0.85f).coerceAtMost(390f).dp else 340.dp
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .zIndex(1f),
        ) {
            AnimatedVisibility(
                visible = isOpen,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetX = { fullWidth -> -fullWidth },
                ),
                exit = slideOutHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    targetOffsetX = { fullWidth -> -fullWidth },
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(sidebarW)
                        .clip(RoundedCornerShape(0.dp, 32.dp, 32.dp, 0.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                ) {
                    SidebarContent(
                        viewModel = viewModel,
                        onOpenSettings = onOpenSettings,
                        userDisplayName = userDisplayName,
                        userInitials = userInitials,
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarContent(
    viewModel: SessionSwitcherViewModel,
    onOpenSettings: () -> Unit,
    userDisplayName: String,
    userInitials: String,
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var renamingSessionId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions else sessions.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    val activeSession = filtered.firstOrNull { it.id == activeId }
    val recentSessions = filtered.filter { it.id != activeId }

    fun commitRename() {
        val id = renamingSessionId
        val newName = renameValue.trim()
        if (id != null && newName.isNotEmpty()) {
            viewModel.rename(id, newName)
        }
        renamingSessionId = null
    }

    // Keep the sidebar surface behind the transparent status bar, while
    // keeping its actual content below the system icons.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IrisSurfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
        )

        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Iris Shell",
                    color = IrisText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(IrisSuccess),
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(IrisPrimary)
                    .clickable { viewModel.createNew("shell") }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = IrisIcons.Plus,
                    contentDescription = "New session",
                    tint = IrisOnPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "New",
                    color = IrisOnPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        }

        // Search bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp)
                .height(32.dp)
                .border(
                    width = 1.dp,
                    color = IrisBorderSubtle.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                ),
            shape = RoundedCornerShape(8.dp),
            color = IrisSurfaceVariant,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = IrisIcons.Search,
                    contentDescription = null,
                    tint = IrisTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search sessions...",
                            color = IrisTextMuted,
                            fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(color = IrisText, fontSize = 13.sp),
                        cursorBrush = SolidColor(IrisText),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Body
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (activeSession != null) {
                item(key = "active_header") { SectionHeader(label = "ACTIVE", trailing = "live") }
                item(key = "active_${activeSession.id}") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IrisSurfaceVariant),
                    ) {
                        SessionRow(
                            snapshot = activeSession,
                            isActive = true,
                            dotColor = IrisSuccess,
                            trailingText = "now",
                            trailingColor = IrisPrimary,
                            rowBackground = IrisPrimary.copy(alpha = 0.12f),
                            isRenaming = renamingSessionId == activeSession.id,
                            renameValue = renameValue,
                            onRenameValueChange = { renameValue = it },
                            onRenameCommit = { commitRename() },
                            onClick = { if (renamingSessionId == null) viewModel.activate(activeSession.id) },
                            onStartRename = {
                                renamingSessionId = activeSession.id
                                renameValue = activeSession.name
                            },
                            onDelete = { viewModel.delete(activeSession.id) },
                        )
                    }
                }
            }

            if (recentSessions.isNotEmpty()) {
                item(key = "recent_header") { SectionHeader(label = "RECENT", trailing = null) }
                item(key = "recent_list") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IrisSurfaceVariant.copy(alpha = 0.5f)),
                    ) {
                        recentSessions.forEachIndexed { index, snapshot ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(IrisBorderSubtle),
                                )
                            }
                            SessionRow(
                                snapshot = snapshot,
                                isActive = false,
                                dotColor = IrisTextMuted,
                                trailingText = null,
                                trailingColor = IrisTextMuted,
                                rowBackground = Color.Transparent,
                                isRenaming = renamingSessionId == snapshot.id,
                                renameValue = renameValue,
                                onRenameValueChange = { renameValue = it },
                                onRenameCommit = { commitRename() },
                                onClick = { if (renamingSessionId == null) viewModel.activate(snapshot.id) },
                                onStartRename = {
                                    renamingSessionId = snapshot.id
                                    renameValue = snapshot.name
                                },
                                onDelete = { viewModel.delete(snapshot.id) },
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (searchQuery.isBlank()) "No active sessions" else "No results",
                        color = IrisTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(4.dp)) }
        }

        // Bottom profile row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(IrisSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = userInitials, color = IrisText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Text(
                    text = userDisplayName,
                    color = IrisText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HoverIconButton(
                onClick = onOpenSettings,
                contentDescription = "Settings",
                 icon = IrisIcons.Settings,
                tint = IrisTextSecondary,
                iconSize = 18.dp,
                buttonSize = 24.dp,
            )
        }
        }
    }
}

@Composable
private fun HoverIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
    tint: Color = IrisTextSecondary,
    iconSize: Dp = 14.dp,
    buttonSize: Dp = 24.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.2f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "iconScale",
    )
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

@Composable
private fun SectionHeader(label: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = IrisTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.6.sp,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = IrisPrimary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SessionRow(
    snapshot: SessionSnapshot,
    isActive: Boolean,
    dotColor: Color,
    trailingText: String?,
    trailingColor: Color,
    rowBackground: Color,
    isRenaming: Boolean,
    renameValue: String,
    onRenameValueChange: (String) -> Unit,
    onRenameCommit: () -> Unit,
    onClick: () -> Unit,
    onStartRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Gerçekten focus alındıktan sonra kaybedilirse commit et — ilk
    // kompozisyondaki "henüz focus yok" (isFocused=false) sinyaliyle
    // yanlışlıkla anında commit edilmesin diye bu bayrak tutuluyor.
    var hasFocusedOnce by remember(snapshot.id, isRenaming) { mutableStateOf(false) }

    LaunchedEffect(isRenaming) {
        if (isRenaming) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(rowBackground)
            .then(
                if (!isRenaming) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )

        if (isRenaming) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = IrisSurfaceVariant,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = IrisPrimary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                BasicTextField(
                    value = renameValue,
                    onValueChange = onRenameValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                hasFocusedOnce = true
                            } else if (hasFocusedOnce) {
                                onRenameCommit()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = IrisText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp,
                    ),
                    cursorBrush = SolidColor(IrisPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onRenameCommit() }),
                )
            }
            HoverIconButton(
                onClick = onRenameCommit,
                contentDescription = "Confirm rename",
                 icon = IrisIcons.Check,
                tint = IrisPrimary,
                iconSize = 16.dp,
                buttonSize = 24.dp,
            )
        } else {
            Text(
                text = snapshot.name,
                color = if (isActive) IrisText else IrisText.copy(alpha = 0.9f),
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailingText != null) {
                Text(text = trailingText, color = trailingColor, fontSize = 11.sp)
            }
            HoverIconButton(
                onClick = onStartRename,
                contentDescription = "Rename",
                 icon = IrisIcons.Pencil,
                iconSize = 14.dp,
                buttonSize = 24.dp,
            )
            HoverIconButton(
                onClick = onDelete,
                contentDescription = "Delete",
                 icon = IrisIcons.Trash2,
                iconSize = 14.dp,
                buttonSize = 24.dp,
            )
        }
    }
}
