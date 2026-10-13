package dev.drosh.ui.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTile
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.agent.components.glassRow
import dev.drosh.ui.settings.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// ── what an attachment is ──────────────────────────────────────────────────

/**
 * One thing the user added to the prompt they are about to send.
 *
 * ## Why these are objects and not text in the composer's box
 *
 * A photo arrives as a base64 data URL — a few hundred kilobytes of characters
 * for one image. Put that in the draft, which is a `BasicTextField`'s value,
 * and every keystroke, every re-layout and every recomposition of the composer
 * carries it, with the transcript recomposing alongside. So the draft stays the
 * words, and an attachment is a value held next to it: a chip above the box,
 * rendered into the prompt at the moment it is sent.
 *
 * That also puts the image where a model that accepts document content expects
 * to find it — inside the prompt — rather than through a channel this app does
 * not have.
 */
sealed interface ChatAttachment {

    /** What the chip says. */
    val label: String

    /** What goes into the prompt, below the user's own words. */
    fun renderIntoPrompt(): String

    /** A photo, downscaled and re-encoded so it fits in a prompt. */
    data class Image(
        val name: String,
        val dataUrl: String,
        val pixelSize: String,
    ) : ChatAttachment {
        override val label: String get() = name
        override fun renderIntoPrompt(): String = buildString {
            append("Attached image: ").append(name)
            append(" (").append(pixelSize).append(')').append('\n')
            append(dataUrl)
        }
    }

    /**
     * A text file, carried in whole.
     *
     * Whole rather than clipped, because a model asked to fix a file needs all
     * of it — but capped, because a five-megabyte log pasted into a prompt
     * costs more than the question it was attached to answer.
     */
    data class Text(
        val name: String,
        val mime: String,
        val content: String,
        val truncated: Boolean,
    ) : ChatAttachment {
        override val label: String get() = name
        override fun renderIntoPrompt(): String = buildString {
            append("Attached file: ").append(name)
            if (mime.isNotBlank()) append(" (").append(mime).append(')')
            append(" — ").append(content.length).append(" characters")
            if (truncated) append(", clipped at ").append(MAX_TEXT_CHARS)
            append("\n\n")
            append(content)
        }
    }

    /**
     * A binary file: a PDF, an archive, anything that is not text.
     *
     * Base64 in a data URL, which is what a model reading document content
     * expects to find. Capped hard at [MAX_BINARY_BYTES] — past that the prompt
     * stops being a prompt.
     */
    data class Binary(
        val name: String,
        val mime: String,
        val byteCount: Int,
        val dataUrl: String,
    ) : ChatAttachment {
        override val label: String get() = name
        override fun renderIntoPrompt(): String = buildString {
            append("Attached binary file: ").append(name)
            if (mime.isNotBlank()) append(" (").append(mime).append(')')
            append(", ").append(byteCount / 1024).append(" KB, base64\n")
            append(dataUrl)
        }
    }

    /** The last command this chat ran, with whatever it printed. */
    data class SessionLog(
        val command: String,
        val output: String,
    ) : ChatAttachment {
        override val label: String get() = command
        override fun renderIntoPrompt(): String = buildString {
            append("The last command this chat ran, and its output:\n\n")
            append("$ ").append(command).append('\n')
            append(output)
        }
    }

    companion object {
        /** Past this the prompt stops being a prompt. */
        const val MAX_BINARY_BYTES = 5 * 1024 * 1024

        /** Past this a pasted file is longer than the question about it. */
        const val MAX_TEXT_CHARS = 120_000

        /**
         * The longest edge an attached photo is reduced to.
         *
         * Under the 2000px the platform's own media ceiling allows, on purpose:
         * a 2000px JPEG is roughly a megabyte of base64 in every request this
         * conversation ever makes again, for detail no model reads. 1600 is
         * where the extra pixels stop changing what the model can say.
         */
        const val MAX_IMAGE_EDGE = 1600
    }
}

// ── the sheet ──────────────────────────────────────────────────────────────

/**
 * The composer's `+`: everything that can be put into the next prompt.
 *
 * ## Why a sheet and not a row of pills under the box
 *
 * There are six things behind this button and one of them is a camera. Six
 * pills is a second toolbar on a screen that already has one, and it would take
 * 48dp from the transcript permanently to advertise a camera most of the time
 * has no app for. A sheet costs nothing until it is opened, and it has room for
 * the sentence each option needs to explain itself.
 *
 * ## Why it really drags
 *
 * The grabber under this sheet is not decoration: `detectVerticalDragGestures`
 * on the sheet itself, the same pattern [TerminalHistorySheet] and
 * [AgentToolDetailSheet] use, and the sheet follows the finger before it
 * leaves. A grabber over a sheet that ignores the gesture teaches the user that
 * this app's affordances are decorative.
 *
 * ## The two switches are not decoration either
 *
 * Web search and reasoning effort are choices about this conversation, so they
 * live where a conversation is written. Both are persisted into `settings.toml`
 * under `[agent]`, which is where a shell or the agent reads them back — a
 * preference that only survives in a `StateFlow` is one the user loses at the
 * next process start.
 */
@Composable
fun AgentAttachSheet(
    webSearchEnabled: Boolean,
    onWebSearchChange: (Boolean) -> Unit,
    /** What is in effect now; null is the model's own default. */
    effort: String?,
    /** The values this model accepts. Empty means it has no effort choice. */
    efforts: List<String>,
    onEffortChange: (String?) -> Unit,
    /** The last shell command in this chat, or null when none has run. */
    sessionLog: ChatAttachment.SessionLog?,
    onAttach: (ChatAttachment) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val closeCd = stringResource(R.string.action_close)

    // Read and shrink off the main thread: a multi-megabyte decode on the UI
    // thread is a dropped frame on the very screen that just opened this sheet.
    var preparing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun attach(block: suspend () -> ChatAttachment?) {
        scope.launch {
            preparing = true
            problem = null
            val built = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
            preparing = false
            if (built == null) {
                problem = context.getString(R.string.agent_attach_unreadable)
            } else {
                onAttach(built)
                onDismiss()
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { u -> attach { readImage(context, u) } } }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { u -> attach { readFile(context, u) } } }

    // The preview rather than the full-size capture: TakePicture writes to a
    // file this app would then own a URI permission for and have to clean up,
    // while a preview is one bitmap the camera app already made the right size.
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
    ) { bitmap -> bitmap?.let { b -> attach { imageFrom(b, CAMERA_NAME) } } }

    // No runtime permission is requested, deliberately. ACTION_IMAGE_CAPTURE
    // needs none, and *declaring* CAMERA would oblige the app to hold a grant it
    // has no other use for. What the camera row really needs to know is whether
    // a camera exists at all — a headless build, a device with no camera app, a
    // work profile — and there the row is drawn disabled rather than opening a
    // picker that cannot return.
    val cameraAvailable = remember(context) { context.hasCameraApp() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DroshSurfaceHigh.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = closeCd },
        )

        val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        val rise by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
            label = "attachSheetRise",
        )

        var dragDown by remember { mutableFloatStateOf(0f) }
        val dismissAt = 160f

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, (((1f - rise) * 420) + dragDown).roundToInt()) }
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(shape)
                .background(DroshSurfaceHigh)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (dragDown > dismissAt) onDismiss() else dragDown = 0f
                        },
                        onDragCancel = { dragDown = 0f },
                    ) { _, delta ->
                        if (delta > 0f) dragDown += delta
                        else if (dragDown > 0f) dragDown = (dragDown + delta).coerceAtLeast(0f)
                    }
                }
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DroshText.copy(alpha = 0.2f)),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.agent_attach_title),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = DroshText,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.action_close),
                    fontSize = 13.sp,
                    color = DroshTextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(DroshBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 20.dp),
            ) {
                SheetLabel(stringResource(R.string.agent_attach_group_attach))

                AttachRow(
                    icon = ImageGlyph,
                    title = stringResource(R.string.agent_attach_image),
                    subtitle = stringResource(R.string.agent_attach_image_desc),
                    enabled = !preparing,
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )

                AttachRow(
                    icon = FileGlyph,
                    title = stringResource(R.string.agent_attach_file),
                    subtitle = stringResource(R.string.agent_attach_file_desc),
                    enabled = !preparing,
                    onClick = { fileLauncher.launch(ANY_MIME) },
                )

                AttachRow(
                    icon = CameraGlyph,
                    title = stringResource(R.string.agent_attach_camera),
                    subtitle = if (cameraAvailable) {
                        stringResource(R.string.agent_attach_camera_desc)
                    } else {
                        stringResource(R.string.agent_attach_camera_unavailable)
                    },
                    enabled = cameraAvailable && !preparing,
                    onClick = { cameraLauncher.launch(null) },
                )

                val log = sessionLog
                AttachRow(
                    icon = DroshIcons.Terminal,
                    title = stringResource(R.string.agent_attach_log),
                    subtitle = if (log == null) {
                        stringResource(R.string.agent_attach_log_empty)
                    } else {
                        stringResource(R.string.agent_attach_log_desc)
                    },
                    enabled = log != null && !preparing,
                    onClick = {
                        val taken = log ?: return@AttachRow
                        onAttach(ChatAttachment.SessionLog(taken.command, taken.output))
                        onDismiss()
                    },
                )

                if (preparing) {
                    SheetNote(stringResource(R.string.agent_attach_preparing), DroshTextMuted)
                }
                problem?.let {
                    SheetNote(it, DroshError)
                }

                Spacer(Modifier.height(14.dp))
                SheetLabel(stringResource(R.string.agent_attach_group_settings))

                // One row, one switch, and a sentence that says what turning it
                // off does. That sentence is the point: with the switch alone, a
                // model that stops being able to search gives no hint that a
                // setting is what changed.
                SheetSwitchRow(
                    title = stringResource(R.string.agent_attach_web_search),
                    subtitle = if (webSearchEnabled) {
                        stringResource(R.string.agent_attach_web_search_on)
                    } else {
                        stringResource(R.string.agent_attach_web_search_off)
                    },
                    checked = webSearchEnabled,
                    onCheckedChange = onWebSearchChange,
                )

                EffortSegmented(
                    selected = effort,
                    efforts = efforts,
                    onSelect = onEffortChange,
                )
            }
        }
    }
}

// ── pieces ─────────────────────────────────────────────────────────────────

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        color = DroshTextMuted,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetNote(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = color,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp),
    )
}

@Composable
private fun AttachRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassRow(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(DroshTilePressed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) DroshTextSecondary else DroshTextMuted,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) DroshText else DroshTextMuted,
            )
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = DroshTextMuted,
            )
        }
    }
}

@Composable
private fun SheetSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassRow(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DroshText,
            )
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = DroshTextMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Reasoning effort as one row of segments.
 *
 * Segmented rather than a dropdown because there are three answers and the
 * whole point is choosing between them in one tap — a menu turns a two-decision
 * choice into open, find, tap. When the model offers no effort at all the row
 * says so rather than disappearing: a control that is missing reads as a control
 * that has not loaded yet.
 */
@Composable
private fun EffortSegmented(
    selected: String?,
    efforts: List<String>,
    onSelect: (String?) -> Unit,
) {
    val levels = efforts.filter { it.lowercase() in KNOWN_EFFORT_LEVELS }.ifEmpty { efforts }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassRow(RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.agent_attach_effort),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = DroshText,
        )
        Spacer(Modifier.height(4.dp))

        if (levels.isEmpty()) {
            Text(
                text = stringResource(R.string.agent_attach_effort_none),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = DroshTextMuted,
            )
            return@Column
        }

        val order = levels.sortedBy { KNOWN_EFFORT_LEVELS.indexOf(it.lowercase()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clip(CircleShape)
                .background(DroshTilePressed)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            order.forEach { level ->
                val on = selected == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(CircleShape)
                        .background(if (on) DroshPrimary else Color.Transparent)
                        .clickable { onSelect(if (on) null else level) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = effortLabel(level),
                        fontSize = 12.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) DroshOnPrimary else DroshTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun effortLabel(level: String): String = when (level.lowercase()) {
    "low", "minimal" -> stringResource(R.string.agent_attach_effort_low)
    "high", "xhigh", "max" -> stringResource(R.string.agent_attach_effort_high)
    else -> stringResource(R.string.agent_attach_effort_medium)
}

/** Effort values the segmented control can name, cheapest first. */
private val KNOWN_EFFORT_LEVELS = listOf("minimal", "low", "medium", "high", "xhigh", "max")

// ── the chips above the composer ───────────────────────────────────────────

/**
 * What has been attached to the next prompt, as removable chips.
 *
 * Above the box rather than inside it, so the composer keeps the height it has
 * whether there is one attachment or nine, and so a few hundred kilobytes of
 * base64 is never the value of a text field.
 */
@Composable
fun AgentAttachmentChips(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val removeCd = stringResource(R.string.agent_attach_remove_cd)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(DroshTile)
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = attachment.label,
                    fontSize = 11.5.sp,
                    fontFamily = LocalFontSet.current.mono,
                    color = DroshTextSecondary,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemove(attachment) }
                        .semantics { contentDescription = removeCd },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = DroshIcons.X,
                        contentDescription = null,
                        tint = DroshTextMuted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

// ── reading a URI ──────────────────────────────────────────────────────────

/** Every MIME type: a list of what this app can render would be a guess. */
private val ANY_MIME = arrayOf("*/*")

private const val CAMERA_NAME = "camera.jpg"

/** Photos are decoded at up to twice the target edge before the final scale. */
private const val MAX_IMAGE_BYTES = 24 * 1024 * 1024

/** 82 is where JPEG artifacts stop being visible at 1600px. */
private const val JPEG_QUALITY = 82

/** Mimes that are text despite not saying so. */
private val TEXTUAL_MIME = setOf(
    "application/json",
    "application/ld+json",
    "application/xml",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/javascript",
    "application/sql",
)

/**
 * A picked photo, shrunk to something a prompt can carry.
 *
 * Downscaled in two steps rather than one: `inSampleSize` only takes powers of
 * two, so a single decode of a 4000px photo would still leave a 2000px bitmap
 * to hold, and the second step is where the exact edge is enforced.
 */
private suspend fun readImage(context: Context, uri: Uri): ChatAttachment? =
    withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytesBounded(MAX_IMAGE_BYTES) }
            ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val edge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / sample > ChatAttachment.MAX_IMAGE_EDGE * 2) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@withContext null

        val built = imageFrom(decoded, context.displayNameOf(uri))
        decoded.recycle()
        built
    }

/** Re-encode a bitmap as a JPEG data URL under [ChatAttachment.MAX_IMAGE_EDGE]. */
private fun imageFrom(bitmap: Bitmap, name: String): ChatAttachment {
    val scaled = bitmap.scaledToFit(ChatAttachment.MAX_IMAGE_EDGE)
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    val encoded = "data:image/jpeg;base64," +
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    val size = "${scaled.width}x${scaled.height}"
    if (scaled !== bitmap) scaled.recycle()
    return ChatAttachment.Image(
        name = name.ifBlank { "image.jpg" },
        dataUrl = encoded,
        pixelSize = size,
    )
}

/**
 * A picked file: whole if it is text, base64 if it is not.
 *
 * The split is the mime type rather than an extension list, because the caller
 * of the picker is a person and the file they chose already knows what it is.
 */
private suspend fun readFile(context: Context, uri: Uri): ChatAttachment? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = context.displayNameOf(uri)
        val mime = resolver.getType(uri).orEmpty()

        if (mime.startsWith("text/") || mime in TEXTUAL_MIME) {
            val bytes = resolver.openInputStream(uri)?.use {
                it.readBytesBounded(ChatAttachment.MAX_TEXT_CHARS.toInt() * 4)
            } ?: return@withContext null
            val text = bytes.toString(Charsets.UTF_8)
            return@withContext ChatAttachment.Text(
                name = name,
                mime = mime,
                content = text.take(ChatAttachment.MAX_TEXT_CHARS),
                truncated = text.length > ChatAttachment.MAX_TEXT_CHARS,
            )
        }

        // An image that arrived as a file rather than through the picker is
        // still an image, and gets the same treatment rather than a megabyte of
        // base64 the model will only guess at.
        if (mime.startsWith("image/")) return@withContext readImage(context, uri)

        val bytes = resolver.openInputStream(uri)?.use {
            it.readBytesBounded(ChatAttachment.MAX_BINARY_BYTES + 1)
        } ?: return@withContext null
        if (bytes.size > ChatAttachment.MAX_BINARY_BYTES) return@withContext null

        ChatAttachment.Binary(
            name = name,
            mime = mime,
            byteCount = bytes.size,
            dataUrl = "data:" + mime.ifBlank { "application/octet-stream" } +
                ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

/**
 * Read at most [limit] bytes.
 *
 * Callers that ask for `limit + 1` are how "this file is too large" is told from
 * "this file is exactly at the limit" without a second read to find out.
 */
private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read <= 0) break
        total += read
        if (total > limit) return null
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

private fun Bitmap.scaledToFit(maxEdge: Int): Bitmap {
    val edge = max(width, height)
    if (edge <= maxEdge) return this
    val ratio = maxEdge.toFloat() / edge
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).toInt().coerceAtLeast(1),
        (height * ratio).toInt().coerceAtLeast(1),
        true,
    )
}

private fun Context.displayNameOf(uri: Uri): String = runCatching {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull().orEmpty()

/**
 * Whether anything on this device can take a photo.
 *
 * Both checks, because they fail on different machines: a build with no camera
 * hardware fails the feature test, and a device with hardware but no camera app
 * — a stripped image, a work profile — fails the intent.
 */
private fun Context.hasCameraApp(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(packageManager) != null

// ── the three glyphs this sheet needs ──────────────────────────────────────

/**
 * The picture, the document and the camera, drawn here rather than taken from
 * `DroshIcons`.
 *
 * `DroshIcons` is the whole app's vocabulary, and this sheet is the only place
 * these three appear: adding them there would put three glyphs no other screen
 * can use into the shared set. Lucide's 24x24 grid, stroked the same way
 * `CursorVector` in that file is, and written in the same dialect it is
 * written in — `moveTo` and `lineTo` rather than a path string, because the
 * only parser that takes a path string is internal to the file that declares
 * it.
 */
private fun glyph(name: String, build: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathData { build() },
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    ).build()

private val ImageGlyph: ImageVector = glyph("attachImage") {
    // The frame.
    moveTo(3f, 3f)
    lineTo(21f, 3f)
    lineTo(21f, 21f)
    lineTo(3f, 21f)
    close()
    // The hill inside it.
    moveTo(21f, 15f)
    lineTo(16f, 10f)
    lineTo(5f, 21f)
    // The sun, as a square: an arc is what needed the parser in the first
    // place, and at 24dp a stroked square and a stroked circle are the same
    // mark to the eye.
    moveTo(8f, 8f)
    lineTo(10f, 8f)
    lineTo(10f, 10f)
    lineTo(8f, 10f)
    close()
}

private val FileGlyph: ImageVector = glyph("attachFile") {
    // The fold, then the page it folds.
    moveTo(14f, 3f)
    lineTo(14f, 9f)
    lineTo(20f, 9f)
    moveTo(20f, 9f)
    lineTo(20f, 21f)
    lineTo(4f, 21f)
    lineTo(4f, 3f)
    lineTo(14f, 3f)
    // Two lines of text.
    moveTo(8f, 13f)
    lineTo(16f, 13f)
    moveTo(8f, 17f)
    lineTo(16f, 17f)
}

private val CameraGlyph: ImageVector = glyph("attachCamera") {
    // The body, with the bump on top that says which way it faces.
    moveTo(3f, 8f)
    lineTo(8f, 8f)
    lineTo(9.5f, 5f)
    lineTo(14.5f, 5f)
    lineTo(16f, 8f)
    lineTo(21f, 8f)
    lineTo(21f, 19f)
    lineTo(3f, 19f)
    close()
    // The lens, square for the same reason the sun is.
    moveTo(9.5f, 10.5f)
    lineTo(14.5f, 10.5f)
    lineTo(14.5f, 16.5f)
    lineTo(9.5f, 16.5f)
    close()
}

// ── the session log as an attachment ───────────────────────────────────────

/**
 * The last shell command this chat ran, with whatever it printed.
 *
 * The other half of "show me what happened": the transcript row says the command
 * is over and the terminal sheet says it failed, but neither carries the output
 * back into a prompt, and re-typing a long pipeline to ask about it is the one
 * thing nobody wants to do on a phone.
 *
 * The newest finished call only. An older one is a command the agent has
 * already moved past, and attaching it would be answering a question the user
 * did not ask.
 */
fun lastSessionLog(messages: List<ChatMessage>): ChatAttachment.SessionLog? {
    val shell = messages.filterIsInstance<ChatMessage.ToolCall>().lastOrNull { call ->
        call.name == SHELL_TOOL &&
            (call.state == ToolCallState.Succeeded ||
                call.state == ToolCallState.Failed ||
                call.state == ToolCallState.Cancelled)
    } ?: return null

    val command = shell.summary.ifBlank { return null }
    val output = (shell.finalOutput ?: shell.output.joinToString("\n"))
        .lineSequence()
        .filterNot { it.startsWith(EXIT_PREFIX) }
        .joinToString("\n")
        .trim()
        .take(MAX_LOG_CHARS)

    return ChatAttachment.SessionLog(command = command, output = output)
}

/** The marker `ShellTool` puts in front of the exit status; not part of the output. */
private const val EXIT_PREFIX = "exit "

/** Past this the log is a transcript rather than something to reason about. */
private const val MAX_LOG_CHARS = 20_000

/** Must match `ShellTool.NAME`. */
private const val SHELL_TOOL = "shell"
