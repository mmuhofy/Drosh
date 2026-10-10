package dev.drosh.domain.settings

/**
 * A terminal colour palette: the theme, and nothing else.
 *
 * Twenty-two colours is what a terminal actually draws — the sixteen ANSI
 * slots, the three that sit outside them, and the pair selection uses — so
 * that is what a theme carries. A theme with more is a config file; a theme
 * with less is a tint.
 *
 * The defaults are the colours Drosh has always drawn, so a settings file
 * written before themes existed renders exactly as it did.
 */
data class ThemePalette(
    val background: String = "#000000",
    val foreground: String = "#E8E8E8",
    val cursor: String = "#E8E8E8",
    val selectionBackground: String = "#16315D",
    val selectionForeground: String = "#101014",
    /** The app's tint, drawn from the palette's blue when a theme is applied. */
    val accent: String = "#3B82F6",
    val black: String = "#000000",
    val red: String = "#CD0000",
    val green: String = "#00CD00",
    val yellow: String = "#CDCD00",
    val blue: String = "#6495ED",
    val magenta: String = "#CD00CD",
    val cyan: String = "#00CDCD",
    val white: String = "#E5E5E5",
    val brightBlack: String = "#7F7F7F",
    val brightRed: String = "#FF0000",
    val brightGreen: String = "#00FF00",
    val brightYellow: String = "#FFFF00",
    val brightBlue: String = "#5C5CFF",
    val brightMagenta: String = "#FF00FF",
    val brightCyan: String = "#00FFFF",
    val brightWhite: String = "#FFFFFF",
) {
    companion object {
        val DEFAULT = ThemePalette()

        /**
         * The colours a grid logo shows, in the order a user reads them:
         * background first, then the eight the palette is judged by.
         */
        fun logoSwatchCount(): Int = 9
    }
}

/** A theme as the user chose it: its metadata and its palette. */
data class DroshTheme(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val palette: ThemePalette = ThemePalette.DEFAULT,
) {
    companion object {
        /** The built-in palette — what "no theme" means. */
        val DEFAULT = DroshTheme(
            id = "drosh",
            name = "Drosh",
            author = "Muhofy",
            description = "The colours Drosh has always drawn.",
            palette = ThemePalette.DEFAULT,
        )
    }
}

/** A theme listed by the index, before it is downloaded. */
data class ThemeIndexEntry(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    /** Path of the theme file in the store repo, relative to its root. */
    val file: String = "",
)

/** The whole index, as fetched. */
data class ThemeIndex(
    val themes: List<ThemeIndexEntry> = emptyList(),
)
