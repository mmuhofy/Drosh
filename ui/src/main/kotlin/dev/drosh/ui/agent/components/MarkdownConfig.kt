package dev.drosh.ui.agent.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily

/**
 * App tokens, expressed in the four shapes the markdown renderer asks for.
 *
 * The renderer takes its styling through four interfaces rather than through
 * MaterialTheme, which is the right call for it — it is a library, and it does not
 * know what this app's surface hierarchy is. Implementing them here rather than
 * passing MaterialTheme's values through means a heading cannot drift from
 * `DroshText` if the app theme changes later.
 *
 * Only the code-block colours come from [CodePalette]. Everything else is prose, and
 * prose belongs to the app palette.
 */
@Immutable
data class MarkdownTheme(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val dimens: MarkdownDimens,
    val padding: MarkdownPadding,
)

/** The theme for the active app theme, or the default before one is provided. */
val LocalMarkdownTheme = staticCompositionLocalOf { markdownThemeFor(darkCodePalette = true) }

val MarkdownThemeValue: MarkdownTheme
    @Composable @ReadOnlyComposable get() = LocalMarkdownTheme.current

@Composable
fun provideMarkdownTheme(dark: Boolean, content: @Composable () -> Unit) {
    // The code palette is read here rather than inside DroshMarkdownColors, because
    // reading a CompositionLocal from a getter of a plain class is not composable —
    // the class has no way to be called in a composition context.
    val theme = remember(dark) { markdownThemeFor(codePalette(dark)) }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalMarkdownTheme provides theme,
        content = content,
    )
}

private fun markdownThemeFor(darkCodePalette: Boolean): MarkdownTheme =
    markdownThemeFor(codePalette(darkCodePalette))

private fun markdownThemeFor(code: CodePalette): MarkdownTheme = MarkdownTheme(
    colors = DroshMarkdownColors(code),
    typography = DroshMarkdownTypography(),
    dimens = DroshMarkdownDimens(),
    padding = DroshMarkdownPadding(),
)

private class DroshMarkdownColors(private val code: CodePalette) : MarkdownColors {
    override val text: Color get() = DroshText
    override val codeText: Color get() = code.plain
    override val inlineCodeText: Color get() = code.string
    override val linkText: Color get() = DroshPrimary
    override val codeBackground: Color get() = code.background
    override val inlineCodeBackground: Color get() = DroshSurfaceVariant
    override val dividerColor: Color get() = DroshOutline
}

private class DroshMarkdownTypography : MarkdownTypography {
    // Headings step down from h1 in a ratio that stays legible at reading size on a
    // phone. A library default of 2x for h1 would be a headline in the transcript.
    override val text: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 14.sp, lineHeight = 21.sp)
    override val paragraph: TextStyle get() = text
    override val h1: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    override val h2: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
    override val h3: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    override val h4: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    override val h5: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    override val h6: TextStyle get() = TextStyle(fontFamily = OutfitFontFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = DroshTextSecondary)
    override val quote: TextStyle get() = text.copy(color = DroshTextSecondary)
    override val code: TextStyle get() = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
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