/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * Editor size in sp.
 *
 * Matches the terminal's own text size default so the two do not look like
 * different applications sharing a screen.
 */
private const val EDITOR_TEXT_SIZE_SP = 14f

/**
 * A live reference to the mounted editor, plus whether the user has changed it.
 *
 * The editor's text is pulled on demand rather than pushed per keystroke.
 * sora-editor fires a [ContentChangeEvent] on every edit, and reading the whole
 * buffer on each one would turn typing into an O(file) operation — at the 2 MiB
 * [dev.drosh.domain.file.GuestFileLimits.MAX_EDIT_BYTES] ceiling that is a visible
 * stutter. An event only flips a boolean; the buffer is read when someone
 * actually needs it, which is saving and leaving.
 *
 * Not a ViewModel: it holds a `View` and a live event subscription, so it
 * cannot survive a configuration change, and it is created and destroyed with
 * the composition that owns the editor.
 */
@Stable
class SoraEditorHandle internal constructor() {

    internal var editor: CodeEditor? = null

    /** True once the user has edited the buffer and it differs from disk. */
    var dirty by mutableStateOf(false)
        internal set

    /** The buffer as it stands. Reads the whole document; do not call per keystroke. */
    fun currentText(): String = editor?.text?.toString().orEmpty()

    /**
     * Replaces the buffer, e.g. after a save or a reload from disk.
     *
     * The event this triggers is [ContentChangeEvent.ACTION_SET_NEW_TEXT], which
     * [SoraCodeEditor] deliberately ignores, so the document does not come back
     * marked dirty and there is no flag to reset around this call.
     */
    fun applyExternalText(text: String) {
        val view = editor ?: return
        if (view.text?.toString() == text) return
        view.setText(text)
        dirty = false
    }
}

@Composable
fun rememberSoraEditorHandle(): SoraEditorHandle = remember { SoraEditorHandle() }

/**
 * sora-editor's `CodeEditor`, mounted in Compose.
 *
 * The widget is a plain Android `View`, so it is hosted exactly the way
 * `TerminalScreen` already hosts `TerminalView`: `AndroidView` over an
 * imperative handle. sora-editor's own Compose guide uses this shape as well.
 *
 * ### What is sora's and what is Drosh's
 *
 * The buffer, cursor, selection, undo/redo, auto-indent, word wrap, rendering
 * and syntax analysis are all sora's, unmodified. This file contributes the
 * three things an app has to contribute regardless of which editor widget it
 * picks: Drosh's colours, a lifetime that releases the widget, and a dirty flag.
 *
 * ### Why one instance per composition
 *
 * `remember`, not a parameter. sora-editor documents that a `Language` instance
 * "should serve for only one editor" and that the widget owns background threads;
 * recreating it on recomposition would leak a thread per frame.
 */
@Composable
fun SoraCodeEditor(
    initialText: String,
    modifier: Modifier = Modifier,
    handle: SoraEditorHandle = rememberSoraEditorHandle(),
) {
    val context = LocalContext.current
    val editor = remember(context) {
        CodeEditor(context).apply {
            setText(initialText)
            setTypefaceText(Typeface.MONOSPACE)
            setTextSize(EDITOR_TEXT_SIZE_SP)
            setColorScheme(droshEditorColorScheme())
        }
    }

    SideEffect { handle.editor = editor }

    // Unsubscribe before release: the receipt holds a reference into the
    // editor's event manager, and releasing the editor underneath a live
    // subscription is how a listener ends up firing against a dead widget.
    DisposableEffect(editor) {
        // EventReceiver spelled out rather than passed as a bare lambda:
        // subscribeEvent and subscribeAlways are overloads on two different
        // functional interfaces, and a lambda would leave the choice to
        // overload resolution.
        val receiver = EventReceiver<ContentChangeEvent> { event, _ ->
            // ACTION_SET_NEW_TEXT is how setText() reports itself. Ignoring it is
            // what keeps loading a file — or pushing a freshly saved buffer back
            // into the widget — from marking the document dirty, with no flag to
            // reset around those calls. INSERT and DELETE cover typing and
            // undo/redo alike, which is the behaviour we want: undoing back to
            // the saved state still counts as changed until the user saves.
            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                handle.dirty = true
            }
        }
        val receipt = editor.subscribeEvent(ContentChangeEvent::class.java, receiver)
        onDispose {
            receipt.unsubscribe()
            editor.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { editor },
        update = { /* Text is imperative; nothing to push on recomposition. */ },
    )
}
