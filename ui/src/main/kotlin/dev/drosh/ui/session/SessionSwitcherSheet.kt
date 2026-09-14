package dev.drosh.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.ui.DroshIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SessionSwitcherSheet(
    onDismiss: () -> Unit,
    viewModel: SessionSwitcherViewModel = hiltViewModel(),
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var committingId     by remember { mutableStateOf<String?>(null) }
    var renamingId       by remember { mutableStateOf<String?>(null) }
    var renameNewName    by remember { mutableStateOf("") }
    var pendingDelete    by remember { mutableStateOf<DeletedSession?>(null) }
    var searchText       by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope     = rememberCoroutineScope()
    val sheetState        = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredSessions = remember(sessions, searchText) {
        if (searchText.isBlank()) sessions
        else sessions.filter { s ->
            s.name.contains(searchText, ignoreCase = true) ||
                s.liveSnapshotLines.any { it.contains(searchText, ignoreCase = true) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        shape            = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor   = DroshSurface,
        tonalElevation   = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 6.dp)
                    .size(width = 40.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(DroshText.copy(alpha = 0.1f)),
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                SheetTopBar(
                    onClose        = onDismiss,
                    onCreate       = { showCreateDialog = true },
                    searchText     = searchText,
                    onSearchChange = { searchText = it },
                )

                if (filteredSessions.isEmpty()) {
                    SheetEmptyState(
                        searchText = searchText,
                        onCreate   = { showCreateDialog = true },
                    )
                } else {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filteredSessions.forEachIndexed { index, snapshot ->
                            // Staggered slide-in animation per card
                            AnimatedVisibility(
                                visible = true,
                                enter   = slideInVertically(
                                    initialOffsetY = { it / 3 },
                                    animationSpec  = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness    = Spring.StiffnessMedium,
                                    ),
                                ) + fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 220,
                                        delayMillis    = index * 40,
                                    )
                                ),
                            ) {
                                SessionCard(
                                    snapshot     = snapshot,
                                    isActive     = snapshot.id == activeId,
                                    isCommitting = committingId == snapshot.id,
                                    onActivate   = {
                                        if (committingId == null) {
                                            committingId = snapshot.id
                                            viewModel.activate(snapshot.id)
                                        }
                                    },
                                    onRename = {
                                        renameNewName = snapshot.name
                                        renamingId    = snapshot.id
                                    },
                                    onDelete = {
                                        pendingDelete = DeletedSession(
                                            snapshot     = snapshot,
                                            wasActive    = snapshot.id == activeId,
                                            fallbackName = null,
                                        )
                                        viewModel.delete(snapshot.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }
    }

    // Commit animation then dismiss
    LaunchedEffect(committingId) {
        val id = committingId ?: return@LaunchedEffect
        delay(220)
        onDismiss()
        committingId = null
    }

    // Resolve fallback name after deletion
    LaunchedEffect(pendingDelete, sessions, activeId) {
        val pd = pendingDelete ?: return@LaunchedEffect
        if (pd.fallbackName == null && !sessions.any { it.id == pd.snapshot.id }) {
            val fallback = sessions.firstOrNull { it.id == activeId }?.name
            pendingDelete = pd.copy(
                wasActive    = pd.wasActive || pd.snapshot.id == activeId,
                fallbackName = fallback,
            )
        }
    }

    // Undo snackbar
    LaunchedEffect(pendingDelete) {
        val pd = pendingDelete ?: return@LaunchedEffect
        val msg = if (pd.wasActive && pd.fallbackName != null)
            "${pd.snapshot.name} removed · now viewing ${pd.fallbackName}"
        else
            "${pd.snapshot.name} removed"
        snackbarScope.launch {
            val result = try {
                snackbarHostState.showSnackbar(
                    message           = msg,
                    actionLabel       = "Undo",
                    withDismissAction = true,
                    duration          = androidx.compose.material3.SnackbarDuration.Short,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                SnackbarResult.Dismissed
            }
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.restoreSession(pd.snapshot)
                SnackbarResult.Dismissed       -> pendingDelete = null
            }
        }
    }

    if (showCreateDialog) {
        CreateSessionDialog(
            onConfirm = { name -> viewModel.createNew(name); showCreateDialog = false },
            onDismiss = { showCreateDialog = false },
        )
    }

    renamingId?.let { id ->
        RenameSessionDialog(
            currentName = renameNewName,
            onConfirm   = { newName -> viewModel.rename(id, newName); renamingId = null },
            onDismiss   = { renamingId = null },
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Press-scale clickable box                                                 */
/* -------------------------------------------------------------------------- */

@Composable
private fun PressScaleBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness    = 300f,
        ),
        label = "press-scale",
    )
    Box(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick,
                enabled           = enabled,
            ),
        contentAlignment = contentAlignment,
    ) { content() }
}

/* -------------------------------------------------------------------------- */
/*  Top bar                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun SheetTopBar(
    onClose: () -> Unit,
    onCreate: () -> Unit,
    searchText: String,
    onSearchChange: (String) -> Unit,
) {
    // Header row
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = "Sessions",
            color    = DroshText,
            style    = MaterialTheme.typography.titleLarge.copy(
                fontWeight    = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            ),
            modifier = Modifier.weight(1f),
        )

        // New session button — blue pill, scale on press
        PressScaleBox(
            onClick = onCreate,
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(DroshPrimary),
        ) {
            Row(
                modifier          = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector        = DroshIcons.Plus,
                    contentDescription = null,
                    tint               = DroshOnPrimary,
                    modifier           = Modifier.size(13.dp),
                )
                Text(
                    text       = "New",
                    color      = DroshOnPrimary,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.size(8.dp))

        // Close button — subtle circle, scale on press
        PressScaleBox(
            onClick = onClose,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.03f))
                .border(0.5.dp, DroshBorderSubtle.copy(alpha = 0.3f), CircleShape),
        ) {
            Icon(
                imageVector        = DroshIcons.X,
                contentDescription = "Close",
                tint               = DroshTextSecondary,
                modifier           = Modifier.size(18.dp),
            )
        }
    }

    // Search field
    OutlinedTextField(
        value         = searchText,
        onValueChange = onSearchChange,
        modifier      = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        singleLine    = true,
        shape         = RoundedCornerShape(16.dp),
        placeholder   = { Text("Search sessions…", color = DroshTextMuted, fontSize = 13.5.sp) },
        leadingIcon   = {
            Icon(
                imageVector        = DroshIcons.Search,
                contentDescription = null,
                tint               = DroshTextMuted,
                modifier           = Modifier.size(15.dp),
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = DroshPrimary.copy(alpha = 0.5f),
            unfocusedBorderColor = DroshBorderSubtle.copy(alpha = 0.08f),
            focusedContainerColor   = DroshBackground.copy(alpha = 0.2f),
            unfocusedContainerColor = DroshBackground.copy(alpha = 0.1f),
            cursorColor          = DroshPrimary,
            focusedTextColor     = DroshText,
            unfocusedTextColor   = DroshText,
        ),
    )
}

/* -------------------------------------------------------------------------- */
/*  Empty state                                                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun SheetEmptyState(
    searchText: String,
    onCreate: () -> Unit,
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text  = if (searchText.isNotBlank()) "No matching session" else "No active session",
            color = DroshText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text  = if (searchText.isNotBlank())
                "Try a different search term"
            else
                "Tap New to spawn your first terminal",
            color = DroshTextMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/* -------------------------------------------------------------------------- */
/*  Dialogs                                                                   */
/* -------------------------------------------------------------------------- */

@Composable
private fun CreateSessionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("shell") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("New session", color = DroshText) },
        text             = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                singleLine    = true,
                label         = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "shell" }) }) {
                Text("Create", color = DroshPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DroshTextMuted)
            }
        },
        containerColor = DroshSurface,
    )
}

@Composable
private fun RenameSessionDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Rename session", color = DroshText) },
        text             = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                singleLine    = true,
                label         = { Text("Name") },
                isError       = name.isBlank(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Save", color = DroshPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DroshTextMuted)
            }
        },
        containerColor = DroshSurface,
    )
}
