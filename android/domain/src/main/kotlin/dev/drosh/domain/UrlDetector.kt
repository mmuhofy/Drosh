package dev.drosh.domain

/**
 * Shared URL detection used by both rendering paths:
 *  - block mode, in `ui/block/PromptBlock.kt`
 *  - classic TUI, in `terminal/TerminalUrlOverlay.kt` plus the tap handler
 *    in `terminal/TerminalViewClientImpl.kt`
 *
 * Strategy is deliberately lexical rather than grammatical. A URL is
 * `scheme://` followed by every non-whitespace character, so this keeps
 * working for shapes a strict grammar would reject or truncate: IPv6
 * literals in brackets, ports, IDN hosts, long query strings, and
 * percent-encoded paths.
 *
 * A grammatical pattern was tried first, ported from termux-app's
 * `TermuxUrlUtils.URL_MATCH_REGEX`, and rejected. Its path segment was a
 * required group rather than an optional one, so `https://example.com`
 * did not match at all and only URLs that happened to carry a trailing
 * path were detected. `www.` kept working because a separate pattern
 * matched it, which is why the gap read as "only bare hosts are found".
 * Anything stricter trades that class of silent miss for a different one.
 *
 * The cost of being lexical is that punctuation sitting directly after a
 * URL is swallowed by the greedy run, so [trimTrailing] pulls it back off
 * and [findUrls] shortens the match to match.
 */
object UrlDetector {

    /**
     * `scheme://` then everything up to whitespace. The scheme grammar
     * follows RFC 3986: a letter followed by letters, digits, `+`, `-`, `.`.
     */
    private val schemeUrlPattern = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")

    /** Hostnames conventionally written without a scheme, e.g. `www.foo.com`. */
    private val bareWwwPattern =
        Regex("""www\.[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]""", RegexOption.IGNORE_CASE)

    /** Characters that follow prose far more often than they end a URL. */
    private const val TRAILING_PUNCTUATION = ".,;:!?'\""

    /** Bracket pairs, used to keep a closer only when the URL opened one. */
    private val BRACKET_PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{', '>' to '<')

    private val SCHEME_CHARS: Set<Char> = buildSet {
        addAll('a'..'z')
        addAll('A'..'Z')
        addAll('0'..'9')
        addAll(listOf('+', '-', '.'))
    }

    private const val SCHEME_SEPARATOR = "://"

    data class UrlMatch(
        val url: String,
        val start: Int,
        val end: Int,
    )

    fun findUrls(text: String): List<UrlMatch> {
        if (text.isEmpty()) return emptyList()

        val result = ArrayList<UrlMatch>()
        scan(schemeUrlPattern, text, result)
        scan(bareWwwPattern, text, result)

        result.sortBy { it.start }

        // The scheme and bare-www patterns can both claim one run; keep the
        // scheme match, which reaches the URL with its `://` intact.
        val deduped = ArrayList<UrlMatch>(result.size)
        for (candidate in result) {
            val alreadyClaimed = deduped.any { candidate.start in it.start until it.end }
            if (!alreadyClaimed) deduped += candidate
        }
        return deduped
    }

    /**
     * Walks [pattern] over [text], resuming from the *trimmed* end of each
     * match rather than the raw match end. `findAll` advances by the raw
     * match, so a run holding two URLs separated by a comma would swallow
     * the second one.
     */
    private fun scan(pattern: Regex, text: String, into: MutableList<UrlMatch>) {
        var pos = 0
        while (pos < text.length) {
            val match = pattern.find(text, pos) ?: return
            val trimmed = trimTrailing(match.value)
            if (trimmed.isEmpty()) {
                pos = match.range.last + 1
                continue
            }
            into += UrlMatch(trimmed, match.range.first, match.range.first + trimmed.length)
            pos = match.range.first + trimmed.length
        }
    }

    /** True when [word] looks like a URL. Used by the TUI tap handler. */
    fun matches(word: String): Boolean =
        schemeUrlPattern.containsMatchIn(word) || bareWwwPattern.containsMatchIn(word)

    /** The match covering [offset], or null when the offset is not on a link. */
    fun matchAt(text: String, offset: Int): UrlMatch? =
        findUrls(text).firstOrNull { offset >= it.start && offset < it.end }

    fun normalizeUrlFromWord(word: String): String = normalizeUrl(trimTrailing(word))

    /**
     * Drops trailing characters that belong to the surrounding sentence
     * rather than to the URL.
     *
     * Three passes, repeated until stable because removing one character
     * can expose another:
     *  1. a second `scheme://` inside the same whitespace run means the run
     *     held two links, so cut back to where that scheme starts
     *  2. sentence punctuation (`. , ; : ! ? ' "`)
     *  3. a closing bracket, kept only when the URL opened one itself — so
     *     `https://en.wikipedia.org/wiki/A_(b)` survives intact while
     *     `(see https://example.com.)` trims cleanly
     */
    private fun trimTrailing(raw: String): String {
        var token = raw

        val schemeEnd = token.indexOf("://")
        val nextScheme = token.indexOf("://", schemeEnd + SCHEME_SEPARATOR.length)
        if (schemeEnd >= 0 && nextScheme > 0) {
            var cut = nextScheme
            while (cut > 0 && token[cut - 1] in SCHEME_CHARS) cut--
            token = token.substring(0, cut)
        }

        var previous: String
        do {
            previous = token
            token = token.trimEnd { it in TRAILING_PUNCTUATION }
            token = trimUnbalancedClosers(token)
        } while (token != previous)

        return token
    }

    private fun trimUnbalancedClosers(token: String): String {
        var end = token.length
        while (end > 0) {
            val closer = token[end - 1]
            val opener = BRACKET_PAIRS[closer] ?: break
            if (opener in token.substring(0, end)) break
            end--
        }
        return token.substring(0, end)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim { it <= ' ' }
        val lowered = trimmed.lowercase()
        return when {
            lowered.startsWith("http://") || lowered.startsWith("https://") ||
                lowered.startsWith("ftp://") || lowered.startsWith("file://") -> trimmed
            trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
            else -> "https://$trimmed"
        }
    }
}
