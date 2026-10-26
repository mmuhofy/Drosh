package dev.drosh.terminal.engine.core

/**
 * The prototype's fixed palette (D12: no config system), plus the colours the
 * application overrode at runtime.
 *
 * The 16 ANSI colors, the primary foreground/background and [CURSOR] are
 * alacritty's defaults — `alacritty/src/config/color.rs`
 * (`NormalColors`/`BrightColors`/`Primary`, cursor defaults to the foreground),
 * and `DIM_FACTOR = 0.66` comes from `alacritty/src/display/color.rs`. Keeping
 * them identical means a screenshot from the lab and one from alacritty can be
 * compared directly.
 *
 * Indexed colors 16..255 use alacritty's own algorithm
 * (`alacritty/src/display/color.rs`, `fill_cube` / `fill_gray_ramp`).
 *
 * **Overrides**: `OSC 4/10/11/12` can replace any entry. The engine stores
 * those in its own table and `nativeColors` exports it; when an entry is set it
 * wins over the default above, which is what keeps the screen and the query
 * replies (`OSC 4/10/11`) saying the same thing. The table is swapped as a
 * whole, from the render thread, so a reader never sees a half-written one.
 */
internal object Palette {

    const val DEFAULT_FOREGROUND = 0xd8d8d8
    const val DEFAULT_BACKGROUND = 0x181818
    const val CURSOR = DEFAULT_FOREGROUND

    /**
     * The application's own table, when it has one.
     *
     * alacritty's split, the same way: the *application* holds the
     * configuration and the *display* holds the resolved table
     * (`alacritty/src/display/color.rs` — `impl From<&Colors> for List`). The
     * terminal crate only knows the defaults and the overrides the terminal
     * itself asked for; the app's theme is one layer above that.
     *
     * The table is the engine's wire format (`bridge/cell.rs` encodes a colour
     * as the index into this same table), so it is alacritty's layout, index
     * for index.
     */
    @Volatile
    private var application: IntArray? = null

    /**
     * Whether bold text is drawn in the bright colours.
     *
     * alacritty's own setting (`config/color.rs` —
     * `draw_bold_text_with_bright_colors`): the bold flag reaches the renderer
     * as a flag, and the *renderer* decides what bold means. A program that
     * asked for a colour gets it; the application's setting only decides how
     * the eight bold colours are drawn.
     */
    @Volatile
    var boldBrightens: Boolean = false

    /**
     * The application's table, the 269 colours the engine names.
     *
     * The array is owned by the caller and only replaced when the app's theme
     * changes.
     */
    fun setApplicationTable(table: IntArray) {
        application = table
    }

    /** `vte::ansi::NamedColor` slots for the primary colours. */
    private const val NAMED_FOREGROUND = 256

    /** The dim named colours, 259..266. */
    private const val NAMED_DIM_BLACK = 259
    private const val NAMED_DIM_RED = 260
    private const val NAMED_DIM_GREEN = 261
    private const val NAMED_DIM_YELLOW = 262
    private const val NAMED_DIM_BLUE = 263
    private const val NAMED_DIM_MAGENTA = 264
    private const val NAMED_DIM_CYAN = 265
    private const val NAMED_DIM_WHITE = 266

    /** The bright named colours, 8..15. */
    private const val BRIGHT_BLACK = 8
    private const val BRIGHT_RED = 9
    private const val BRIGHT_GREEN = 10
    private const val BRIGHT_YELLOW = 11
    private const val BRIGHT_BLUE = 12
    private const val BRIGHT_MAGENTA = 13
    private const val BRIGHT_CYAN = 14
    private const val BRIGHT_WHITE = 15
    private const val NAMED_BACKGROUND = 257
    private const val NAMED_CURSOR = 258

    private const val DIM_FACTOR = 0.66f

    private val ANSI = intArrayOf(
        0x181818, 0xac4242, 0x90a959, 0xf4bf75, 0x6a9fb5, 0xaa759f, 0x75b5aa, 0xd8d8d8,
        0x6b6b6b, 0xc55555, 0xaac474, 0xfeca88, 0x82b8c8, 0xc28cb8, 0x93d3c3, 0xf8f8f8,
    )

    /** `OSC 4/10/11/12` overrides: `-1` means "use the default above". */
    @Volatile
    private var overrides: IntArray? = null

    /** Install the engine's table. The array is owned by the caller and only
     *  replaced when the engine reports a change (see `STATE_COLORS`). */
    fun setOverrides(table: IntArray) {
        overrides = table
    }

    /** The current primary foreground, after any `OSC 10` override. */
    val foreground: Int get() = override(NAMED_FOREGROUND) ?: application(NAMED_FOREGROUND) ?: DEFAULT_FOREGROUND

    /** The current primary background, after any `OSC 11` override. */
    val background: Int get() = override(NAMED_BACKGROUND) ?: application(NAMED_BACKGROUND) ?: DEFAULT_BACKGROUND

    /** The cursor colour; alacritty's default is the foreground. */
    val cursor: Int get() = override(NAMED_CURSOR) ?: application(NAMED_CURSOR) ?: foreground

    /**
     * Resolve a wire colour (see `bridge/cell.rs`) to `0xRRGGBB`.
     *
     * The order is alacritty's: a palette colour is the palette's, and the
     * application's table is what the palette *is*. The engine's own overrides
     * (`OSC 4/10/11/12`) come first only for the colours those sequences are
     * about — that is, for names, not for palette indices. Asking the override
     * table first for a `Named(Background)` cell meant a program that had set
     * `OSC 11` was drawn in its own choice for every default cell, which is
     * how a whole screen can come out in the wrong colours.
     */
    fun resolve(encoded: Int, defaultColor: Int): Int {
        val payload = encoded and NativeCore.COLOR_VALUE_MASK
        return when (encoded ushr NativeCore.COLOR_TAG_SHIFT) {
            NativeCore.COLOR_TAG_NAMED ->
                if (payload < 16) {
                    // The sixteen ANSI names: the palette's, never the theme's.
                    application(payload) ?: ANSI[payload]
                } else {
                    override(payload) ?: named(payload, defaultColor)
                }
            NativeCore.COLOR_TAG_INDEXED -> indexed(payload)
            else -> payload
        }
    }

    /**
     * The bright version of a colour, for bold text.
     *
     * The named colours are the *dim* ones, so the bright one is the same hue
     * with the dim factor undone. A colour that is not one of the named eight
     * is left alone: there is no bright version of it to have.
     */
    fun brightened(color: Int): Int {
        val index = DIM_NAMES.indexOfFirst { dim(it) == color }
        if (index < 0) return color
        return application(BRIGHT_NAMES[index]) ?: BRIGHT_DEFAULTS[index]
    }

    /**
     * The colour an `OSC 4/10/11/12` query asks about, or `null` when alacritty
     * would not answer it (a cursor query whose colour was never set).
     */
    fun colorFor(index: Int): Int? {
        // The cursor is answered only when the application set it, which is
        // what xterm does; the engine answers that case itself.
        if (index == NativeCore.COLOR_INDEX_CURSOR && override(index) == null) {
            return null
        }
        // The three primary slots are the *application's*, not the engine's:
        // `OSC 11` asks "what is your background", and the answer is the
        // terminal's own colour. Reading them out of the engine's override
        // table first meant a program that had set one (nano sets both) was
        // answered with its own choice instead of the terminal's.
        return when (index) {
            NAMED_FOREGROUND -> application(NAMED_FOREGROUND) ?: DEFAULT_FOREGROUND
            NAMED_BACKGROUND -> application(NAMED_BACKGROUND) ?: DEFAULT_BACKGROUND
            NAMED_CURSOR -> override(index) ?: cursor
            else -> override(index) ?: if (index < 256) indexed(index) else named(index, DEFAULT_FOREGROUND)
        }
    }

    /** The application's colour at an index, when it has a table. */
    private fun application(index: Int): Int? {
        val table = application ?: return null
        if (index < 0 || index >= table.size) return null
        return table[index]
    }

    private fun override(index: Int): Int? {
        val table = overrides ?: return null
        if (index < 0 || index >= table.size) {
            return null
        }
        val value = table[index]
        return if (value >= 0) value and 0xffffff else null
    }

    /** `vte::ansi::NamedColor` values: 0..15 ANSI, 16..255 unused, then 256+. */
    private fun named(index: Int, defaultColor: Int): Int = when {
        index < 16 -> application(index) ?: ANSI[index]
        index == NAMED_FOREGROUND -> application(index) ?: DEFAULT_FOREGROUND
        index == NAMED_BACKGROUND -> application(index) ?: DEFAULT_BACKGROUND
        index == NAMED_CURSOR -> application(index) ?: CURSOR
        index in 259..266 -> dim(application(index) ?: ANSI[index - 259])
        index == 267 -> application(index) ?: ANSI[15]
        index == 268 -> dim(application(index) ?: DEFAULT_FOREGROUND)
        // vte 0.15 ends the enum at `DimForeground` (268); anything else means
        // the engine moved and the mapping has to be re-read from its source.
        else -> defaultColor
    }

    /**
     * A palette index, as the xterm 256-colour table defines it.
     *
     * The first sixteen are the ANSI colours, and they are **not** the
     * application's theme here on purpose: a program that asked for
     * `ESC[48;5;4]` asked for colour 4 of the terminal's palette, and drawing
     * the user's "blue" instead is drawing a different colour than the one it
     * picked. nano's menu bar, vim's status line and every TUI that paints a
     * block do exactly that, and with a dark theme whose blue is near-black
     * those blocks vanished into the background.
     *
     * The theme still owns the *named* colours — the default foreground, the
     * default background, the cursor — which is where a user's palette is
     * meant to apply.
     */
    private fun indexed(index: Int): Int = when {
        index < 16 -> ANSI[index]
        index < 232 -> {
            val cube = index - 16
            rgb(level(cube / 36), level((cube % 36) / 6), level(cube % 6))
        }
        index < 256 -> {
            val value = (index - 232) * 10 + 8
            rgb(value, value, value)
        }
        else -> DEFAULT_FOREGROUND
    }

    private fun level(value: Int): Int = if (value == 0) 0 else value * 40 + 55

    /** The named colours, in the order the bright ones follow them. */
    private val DIM_NAMES = listOf(
        NAMED_DIM_BLACK, NAMED_DIM_RED, NAMED_DIM_GREEN, NAMED_DIM_YELLOW,
        NAMED_DIM_BLUE, NAMED_DIM_MAGENTA, NAMED_DIM_CYAN, NAMED_DIM_WHITE,
    )

    /** The bright counterpart of each dim colour. */
    private val BRIGHT_NAMES = listOf(
        BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
        BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE,
    )

    /** alacritty's own bright colours, for when the application has no table. */
    private val BRIGHT_DEFAULTS = intArrayOf(
        0x6b6b6b, 0xc55555, 0xaac474, 0xfeca88, 0x82b8c8, 0xc28cb8, 0x93d3c3, 0xf8f8f8,
    )

    private fun dim(color: Int): Int = rgb(
        ((color shr 16 and 0xff) * DIM_FACTOR).toInt(),
        ((color shr 8 and 0xff) * DIM_FACTOR).toInt(),
        ((color and 0xff) * DIM_FACTOR).toInt(),
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
}
