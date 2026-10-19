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
    /**
     * Drosh's own background, not black: the terminal view used to show the
     * app's surface through it (#0E0E0E), and "the terminal is black" was a
     * habit from other terminals rather than how Drosh looked. A theme
     * brings its own background; without one, this is it.
     */
    val background: String = "#0E0E0E",
    val foreground: String = "#E8E8E8",
    /** The scheme's own default for a dark background: white. */
    val cursor: String = "#FFFFFF",
    val selectionBackground: String = "#16315D",
    val selectionForeground: String = "#101014",
    /**
     * The app tint this palette would give the interface.
     *
     * Carried, written to the file, and read back — but *not* wired into
     * `:design-system`, which still picks its own accent per light/dark. That is
     * deliberate and is why this field is separate from the twenty-two terminal
     * colours: a palette is a statement about output, and repainting every
     * settings row and dialog the moment someone changes their terminal colours
     * is a surprise the settings screen is not in a position to explain. The one
     * place the accent is shown live is the onboarding theme page, which
     * overrides the token for its own subtree and nothing else — see
     * `AccentPreview` in `ui/setup/onboarding/components`.
     */
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

        /**
         * The palettes that ship inside the APK, in the order a picker shows
         * them: Drosh's own first, then the starters.
         *
         * **Why these are here and not fetched.** Onboarding runs *before* the
         * rootfs exists. The theme store's themes are files in
         * `<rootfs>/home/.drosh/themes/`, and the store's index is a network
         * request — so during setup there is, by construction, nowhere a theme
         * can be read from and nothing worth showing but what is compiled in.
         * A picker that renders an empty grid, or that blocks setup on a
         * download, is worse than a shelf of well-known palettes. The store is
         * still the answer for the long tail; it just is not the answer for the
         * first thirty seconds of the app.
         *
         * The values are the published palettes of their respective authors.
         * `author` is "Drosh" rather than theirs because these ship with the
         * app: the store shows that line, and crediting a bundled copy to a
         * third party would be claiming a release that does not exist.
         */
        val STARTERS: List<DroshTheme> = listOf(
            starter(
                id = "dracula",
                name = "Dracula",
                palette = ThemePalette(
                    background = "#282A36",
                    foreground = "#F8F8F2",
                    cursor = "#F8F8F2",
                    selectionBackground = "#44475A",
                    selectionForeground = "#F8F8F2",
                    accent = "#BD93F9",
                    black = "#21222C",
                    red = "#FF5555",
                    green = "#50FA7B",
                    yellow = "#F1FA8C",
                    blue = "#BD93F9",
                    magenta = "#FF79C6",
                    cyan = "#8BE9FD",
                    white = "#F8F8F2",
                    brightBlack = "#6272A4",
                    brightRed = "#FF6E6E",
                    brightGreen = "#69FF94",
                    brightYellow = "#FFFFA5",
                    brightBlue = "#D6ACFF",
                    brightMagenta = "#FF92DF",
                    brightCyan = "#A4FFFF",
                    brightWhite = "#FFFFFF",
                ),
            ),
            starter(
                id = "nord",
                name = "Nord",
                palette = ThemePalette(
                    background = "#2E3440",
                    foreground = "#D8DEE9",
                    cursor = "#D8DEE9",
                    selectionBackground = "#434C5E",
                    selectionForeground = "#E5E9F0",
                    accent = "#88C0D0",
                    black = "#3B4252",
                    red = "#BF616A",
                    green = "#A3BE8C",
                    yellow = "#EBCB8B",
                    blue = "#81A1C1",
                    magenta = "#B48EAD",
                    cyan = "#88C0D0",
                    white = "#E5E9F0",
                    brightBlack = "#4C566A",
                    brightRed = "#BF616A",
                    brightGreen = "#A3BE8C",
                    brightYellow = "#EBCB8B",
                    brightBlue = "#81A1C1",
                    brightMagenta = "#B48EAD",
                    brightCyan = "#8FBCBB",
                    brightWhite = "#ECEFF4",
                ),
            ),
            starter(
                id = "tokyo-night",
                name = "Tokyo Night",
                palette = ThemePalette(
                    background = "#1A1B26",
                    foreground = "#C0CAF5",
                    cursor = "#C0CAF5",
                    selectionBackground = "#283457",
                    selectionForeground = "#C0CAF5",
                    accent = "#7AA2F7",
                    black = "#15161E",
                    red = "#F7768E",
                    green = "#9ECE6A",
                    yellow = "#E0AF68",
                    blue = "#7AA2F7",
                    magenta = "#BB9AF7",
                    cyan = "#7DCFFF",
                    white = "#A9B1D6",
                    brightBlack = "#414868",
                    brightRed = "#F7768E",
                    brightGreen = "#9ECE6A",
                    brightYellow = "#E0AF68",
                    brightBlue = "#7AA2F7",
                    brightMagenta = "#BB9AF7",
                    brightCyan = "#7DCFFF",
                    brightWhite = "#C0CAF5",
                ),
            ),
            starter(
                id = "catppuccin-mocha",
                name = "Catppuccin Mocha",
                palette = ThemePalette(
                    background = "#1E1E2E",
                    foreground = "#CDD6F4",
                    cursor = "#CDD6F4",
                    selectionBackground = "#585B70",
                    selectionForeground = "#CDD6F4",
                    accent = "#89B4FA",
                    black = "#45475A",
                    red = "#F38BA8",
                    green = "#A6E3A1",
                    yellow = "#F9E2AF",
                    blue = "#89B4FA",
                    magenta = "#F5C2E7",
                    cyan = "#94E2D5",
                    white = "#BAC2DE",
                    brightBlack = "#585B70",
                    brightRed = "#F38BA8",
                    brightGreen = "#A6E3A1",
                    brightYellow = "#F9E2AF",
                    brightBlue = "#89B4FA",
                    brightMagenta = "#F5C2E7",
                    brightCyan = "#94E2D5",
                    brightWhite = "#A6ADC8",
                ),
            ),
            starter(
                id = "rose-pine",
                name = "Rosé Pine",
                palette = ThemePalette(
                    background = "#191724",
                    foreground = "#E0DEF4",
                    cursor = "#E0DEF4",
                    selectionBackground = "#403D52",
                    selectionForeground = "#E0DEF4",
                    accent = "#C4A7E7",
                    black = "#26233A",
                    red = "#EB6F92",
                    green = "#31748F",
                    yellow = "#F6C177",
                    blue = "#9CCFD8",
                    magenta = "#C4A7E7",
                    cyan = "#EBBCBA",
                    white = "#E0DEF4",
                    brightBlack = "#6E6A86",
                    brightRed = "#EB6F92",
                    brightGreen = "#31748F",
                    brightYellow = "#F6C177",
                    brightBlue = "#9CCFD8",
                    brightMagenta = "#C4A7E7",
                    brightCyan = "#EBBCBA",
                    brightWhite = "#FFFFFF",
                ),
            ),
            starter(
                id = "solarized-dark",
                name = "Solarized Dark",
                palette = ThemePalette(
                    background = "#002B36",
                    foreground = "#839496",
                    cursor = "#93A1A1",
                    selectionBackground = "#0F4B57",
                    selectionForeground = "#EEE8D5",
                    accent = "#268BD2",
                    black = "#073642",
                    red = "#DC322F",
                    green = "#859900",
                    yellow = "#B58900",
                    blue = "#268BD2",
                    magenta = "#D33682",
                    cyan = "#2AA198",
                    white = "#EEE8D5",
                    brightBlack = "#002B36",
                    brightRed = "#CB4B16",
                    brightGreen = "#586E75",
                    brightYellow = "#657B83",
                    brightBlue = "#839496",
                    brightMagenta = "#6C71C4",
                    brightCyan = "#93A1A1",
                    brightWhite = "#FDF6E3",
                ),
            ),
            starter(
                id = "gruvbox-dark",
                name = "Gruvbox Dark",
                palette = ThemePalette(
                    background = "#282828",
                    foreground = "#EBDBB2",
                    cursor = "#EBDBB2",
                    selectionBackground = "#504945",
                    selectionForeground = "#EBDBB2",
                    accent = "#FABD2F",
                    black = "#282828",
                    red = "#CC241D",
                    green = "#98971A",
                    yellow = "#D79921",
                    blue = "#458588",
                    magenta = "#B16286",
                    cyan = "#689D6A",
                    white = "#A89984",
                    brightBlack = "#928374",
                    brightRed = "#FB4934",
                    brightGreen = "#B8BB26",
                    brightYellow = "#FABD2F",
                    brightBlue = "#83A598",
                    brightMagenta = "#D3869B",
                    brightCyan = "#8EC07C",
                    brightWhite = "#EBDBB2",
                ),
            ),
        )

        /** A bundled palette: shipped by the app, so the app is what it names. */
        private fun starter(id: String, name: String, palette: ThemePalette) = DroshTheme(
            id = id,
            name = name,
            author = AUTHOR,
            palette = palette,
        )

        /**
         * Every theme a picker may show: the built-ins, then whatever is
         * installed that is not one of them.
         *
         * Installed entries win per id — a theme file on disk is a thing the
         * user can have edited, and a picker that showed the compiled-in copy
         * of an id the user had replaced would be showing them something they
         * do not have. The built-ins stay in front either way: Drosh's own is
         * the way back to the colours the app started with, and the order is
         * the order of the grid.
         *
         * Pure, and in `:domain` rather than in the store, because the merge is
         * a rule about what a picker shows and has nothing to do with where the
         * files are.
         */
        fun choices(installed: List<DroshTheme>): List<DroshTheme> {
            val builtIn = listOf(DEFAULT) + STARTERS
            val byInstalled = installed.associateBy { it.id }
            val builtInIds = builtIn.mapTo(HashSet()) { it.id }
            return builtIn.map { byInstalled[it.id] ?: it } +
                installed.filter { it.id !in builtInIds }
        }

        /** The one theme named by [id] among [candidates], or null. */
        fun find(id: String, candidates: List<DroshTheme>): DroshTheme? =
            candidates.firstOrNull { it.id == id }

        /** Who the bundled palettes are credited to in a picker. */
        private const val AUTHOR = "Drosh"
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
    /**
     * Nine colours from the theme's palette, for the store's cover.
     *
     * The index carries them so a shelf of two hundred themes can be drawn
     * without downloading two hundred files — a cover that needs a request
     * per card is not a cover, it is a loading state.
     */
    val preview: List<String> = emptyList(),
)

/** The whole index, as fetched. */
data class ThemeIndex(
    val themes: List<ThemeIndexEntry> = emptyList(),
)
