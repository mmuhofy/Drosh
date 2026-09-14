package com.iris.irisshell.domain

/**
 * Shared URL detection utility used by both the block engine rendering path
 * (Compose UI in the `ui` module) and the classic Termux terminal view
 * (in the `terminal` module).
 *
 * Detects URLs matching the pattern:
 * `((https?|ftp|file)://|www\.)[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]`
 *
 * Supported URL schemes: http, https, ftp, file, and bare `www.`
 * (bare `www.` URLs are normalized to `https://` on click).
 */
object UrlDetector {

    private val urlPattern = Regex(
        "((https?|ftp|file)://|www\\.)[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]",
        RegexOption.IGNORE_CASE,
    )

    data class UrlMatch(
        val url: String,
        val start: Int,
        val end: Int,
    )

    /**
     * Returns all URL matches in [text] with their positions.
     */
    fun findUrls(text: String): List<UrlMatch> {
        return urlPattern.findAll(text).map { match ->
            UrlMatch(
                url = normalizeUrl(match.value),
                start = match.range.first,
                end = match.range.last + 1,
            )
        }.toList()
    }

    /**
     * Checks if [word] matches the URL pattern (for word-level detection
     * in the classic terminal tap handler).
     */
    fun matches(word: String): Boolean = urlPattern.matches(word)

    /**
     * Normalizes a bare domain/word into a full URL.
     * Adds the appropriate scheme if missing (used by the classic
     * terminal tap handler after [matches] returns true).
     */
    fun normalizeUrlFromWord(word: String): String = normalizeUrl(word)

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim { it <= ' ' }
        if (trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("ftp://") ||
            trimmed.startsWith("file://")
        ) {
            return trimmed
        }
        if (trimmed.startsWith("www.")) {
            return "https://$trimmed"
        }
        return "https://$trimmed"
    }
}
