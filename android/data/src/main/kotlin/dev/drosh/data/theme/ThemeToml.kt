package dev.drosh.data.theme

import dev.drosh.core.toml.TomlDocument
import dev.drosh.core.toml.TomlParseException
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.ThemePalette

/**
 * The theme file format: one [meta] block, one [palette] block, and the
 * same TOML the settings file already uses.
 *
 * The format is ours rather than a port of iTerm2's plist or Alacritty's
 * YAML because it has to be read by a phone and written by a person in
 * an editor inside the terminal: named colours, no nesting, comments
 * allowed. A theme file is small enough to read at a glance.
 *
 * ```toml
 * [meta]
 * id = "dracula"
 * name = "Dracula"
 * author = "Zeno Rocha"
 * description = "A dark theme with a purple accent."
 *
 * [palette]
 * background = "#282A36"
 * foreground = "#F8F8F2"
 * # ... twenty-two in all
 * ```
 */
internal object ThemeToml {

    fun render(theme: DroshTheme): String = buildString {
        append("# Drosh theme — ").append(theme.name).append('\n')
        append("# Install from the Theme Store, or paste this file anywhere.\n\n")
        append("[meta]\n")
        append("id = \"").append(theme.id.escape()).append("\"\n")
        append("name = \"").append(theme.name.escape()).append("\"\n")
        append("author = \"").append(theme.author.escape()).append("\"\n")
        append("description = \"").append(theme.description.escape()).append("\"\n\n")
        append("[palette]\n")
        with(theme.palette) {
            appendKey("background", background)
            appendKey("foreground", foreground)
            appendKey("cursor", cursor)
            appendKey("selection_background", selectionBackground)
            appendKey("selection_foreground", selectionForeground)
            appendKey("accent", accent)
            appendKey("black", black)
            appendKey("red", red)
            appendKey("green", green)
            appendKey("yellow", yellow)
            appendKey("blue", blue)
            appendKey("magenta", magenta)
            appendKey("cyan", cyan)
            appendKey("white", white)
            appendKey("bright_black", brightBlack)
            appendKey("bright_red", brightRed)
            appendKey("bright_green", brightGreen)
            appendKey("bright_yellow", brightYellow)
            appendKey("bright_blue", brightBlue)
            appendKey("bright_magenta", brightMagenta)
            appendKey("bright_cyan", brightCyan)
            appendKey("bright_white", brightWhite)
        }
    }

    /** Parses a theme file. Throws [TomlParseException] on a malformed one. */
    fun parse(text: String): DroshTheme {
        val document = TomlDocument.parse(text)
        val base = DroshTheme.DEFAULT
        val id = document.getStringOrNull("meta", "id")
            ?: throw TomlParseException("a theme needs [meta] id")
        val name = document.getString("meta", "name", id)
        val palette = with(document) {
            ThemePalette(
                background = getString("palette", "background", base.palette.background),
                foreground = getString("palette", "foreground", base.palette.foreground),
                cursor = getString("palette", "cursor", base.palette.cursor),
                selectionBackground = getString(
                    "palette", "selection_background", base.palette.selectionBackground,
                ),
                selectionForeground = getString(
                    "palette", "selection_foreground", base.palette.selectionForeground,
                ),
                accent = getString("palette", "accent", base.palette.accent),
                black = getString("palette", "black", base.palette.black),
                red = getString("palette", "red", base.palette.red),
                green = getString("palette", "green", base.palette.green),
                yellow = getString("palette", "yellow", base.palette.yellow),
                blue = getString("palette", "blue", base.palette.blue),
                magenta = getString("palette", "magenta", base.palette.magenta),
                cyan = getString("palette", "cyan", base.palette.cyan),
                white = getString("palette", "white", base.palette.white),
                brightBlack = getString("palette", "bright_black", base.palette.brightBlack),
                brightRed = getString("palette", "bright_red", base.palette.brightRed),
                brightGreen = getString("palette", "bright_green", base.palette.brightGreen),
                brightYellow = getString("palette", "bright_yellow", base.palette.brightYellow),
                brightBlue = getString("palette", "bright_blue", base.palette.brightBlue),
                brightMagenta = getString("palette", "bright_magenta", base.palette.brightMagenta),
                brightCyan = getString("palette", "bright_cyan", base.palette.brightCyan),
                brightWhite = getString("palette", "bright_white", base.palette.brightWhite),
            )
        }
        return DroshTheme(
            id = id,
            name = name,
            author = document.getString("meta", "author", base.author),
            description = document.getString("meta", "description", base.description),
            palette = palette,
        )
    }

    private fun StringBuilder.appendKey(key: String, value: String) {
        append(key).append(" = \"").append(value.escape()).append("\"\n")
    }

    private fun String.escape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
