package com.iris.irisshell.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.iris.irisshell.design.system.OutfitFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.irisshell.domain.terminal.BootstrapError
import com.iris.irisshell.domain.terminal.BootstrapProgress
import com.iris.irisshell.domain.terminal.RecoveryAction
import com.iris.irisshell.ui.setup.BootstrapViewModel
import com.iris.irisshell.ui.setup.label
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Recovery screen rendered when `BootstrapProgress.isFailed` is true.
 *
 * Layout:
 *  - Red banner badge
 *  - "Setup failed at: <step>" title
 *  - Last error message (single line, ellipsised)
 *  - Inline log peek (last ~50 lines, monospace)
 *  - Four recovery buttons:
 *      1. Retry              → `viewModel.retry()`
 *      2. Re-download rootfs → `viewModel.reDownloadRootfs()`
 *      3. Report issue       → copies diagnostic bundle + opens GitHub issue
 *      4. Reset everything   → `viewModel.resetEverything()`
 */
@Composable
fun SetupRecoveryScreen(
    viewModel: BootstrapViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val liveLogs by viewModel.liveLogs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val error: BootstrapError = progress.error ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SetupPalette.Background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            FailureBadge(
                message = "Setup failed at: ${error.step.label()}",
                detail = error.shortMessage,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Last 50 log lines",
                color = SetupPalette.TextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
            LogPeek(lines = liveLogs.takeLast(50))

            Spacer(modifier = Modifier.height(28.dp))

            RecoveryActions(
                actions = error.recoveryActions,
                onRetry = { viewModel.retry() },
                onReDownload = { viewModel.reDownloadRootfs() },
                onReset = { viewModel.resetEverything() },
                onReport = { reportIssue(context, error, liveLogs.takeLast(50)) },
            )
        }
    }
}

@Composable
private fun FailureBadge(message: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(SetupPalette.Error.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "!",
                color = SetupPalette.Error,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = message,
                color = SetupPalette.Text,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = detail,
                color = SetupPalette.TextSecondary,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                ),
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun LogPeek(lines: List<String>) {
    val state = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp, max = 220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SetupPalette.Surface)
            .border(
                width = 1.dp,
                color = SetupPalette.Outline,
                shape = RoundedCornerShape(10.dp),
            ),
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No logs captured.",
                    color = SetupPalette.TextMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                state = state,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(items = lines, key = { it.hashCode() }) { line ->
                    Text(
                        text = line,
                        color = SetupPalette.MonoLog,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecoveryActions(
    actions: List<RecoveryAction>,
    onRetry: () -> Unit,
    onReDownload: () -> Unit,
    onReset: () -> Unit,
    onReport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        actions.forEach { action ->
            when (action) {
                RecoveryAction.Retry -> PrimaryRecoveryButton(
                    label = "Retry",
                    sublabel = "Try the same steps again with cached files.",
                    accent = SetupPalette.Primary,
                    onClick = onRetry,
                )
                RecoveryAction.ReDownloadRootfs -> PrimaryRecoveryButton(
                    label = "Re-download rootfs",
                    sublabel = "Delete cached rootfs and re-download from Ubuntu.",
                    accent = SetupPalette.Primary,
                    onClick = onReDownload,
                )
                RecoveryAction.ResetEverything -> PrimaryRecoveryButton(
                    label = "Reset everything",
                    sublabel = "Wipe ~/.iris-shell/ folder. Next launch will redownload all.",
                    accent = SetupPalette.Warning,
                    onClick = onReset,
                )
                RecoveryAction.ReportIssue -> PrimaryRecoveryButton(
                    label = "Report issue on GitHub",
                    sublabel = "Copies a diagnostic bundle to your clipboard.",
                    accent = SetupPalette.TextSecondary,
                    onClick = onReport,
                )
            }
        }
    }
}

@Composable
private fun PrimaryRecoveryButton(
    label: String,
    sublabel: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = SetupPalette.Text,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = accent,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sublabel,
                color = SetupPalette.TextMuted,
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}

// ───── Side-effects ─────

private fun reportIssue(
    context: Context,
    error: BootstrapError,
    lastLogLines: List<String>,
) {
    val body = buildString {
        appendLine("**Setup failed at:** ${error.step.label()}")
        appendLine("**Error:** ${error.shortMessage}")
        appendLine()
        appendLine("**Last 50 log lines:**")
        appendLine("```")
        lastLogLines.forEach { appendLine(it) }
        appendLine("```")
        appendLine()
        appendLine("**Device:** ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("**Android:** ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("**ABI:** ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Iris Shell diagnostic", body))
    Toast.makeText(context, "Diagnostic copied to clipboard", Toast.LENGTH_SHORT).show()

    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(GITHUB_ISSUES_URL),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val GITHUB_ISSUES_URL = "https://github.com/mmuhofy/IrisShell/issues/new"
