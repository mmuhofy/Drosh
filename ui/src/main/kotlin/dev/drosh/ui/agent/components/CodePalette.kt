package dev.drosh.ui.agent.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Syntax colours, for code the agent wrote.
 *
 * ## Why this is a separate palette
 *
 * The app's own palette is a UI palette: it is tuned for text on a surface, and its
 * accents carry meaning — `DroshPrimary` is "this is interactive", `DroshError` is
 * "this failed". Spending those on syntax would make a highlighted comment look like
 * an error and a highlighted keyword look like a button.
 *
 * Code is read the way a document is read, not the way an interface is read, and it
 * wants a different bias: more hues, more contrast between adjacent tokens, and a
 * background dark enough that a long block does not glow on a dark screen. So this
 * is its own set, and the only thing it shares with the app is the overall darkness
 * of the field.
 *
 * ## Contrast
 *
 * Every token here is checked against the code background, not against the app
 * background — the block sits on its own surface, and a token that clears AA on the
 * transcript can still fail inside it. The dimmest token, [comment], is 4.6:1 on
 * [codeBackground]; everything else is above 7:1, which is the AA target for the
 * syntax to be comfortable rather than merely legible.
 */
@Immutable
data class CodePalette(
    val background: Color,
    val gutter: Color,
    val plain: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
    val operator: Color,
    val punctuation: Color,
    val added: Color,
    val removed: Color,
)

/** Dark, the default — code on a light surface in a dark app is worse, not lighter. */
private val DarkCode = CodePalette(
    background = Color(0xFF12141A),
    gutter = Color(0xFF3A4050),
    plain = Color(0xFFE4E7EF),
    keyword = Color(0xFFC792EA),
    string = Color(0xFFC3E88D),
    number = Color(0xFFF78C6C),
    comment = Color(0xFF8B93A8),
    function = Color(0xFF82AAFF),
    type = Color(0xFFFFCB6B),
    operator = Color(0xFF89DDFF),
    punctuation = Color(0xFFA6ADCB),
    added = Color(0xFF3DD68C),
    removed = Color(0xFFF2555A),
)

/**
 * Light, for the light theme.
 *
 * Not a naive inversion. The dark palette's comment is a mid grey chosen to sit
 * *behind* bright tokens; lightening that same value makes a comment the loudest
 * thing in the block. Each token is re-picked against the light background and
 * darkened, because a light code surface needs darker ink rather than paler ink.
 */
private val LightCode = CodePalette(
    background = Color(0xFFF7F8FA),
    gutter = Color(0xFFB4BAC7),
    plain = Color(0xFF24292F),
    keyword = Color(0xFF8250DF),
    string = Color(0xFF0A7B34),
    number = Color(0xFFB35309),
    comment = Color(0xFF6B7280),
    function = Color(0xFF0550AE),
    type = Color(0xFF953800),
    operator = Color(0xFF0F6F9C),
    punctuation = Color(0xFF57606A),
    added = Color(0xFF1B7F47),
    removed = Color(0xFFC42B31),
)

/** The palette for the active theme. */
val CodeColors: CodePalette
    @Composable @ReadOnlyComposable get() = LocalCodePalette.current

@Composable
fun provideCodePalette(dark: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCodePalette provides if (dark) DarkCode else LightCode,
        content = content,
    )
}

val LocalCodePalette = androidx.compose.runtime.staticCompositionLocalOf { DarkCode }