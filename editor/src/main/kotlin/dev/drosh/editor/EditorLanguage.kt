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

/**
 * Maps a filename to a TextMate grammar scope.
 *
 * Scopes are the strings in `textmate/languages.json`, which is also the only
 * list of grammars actually bundled. A scope absent here is not an error — it
 * means the file opens as plain text, which is the right outcome for a binary
 * or an unknown format rather than a wrong guess at a language.
 */
object EditorLanguage {

    /**
     * Extension (no dot, lower case) to grammar scope.
     *
     * Written by extension rather than by file name because that is what a user
     * expects to drive highlighting: renaming `notes.txt` to `notes.md` should
     * change how it is coloured without the path being interpreted.
     *
     * Shell is included deliberately: a large share of what gets edited on a
     * phone in Drosh is a script, not source.
     */
    private val BY_EXTENSION: Map<String, String> = mapOf(
        // shells
        "sh" to "source.shell",
        "bash" to "source.shell",
        "zsh" to "source.shell",
        "fish" to "source.shell",

        // data / config
        "json" to "source.json",
        "yaml" to "source.yaml",
        "yml" to "source.yaml",

        // jvm / mobile — Drosh's own audience
        "kt" to "source.kotlin",
        "kts" to "source.kotlin",
        "java" to "source.java",

        // web
        "js" to "source.js",
        "mjs" to "source.js",
        "cjs" to "source.js",
        "jsx" to "source.js",
        "ts" to "source.ts",
        "tsx" to "source.ts",

        // native / systems
        "c" to "source.cpp",
        "cc" to "source.cpp",
        "cpp" to "source.cpp",
        "cxx" to "source.cpp",
        "h" to "source.cpp",
        "hpp" to "source.cpp",
        "cs" to "source.cs",

        // docs
        "md" to "text.html.markdown",
        "markdown" to "text.html.markdown",
    )

    /** Whole filenames that carry their own grammar regardless of extension. */
    private val BY_NAME: Map<String, String> = mapOf(
        "dockerfile" to "source.shell",
        "containerfile" to "source.shell",
        ".bashrc" to "source.shell",
        ".zshrc" to "source.shell",
        ".profile" to "source.shell",
    )

    /**
     * The grammar scope for [fileName], or null when there is none.
     *
     * [fileName] is a basename, not a path — the extension of a path and of its
     * last segment are the same, but taking the basename first means a directory
     * called `foo.sh/` cannot make a file inside it look like a shell script.
     */
    fun scopeFor(fileName: String): String? {
        val base = fileName.substringAfterLast('/').lowercase()
        BY_NAME[base]?.let { return it }
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return null
        return BY_EXTENSION[base.substring(dot + 1)]
    }
}