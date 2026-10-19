package dev.drosh.engine.render

import dev.drosh.engine.core.NativeCore

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

    /** `vte::ansi::NamedColor` slots for the primary colours. */
    private const val NAMED_FOREGROUND = 256
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
    val foreground: Int get() = override(NAMED_FOREGROUND) ?: DEFAULT_FOREGROUND

    /** The current primary background, after any `OSC 11` override. */
    val background: Int get() = override(NAMED_BACKGROUND) ?: DEFAULT_BACKGROUND

    /** The cursor colour; alacritty's default is the foreground. */
    val cursor: Int get() = override(NAMED_CURSOR) ?: foreground

    /** Resolve a wire color (see `bridge/cell.rs`) to `0xRRGGBB`. */
    fun resolve(encoded: Int, defaultColor: Int): Int {
        val payload = encoded and NativeCore.COLOR_VALUE_MASK
        return when (encoded ushr NativeCore.COLOR_TAG_SHIFT) {
            NativeCore.COLOR_TAG_NAMED -> override(payload) ?: named(payload, defaultColor)
            NativeCore.COLOR_TAG_INDEXED -> override(payload) ?: indexed(payload)
            else -> payload
        }
    }

    /**
     * The colour an `OSC 4/10/11/12` query asks about, or `null` when alacritty
     * would not answer it (a cursor query whose colour was never set).
     */
    fun colorFor(index: Int): Int? {
        val set = override(index)
        if (index == NativeCore.COLOR_INDEX_CURSOR && set == null) {
            return null
        }
        return set ?: if (index < 256) indexed(index) else named(index, DEFAULT_FOREGROUND)
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
        index < 16 -> ANSI[index]
        index == NAMED_FOREGROUND -> DEFAULT_FOREGROUND
        index == NAMED_BACKGROUND -> DEFAULT_BACKGROUND
        index == NAMED_CURSOR -> CURSOR
        index in 259..266 -> dim(ANSI[index - 259])
        index == 267 -> ANSI[15]
        index == 268 -> dim(DEFAULT_FOREGROUND)
        // vte 0.15 ends the enum at `DimForeground` (268); anything else means
        // the engine moved and the mapping has to be re-read from its source.
        else -> defaultColor
    }

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

    private fun dim(color: Int): Int = rgb(
        ((color shr 16 and 0xff) * DIM_FACTOR).toInt(),
        ((color shr 8 and 0xff) * DIM_FACTOR).toInt(),
        ((color and 0xff) * DIM_FACTOR).toInt(),
    )

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b
}
