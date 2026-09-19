package dev.drosh.ui.agent

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextDisabled
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ProviderConfig
import dev.drosh.domain.agent.ToolResult

@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(DroshBackground)
                .imePadding()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.IconButton(
                        onClick = { onBack() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = DroshIcons.ArrowLeft,
                            contentDescription = "Back",
                            tint = DroshTextSecondary
                        )
                    }
                    Text(
                        text = "AI Agent",
                        color = DroshText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { },
                        modifier = Modifier.size(36.dp),
                        enabled = false
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = DroshIcons.EllipsisVertical,
                            contentDescription = null,
                            tint = DroshTextMuted
                        )
                    }
                }

                if (uiState.providers.isNotEmpty()) {
                ProviderSelector(
                    providers = uiState.providers,
                    currentProvider = uiState.currentProvider,
                    workMode = uiState.workMode,
                    onProviderSelected = { viewModel.setProvider(it) },
                    onProviderUpdated = { viewModel.updateCurrentProvider(it) },
                    onWorkModeSelected = { viewModel.setWorkMode(it) }
                )
            }

            if (uiState.errorMessage != null) {
                ErrorBanner(
                    message = uiState.errorMessage!!,
                    onDismiss = { viewModel.clearError() }
                )
            }

            if (uiState.messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        AgentMessageRow(message = message)
                    }
                }
            }

            InputArea(
                isStreaming = uiState.isStreaming,
                onSend = { text ->
                    viewModel.sendMessage(text)
                    keyboardController?.hide()
                },
                onCancel = { viewModel.cancel() }
            )
        }
    }
}

@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshError.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .border(1.dp, DroshError.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = DroshText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text("×", color = DroshTextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Enter a task for Drosh agent...",
                color = DroshTextSecondary,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun ProviderSelector(
    providers: List<ProviderConfig>,
    currentProvider: ProviderConfig?,
    workMode: dev.drosh.domain.agent.WorkMode,
    onProviderSelected: (ProviderConfig) -> Unit,
    onProviderUpdated: (ProviderConfig) -> Unit,
    onWorkModeSelected: (dev.drosh.domain.agent.WorkMode) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { provider ->
                SuggestionChip(
                    onClick = { onProviderSelected(provider) },
                    label = {
                        Text(
                            text = provider.name,
                            fontSize = 12.sp,
                            color = if (currentProvider == provider) DroshPrimary else DroshTextSecondary
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (currentProvider == provider)
                            DroshPrimary.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
                )
            }
        }

        currentProvider?.let { provider ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = provider.endpoint,
                    onValueChange = { endpoint ->
                        onProviderUpdated(
                            provider.copy(endpoint = endpoint)
                        )
                    },
                    label = { Text("Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedLabelColor = DroshPrimary,
                        cursorColor = DroshPrimary,
                    )
                )
                OutlinedTextField(
                    value = provider.apiKey,
                    onValueChange = { key ->
                        onProviderUpdated(
                            provider.copy(apiKey = key)
                        )
                    },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedLabelColor = DroshPrimary,
                        cursorColor = DroshPrimary,
                    )
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            dev.drosh.domain.agent.WorkMode.entries.forEach { mode ->
                val isSelected = workMode == mode
                SuggestionChip(
                    onClick = { onWorkModeSelected(mode) },
                    label = {
                        Text(
                            text = mode.name,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) DroshPrimary else DroshTextMuted
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isSelected)
                            DroshPrimary.copy(alpha = 0.15f)
                        else Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
fun InputArea(
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Message Drosh...", color = DroshTextMuted, fontSize = 14.sp) },
            modifier = Modifier
                .weight(1f)
                .height(100.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = DroshText,
                fontSize = 14.sp
            ),
            maxLines = 5,
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank() && !isStreaming) {
                        onSend(text.trim())
                        text = ""
                    }
                }
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = DroshSurfaceVariant,
                unfocusedContainerColor = DroshSurfaceVariant,
                focusedTextColor = DroshText,
                unfocusedTextColor = DroshText,
                cursorColor = DroshPrimary,
                focusedPlaceholderColor = DroshTextMuted,
                unfocusedPlaceholderColor = DroshTextMuted
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.width(8.dp))

        if (isStreaming) {
            TextButton(
                onClick = { onCancel() },
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                Text("Stop", color = DroshTextSecondary, fontSize = 13.sp)
            }
        } else {
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text.trim())
                        text = ""
                    }
                },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = DroshPrimary.copy(alpha = 0.15f)
                )
            ) {
                Text(
                    text = "Send",
                    color = DroshPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun AgentMessageRow(message: ChatMessage) {
    when (message) {
        is ChatMessage.UserMessage -> UserMessageRow(message)
        is ChatMessage.AgentText -> AgentTextMessage(message)
        is ChatMessage.Thinking -> ThinkingMessage(message)
        is ChatMessage.ToolCall -> ToolCallMessage(message)
        is ChatMessage.ToolResult -> ToolResultMessage(message)
        is ChatMessage.BashCommand -> BashCommandMessage(message)
        is ChatMessage.Error -> ErrorMessage(message)
    }
}

@Composable
fun UserMessageRow(message: ChatMessage.UserMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = message.text,
            color = DroshText,
            fontSize = 14.sp,
            modifier = Modifier
                .background(DroshPrimary, RoundedCornerShape(16.dp))
                .padding(12.dp, 8.dp)
                .fillMaxWidth(0.8f),
            softWrap = true
        )
    }
}

@Composable
fun AgentTextMessage(message: ChatMessage.AgentText) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = message.text,
            color = DroshText,
            fontSize = 14.sp,
            modifier = Modifier
                .background(DroshSurface, RoundedCornerShape(16.dp))
                .padding(12.dp, 8.dp)
                .fillMaxWidth(0.8f),
            softWrap = true
        )
    }
}

@Composable
fun ThinkingMessage(message: ChatMessage.Thinking) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Thinking",
                    color = DroshTextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                if (message.isThinking) {
                    Text(
                        text = " · ${formatElapsed(message.elapsedMs)}",
                        color = DroshTextDisabled,
                        fontSize = 11.sp
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = message.text,
                    color = DroshTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ToolCallMessage(message: ChatMessage.ToolCall) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column {
            Text(
                text = "Tool: ${message.toolName}",
                color = DroshPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = message.args.entries.joinToString(", ") { "${it.key}: ${it.value}" },
                color = DroshTextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun ToolResultMessage(message: ChatMessage.ToolResult) {
    val result = message.result
    val resultColor = when (result) {
        is ToolResult.Success -> DroshText
        is ToolResult.Error -> DroshError
        is ToolResult.Cancelled -> DroshTextMuted
        is ToolResult.AwaitingApproval -> DroshTextMuted
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column {
            Text(
                text = result.toResponseString().take(80),
                color = resultColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun BashCommandMessage(message: ChatMessage.BashCommand) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column {
            Text(
                text = "$ " + message.command,
                color = DroshPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            if (message.output.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = message.output,
                        color = DroshTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(DroshSurfaceVariant, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                            .fillMaxWidth(0.9f)
                    )
                }
            }
            if (message.exitCode != null) {
                Text(
                    text = "Exit code: ${message.exitCode}",
                    color = if (message.exitCode == 0) DroshTextMuted else DroshError,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun ErrorMessage(message: ChatMessage.Error) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "⚠ ${message.message}",
            color = DroshError,
            fontSize = 12.sp
        )
    }
}

@Composable
fun formatElapsed(ms: Long): String {
    if (ms < 1000) return "${ms}ms"
    val sec = ms / 1000.0
    return String.format("%.1fs", sec)
}