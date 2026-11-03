package dev.drosh.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.drosh.theme.DroshPrimary
import dev.drosh.terminal.session.EnginePane
import kotlinx.coroutines.delay

/**
 * The scrollbar: a modern bar on the pane's right edge, with a dot for every
 * prompt the shell announced (`OSC 133 ; A`).
 *
 * ## When it shows
 *
 * Only while there is something to scroll to. A terminal is read at the live
 * edge far more than it is panned, and a bar parked over the output forever is
 * chrome for a job the screen is not doing — so it appears when the viewport
 * leaves the edge, and hides a moment after it returns (or after the user
 * last dragged it).
 *
 * ## The dots
 *
 * Each dot is a prompt, placed along the bar by its distance above the live
 * edge — the one coordinate that keeps pointing at the same row while the
 * history grows. A drag or a tap jumps the viewport so the touched position
 * is near the top: an absolute scroll the engine clamps.
 */
@Composable
fun PromptScrollbar(
    pane: EnginePane,
    modifier: Modifier = Modifier,
) {
    /** The viewport's position, in rows above the live edge. */
    var offset by remember { mutableStateOf(0) }

    /** How much history there is above the viewport. */
    var history by remember { mutableStateOf(0) }

    /** The viewport's own height, in rows. */
    var rows by remember { mutableStateOf(0) }

    /** Where the prompts are, as rows above the live edge, oldest first. */
    var prompts by remember { mutableStateOf(IntArray(0)) }

    /** The last drag or tap, so the bar outlives it by a moment. */
    var lastTouchAt by remember { mutableLongStateOf(0L) }

    var dragging by remember { mutableStateOf(false) }

    // The engine's state is written by the frame loop, not by anyone here, so
    // it is sampled: a poll every fourth of a second is invisible next to the
    // picture itself, and a flow through the frame loop would wake compose
    // sixty times a second for nothing.
    LaunchedEffect(pane) {
        while (true) {
            if (pane.session.isAlive) {
                offset = pane.scrollOffset()
                history = pane.historySize()
                rows = pane.session.rows
                prompts = pane.promptRows()
            }
            delay(SCROLLBAR_POLL_MS)
        }
    }

    val visible = dragging || offset > 0 || history > 0

    // Leave only after the user stopped touching it: a bar that vanishes under
    // a finger mid-drag is a bar the user fights.
    LaunchedEffect(visible, dragging, lastTouchAt) {
        if (visible && !dragging) {
            delay(SCROLLBAR_IDLE_HIDE_MS)
            lastTouchAt = 0L
        }
    }

    // Jump so the touched position of the history sits near the top of the
    // viewport. The bar's pixels map to rows through the extent; a third of a
    // viewport is what lands the row the finger chose comfortably below the
    // top edge rather than flush against it.
    fun jumpTo(y: Float, height: Float) {
        val extent = history + rows
        if (extent <= 0) {
            return
        }
        val above = (1f - (y / height).coerceIn(0f, 1f)) * extent
        pane.scrollTo((above - rows * 0.34f).toInt().coerceIn(0, history))
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(SCROLLBAR_WIDTH)
                .pointerInput(pane) {
                    detectTapGestures { position ->
                        jumpTo(position.y, size.height.toFloat())
                        lastTouchAt = System.currentTimeMillis()
                    }
                }
                .pointerInput(pane) {
                    detectDragGestures(
                        onDragStart = { position ->
                            dragging = true
                            jumpTo(position.y, size.height.toFloat())
                        },
                        onDrag = { change, _ ->
                            jumpTo(change.position.y, size.height.toFloat())
                            lastTouchAt = System.currentTimeMillis()
                        },
                        onDragEnd = {
                            dragging = false
                            lastTouchAt = System.currentTimeMillis()
                        },
                    )
                },
        ) {
            val extent = history + rows
            if (extent <= 0 || rows <= 0) {
                return@Canvas
            }

            // The track: a thin pill on the pane's edge, out of the text's way.
            val trackWidth = size.width * 0.42f
            drawRoundRect(
                color = TrackColor,
                topLeft = Offset(size.width - trackWidth, 0f),
                size = Size(trackWidth, size.height),
                cornerRadius = CornerRadius(trackWidth),
            )

            // The thumb: the viewport's share of everything there is to see.
            val thumbHeight = (rows.toFloat() / extent * size.height)
                .coerceAtLeast(MIN_THUMB_PX)
                .coerceAtMost(size.height)
            val thumbTop = (offset.toFloat() / extent * size.height)
                .coerceIn(0f, size.height - thumbHeight)
            drawRoundRect(
                color = ThumbColor,
                topLeft = Offset(size.width - trackWidth, thumbTop),
                size = Size(trackWidth, thumbHeight),
                cornerRadius = CornerRadius(trackWidth),
            )

            // One dot per prompt, down the bar. A prompt whose row has fallen
            // out of the history is simply not drawn — the engine keeps a
            // capped number of marks, and the history is what it is.
            for (above in prompts) {
                val fraction = above.toFloat() / extent
                if (fraction > 1f) {
                    continue
                }
                val y = ((1f - fraction) * size.height)
                    .coerceIn(MIN_THUMB_PX * 0.5f, size.height - MIN_THUMB_PX * 0.5f)
                drawCircle(
                    color = DotColor,
                    radius = size.width * 0.16f,
                    center = Offset(size.width - trackWidth * 0.5f, y),
                )
            }
        }
    }
}

/** How often the engine's scroll state is sampled. */
private const val SCROLLBAR_POLL_MS = 250L

/** How long the bar lingers after the user last touched it or reached the edge. */
private const val SCROLLBAR_IDLE_HIDE_MS = 2_500L

private val SCROLLBAR_WIDTH = 28.dp

/** The thumb never gets so small it cannot be grabbed. */
private const val MIN_THUMB_PX = 48f

private val TrackColor = Color(0x33888888)

private val ThumbColor = Color(0x66AAAAAA)

private val DotColor = DroshPrimary
