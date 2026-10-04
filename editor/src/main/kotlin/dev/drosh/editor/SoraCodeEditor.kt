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

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.drosh.core.DroshPalette
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * sora-editor's `CodeEditor`, mounted in Compose.
 *
 * The widget is a plain Android `View`, so it is hosted the same way
 * `TerminalScreen` already hosts `TerminalView`: `AndroidView` plus an imperative
 * handle. sora-editor's own Compose guide uses this shape too.
 *
 * ### What is delegated and what is ours
 *
 * Everything about editing — the text buffer, the cursor, selection,
 * undo/redo, auto-indent, word wrap, rendering, and the syntax highlighter —
 * belongs to sora-editor. None of it is reimplemented. What this file adds is
 * only what an app has to add anyway: colour, and a release.
 *
 * ### Why the state is remembered rather than hoisted
 *
 * `remember` keeps one editor instance per composition, which is what sora
 * requires: it owns background threads for syntax analysis and holds a
 * language object that "should serve for only one editor". Recreating it on
 * every recomposition would leak a thread per frame.
 *
 * The colours are applied inside `apply`, not as parameters, because they come
 * from a raw `Int` palette in `:core` and never change while the screen is up.
 */
@Composable
fun SoraCodeEditor(
    /** Set once, at creation. Changing it afterwards does not reload the editor. */
    initialText: String,
    modifier: Modifier = Modifier,
    onTextChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val latestOnTextChanged by rememberUpdatedState(onTextChanged)

    val editor = remember(context) {
        CodeEditor(context).apply {
            setText(initialText)
            typefaceText = android.graphics.Typeface.MONOSPACE
            setTextSize(TEXT_SIZE_SP)
            // Paint the widget from the same constants the terminal uses, so the
            // editor does not read as a different app pasted into Drosh.
            setEditorBackgroundColor(DroshPalette.DROSH_BACKGROUND.toArgb())
            setColorScheme(DroshEditorColorScheme())
        }
    }

    // Release is mandatory: the editor holds a background thread for syntax
    // analysis, and sora-editor's docs are explicit that a leaked editor must
    // not be reused afterwards. Without this the thread outlives the screen.
    DisposableEffect(editor) {
        onDispose { editor.release() }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(DroshPalette.DROSH_BACKGROUND.toComposeColor()),
        factory = { editor },
        update = { view ->
            view.setTextColor(DroshPalette.DROSH_TEXT_PRIMARY.toArgb())
            // The cursor position is readable synchronously, so this gives the
            // dirty-state tracking without a per-keystroke recomposition of the
            // whole screen.
            latestOnTextChanged(view.text?.toString().orEmpty())
        },
    )
}

private const val TEXT_SIZE_SP = 14f