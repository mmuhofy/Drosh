package dev.drosh.ui.agent.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import dev.drosh.design.system.DroshThemeColors
import dev.drosh.design.system.LocalDroshColors
import dev.drosh.design.system.LocalFontSet

/**
 * Installs our tokens where the renderer will read them.
 *
 * The library exposes each of the four as its own `LocalMarkdown*`, and reads them
 * from inside the components rather than from arguments — so providing those is the
 * whole job, and `Markdown` needs no explicit styling passed at all.
 *
 * `LocalDroshColors` is read once and the fields copied out, because every `Drosh*`
 * token is a composable getter over that local and a plain class cannot make one.
 */
@Composable
fun provideMarkdownTheme(content: @Composable () -> Unit) {
    val app = LocalDroshColors.current
    val code = CodeColors
    val fonts = LocalFontSet.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownColors provides remember(app, code) { DroshMarkdownColors(app, code) },
        LocalMarkdownTypography provides remember(app, fonts) {
            DroshMarkdownTypography(app.textSecondary, fonts.sans, fonts.mono)
        },
        LocalMarkdownDimens provides remember { DroshMarkdownDimens() },
        LocalMarkdownPadding provides remember { DroshMarkdownPadding() },
        content = content,
    )
}

/**
 * Holds the whole `DroshThemeColors` rather than the five fields it needs.
 *
 * Naming the constructor parameters `text`, `primary` and so on would clash with the
 * interface properties they are meant to implement, and renaming them to dodge that
 * would leave a constructor that reads as a list of near-identical colours. The
 * palette object is already a value, and it is stable across a theme change, so it is
 * what gets remembered.
 */
private class DroshMarkdownColors(
    private val app: DroshThemeColors,
    private val code: CodePalette,
) : MarkdownColors {
    override val text: Color get() = app.text
    override val codeText: Color get() = code.plain
    override val inlineCodeText: Color get() = code.string
    override val linkText: Color get() = app.primary
    override val codeBackground: Color get() = code.background
    override val inlineCodeBackground: Color get() = app.surfaceVariant
    override val dividerColor: Color get() = app.outline
}

// The font families are constructor arguments rather than CompositionLocal
// reads: the getters below are plain property getters, and a class cannot
    // make one. `provideMarkdownTheme` reads them once and passes them in.
private class DroshMarkdownTypography(
    private val secondary: Color,
    private val sans: FontFamily,
    private val mono: FontFamily,
) : MarkdownTypography {
    // Headings step down from h1 in a ratio that stays legible at reading size on a
    // phone. A library default of 2x for h1 would be a headline in the transcript.
    override val text: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 21.sp)
    override val paragraph: TextStyle get() = text
    override val h1: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    override val h2: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    override val h3: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    override val h4: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    override val h5: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    override val h6: TextStyle get() = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = secondary)
    override val quote: TextStyle get() = text.copy(color = secondary)
    override val code: TextStyle get() = TextStyle(fontFamily = mono, fontSize = 12.sp, lineHeight = 17.sp)
    override val inlineCode: TextStyle get() = code.copy(fontSize = 13.sp)
    override val ordered: TextStyle get() = text
    override val bullet: TextStyle get() = text
    override val list: TextStyle get() = text
    override val link: TextStyle get() = text
}

private class DroshMarkdownDimens : MarkdownDimens {
    override val dividerThickness: Dp get() = 1.dp
    override val codeBackgroundCornerSize: Dp get() = CODE_BLOCK_CORNER
    override val blockQuoteThickness: Dp get() = 2.dp
}

private class DroshMarkdownPadding : MarkdownPadding {
    // Mixed on purpose, matching the interface: the first four are a single edge, while
    // the block quote needs PaddingValues because the bar is padded away from the text,
    // which one number cannot express. blockQuoteBar is Absolute so it is not resolved
    // against a layout direction — the bar sits on the same side in both.
override val block: Dp get() = 6.dp
    override val list: Dp get() = 4.dp
    override val listItemBottom: Dp get() = 2.dp
    override val indentList: Dp get() = 16.dp
    override val codeBlock: PaddingValues get() = PaddingValues(all = 8.dp)
    override val blockQuote: PaddingValues get() = PaddingValues(vertical = 8.dp)
    override val blockQuoteText: PaddingValues get() = PaddingValues(horizontal = 2.dp)
    override val blockQuoteBar: PaddingValues.Absolute get() = PaddingValues.Absolute(right = 10.dp)
}

/** Matches the corner radius the code block draws itself with. */
internal val CODE_BLOCK_CORNER = 10.dp

/**
 * The library's components, with the code block replaced by ours.
 *
 * Two reasons. The library's own block has no header and no copy action, and both
 * are needed here — an answer that writes a function is usually a function the user
 * wants to paste. And it would colour code with [MarkdownColors], which is the app
 * palette: spending `primary` on a keyword would make code look interactive.
 *
 * Everything else keeps the library's default, which is why this passes one argument
 * rather than filling in all twenty-three.
 *
 * The highlighter stays our own for now. `dev.snipme:highlights` — the Highlight.js
 * port behind the library's `-code` module — publishes no Android variant, so it
 * cannot be resolved for an Android target without vendoring it. See `SyntaxHighlighter`.
 */
@Composable
fun droshMarkdownComponents(onCopy: (String) -> Unit): MarkdownComponents =
    markdownComponents(
        codeBlock = { model ->
            DroshCodeBlock(
                model = model,
                onCopy = onCopy,
            )
        },
    )