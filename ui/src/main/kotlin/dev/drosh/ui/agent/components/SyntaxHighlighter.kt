package dev.drosh.ui.agent.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** One lexical class of token, and the palette entry that paints it. */
enum class SyntaxToken {
    PLAIN,
    KEYWORD,
    STRING,
    NUMBER,
    COMMENT,
    FUNCTION,
    TYPE,
    OPERATOR,
    PUNCTUATION,
}

/**
 * A lexical tokeniser for the languages in [SyntaxLanguages].
 *
 * ## Why this is a scanner and not a parser
 *
 * It answers one question — "which colour is this character" — and nothing else. It
 * never validates, never resolves a symbol, and never knows whether the code is
 * correct. That is enough for highlighting and it is why the implementation is a
 * single pass over the text.
 *
 * A real parser would be both larger and worse here: it needs a full grammar per
 * language to get the same answer, and on a half-typed snippet — which is what
 * arrives while the model is still streaming — a grammar error means no highlighting
 * at all, where the scanner degrades to "colours what it recognised".
 *
 * ## The rule that matters most
 *
 * Strings and comments are consumed to their terminator before anything else is
 * looked at. Without that, the word `return` inside `"return value"` highlights as a
 * keyword, and a `//` inside a string opens a comment that runs to the end of the
 * line. Both are the kind of wrong that makes highlighting look broken rather than
 * approximate.
 */
class SyntaxHighlighter(
    private val language: String?,
    private val palette: CodePalette,
) {

    private val keywords: Set<String> = language?.let(SyntaxLanguages::keywords).orEmpty()
    private val lineComments: Set<String> = language?.let(SyntaxLanguages::lineComments).orEmpty()
    private val blockComments: Boolean = language?.let(SyntaxLanguages::hasBlockComments) ?: false

    fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        var atLineStart = true

        while (i < source.length) {
            val c = source[i]

            // Whitespace: consumed silently so it inherits whatever preceded it.
            if (c.isWhitespace()) {
                append(c)
                if (c == '\n') atLineStart = true
                i++
                continue
            }

            // A line comment runs to the newline.
            val lineComment = lineComments.firstOrNull { source.startsWith(it, i) }
            if (lineComment != null) {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                styled(SyntaxToken.COMMENT, source.substring(i, end))
                i = end
                continue
            }

            // A block comment runs to the terminator, across lines.
            if (blockComments && source.startsWith("/*", i)) {
                val close = source.indexOf("*/", i + 2)
                val end = if (close < 0) source.length else close + 2
                styled(SyntaxToken.COMMENT, source.substring(i, end))
                atLineStart = false
                i = end
                continue
            }

            // A string runs to its quote, honouring backslash escapes. Unterminated
            // runs to the end of the line, which is what a half-streamed string is.
            if (c == '"' || c == '\'' || c == '`') {
                val end = scanString(source, i, c)
                styled(SyntaxToken.STRING, source.substring(i, end))
                atLineStart = false
                i = end
                continue
            }

            // A number. The letter check stops `0x` being read as `0` then `x`.
            if (c.isDigit()) {
                var end = i + 1
                while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '.' || source[end] == '_')) {
                    end++
                }
                styled(SyntaxToken.NUMBER, source.substring(i, end))
                atLineStart = false
                i = end
                continue
            }

            // An identifier: a keyword, or the start of a function or type name.
            if (c.isLetter() || c == '_' || c == '$') {
                var end = i + 1
                while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '$')) {
                    end++
                }
                val word = source.substring(i, end)
                styled(tokenFor(word, source, end), word)
                atLineStart = false
                i = end
                continue
            }

            // Punctuation before operators, because `(` is both and the bracket is
            // what a reader expects to be punctuation.
            val token = if (c in "()[]{},;:" || c == '\\') {
                SyntaxToken.PUNCTUATION
            } else {
                SyntaxToken.OPERATOR
            }
            styled(token, c.toString())
            atLineStart = false
            i++
        }
    }

    /**
     * Classify a word by what follows it.
     *
     * `foo(` is a call, `class Foo` is a declaration, and a bare `foo` is a name
     * with no further claim. The lookahead is what separates them, and it is a single
     * character of whitespace rather than a grammar — which is exactly as imprecise
     * as it sounds, and is why the function and type colours are tuned to be
     * readable rather than informative.
     */
    private fun tokenFor(word: String, source: String, end: Int): SyntaxToken {
        if (word in keywords) return SyntaxToken.KEYWORD

        val next = source.getOrNull(end)?.let { if (it.isWhitespace()) null else it }
        val afterSpace = next ?: source.getOrNull(end + (source.substring(end).takeWhile { it.isWhitespace() }.length))
            ?.takeIf { !it.isWhitespace() }

        return when {
            next == '(' -> SyntaxToken.FUNCTION
            // Capitalised identifiers are types by convention in every language here.
            word.firstOrNull()?.isUpperCase() == true -> SyntaxToken.TYPE
            afterSpace == '(' -> SyntaxToken.FUNCTION
            else -> SyntaxToken.PLAIN
        }
    }

    /** Index just past the closing quote of a string starting at [start]. */
    private fun scanString(source: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            // Backticks in Kotlin are raw: an escape inside one is not an escape.
            if (c == '\\' && quote != '`') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            // A single-quoted string does not cross a line.
            if (c == '\n' && quote == '\'') return i
            i++
        }
        return source.length
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.styled(
        token: SyntaxToken,
        text: String,
    ) {
        val color = colorFor(token)
        if (color == null) {
            append(text)
        } else {
            pushStyle(SpanStyle(color = color))
            append(text)
            pop()
        }
    }

    private fun colorFor(token: SyntaxToken): Color? = when (token) {
        SyntaxToken.PLAIN -> null // inherited from the block's own colour
        SyntaxToken.KEYWORD -> palette.keyword
        SyntaxToken.STRING -> palette.string
        SyntaxToken.NUMBER -> palette.number
        SyntaxToken.COMMENT -> palette.comment
        SyntaxToken.FUNCTION -> palette.function
        SyntaxToken.TYPE -> palette.type
        SyntaxToken.OPERATOR -> palette.operator
        SyntaxToken.PUNCTUATION -> palette.punctuation
    }
}