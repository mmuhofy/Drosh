package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.LocalAgentGlass
import dev.drosh.ui.agent.components.glassSurface

/**
 * The input, on its own.
 *
 * It lives here rather than inside [AgentChatScreen] because it is a screen of
 * its own: the draft, the keyboard, the send-or-stop swap and the `+` sheet are
 * one behaviour, and keeping them in a file the size of the transcript made the
 * composer look like a detail rather than the thing the user came for.
 *
 * ## It floats, and there is nothing under it
 *
 * The box is a shadow, a hairline of gradient, and the text. No fill: a filled
 * composer is a panel welded to the bottom of the screen — the transcript runs up
 * to it and stops, and the input reads as chrome rather than as the thing you type
 * into. Transparent means the transcript and the shadow alone carry the shape.
 *
 * That is why it is one of the two glass surfaces on this screen. The other is
 * the pill row at the top; see [dev.drosh.ui.agent.components.glassSurface]. Both
 * are surfaces over content, and the glass is the depth that says so.
 *
 * ## The stop button is here
 *
 * It replaces send while a run is going, beside the text that is generating,
 * rather than in the top row where it would move a control the user had just
 * learned the position of.
 *
 * @param draft the text, owned by the caller so a bubble's "Edit" can put a
 *        message the user already sent back into the box.
 * @param focusRequest incremented by whoever wants the keyboard raised after
 *        filling the draft.
 * @param canSendWithNothingTyped a prompt with nothing typed is still worth
 *        sending when a picture is attached: a screenshot is a question.
 */
@Composable
fun AgentInputBar(
    running: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Incremented by whoever wants the keyboard raised after filling the draft. */
    focusRequest: Int = 0,
    /** Opens the `+` sheet. */
    onAttachClick: () -> Unit = {},
    /** Whether a prompt with nothing typed in is still worth sending. */
    canSendWithNothingTyped: Boolean = false,
) {
    val sendCd = stringResource(R.string.agent_send)
    val stopCd = stringResource(R.string.agent_stop)
    val attachCd = stringResource(R.string.agent_attach_cd)
    val enabled = !running
    val canSend = (draft.isNotBlank() || canSendWithNothingTyped) && enabled

    // A focus requester the screen drives. Editing a sent message means the
    // keyboard should come up — the user is mid-thought, and a filled box with
    // the keyboard down is a box they have to tap before they can type in.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // The same liquid glass as the pills, at a larger radius. One surface for
        // the whole app's controls, so the composer and the pill row read as the
        // same material rather than as two things that happen to be on the same
        // screen.
        //
        // It floats, and it is the one surface on this screen that does: a real
        // shadow beneath it, so it reads as sitting over the transcript rather
        // than as a bar the transcript stops at. The border is drawn here and
        // nowhere else outside the pills — an outline is what says "this is a
        // control", and the composer is the one card-sized control on screen.
        val glass = LocalAgentGlass.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .glassSurface(
                    glass,
                    pressed = false,
                    shape = RoundedCornerShape(20.dp),
                    edged = true,
                    // No fill at all. See the file note: a filled composer is a
                    // panel the transcript stops at, and this one is meant to
                    // float over it with the shadow carrying the whole box.
                    fill = Color.Transparent,
                )
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Column {
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            text = if (enabled) {
                                stringResource(R.string.agent_composer_hint)
                            } else {
                                stringResource(R.string.agent_composer_busy)
                            },
                            fontSize = 14.5.sp,
                            color = DroshTextMuted,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = enabled,
                        singleLine = false,
                        maxLines = 6,
                        textStyle = TextStyle(fontSize = 14.5.sp, color = DroshText),
                        cursorBrush = SolidColor(DroshPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The `+`, at the far end of the row from send. 48dp of touch
                    // target around a 36dp circle, the same box the pills above
                    // use, so the two ends of the composer are the same size to a
                    // finger.
                    //
                    // Left rather than next to send because attaching and sending
                    // are different acts — one adds to the prompt, the other
                    // commits it — and putting them shoulder to shoulder is how a
                    // mis-tap sends a half-written prompt.
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DroshSurfaceHigh)
                                .clickable(enabled = enabled, onClick = onAttachClick)
                                .semantics { contentDescription = attachCd },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = DroshIcons.Plus,
                                contentDescription = null,
                                tint = DroshTextMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 36dp drawn inside a 48dp box, to sit with the 44dp pills
                    // above. At 30dp the primary action on the screen was smaller
                    // than the icons in the row above it, which read as a
                    // deliberate demotion.
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (running) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(DroshError)
                                    .clickable(onClick = onStop)
                                    .semantics { contentDescription = stopCd },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DroshIcons.Square,
                                    contentDescription = null,
                                    tint = DroshOnPrimary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) DroshPrimary else DroshSurfaceHigh)
                                    .clickable(enabled = canSend) {
                                        onSend(draft)
                                    }
                                    .semantics { contentDescription = sendCd },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = DroshIcons.Send,
                                    contentDescription = null,
                                    tint = if (canSend) DroshOnPrimary else DroshTextMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
