package dev.drosh.domain

/**
 * Shared URL detection utility used by both the block engine rendering path
 * (Compose UI in the `ui` module) and the classic Termux terminal view
 * (TUI overlay + tap handler in the `terminal` module).
 *
 * Regex modelled on termux-app's `TermuxUrlUtils.URL_MATCH_REGEX`.
 */
object UrlDetector {

    private val urlPattern = Regex(
        """(?i)(dav|dict|dns|file|finger|ftps?|git|gemini|gopher|https?|imaps?|irc[6s]?|ip[fn]s|ldaps?|pop3s?|rediss?|rsync|rtsp[su]?|sftp|smtps?|svn(?:\+ssh)?|telnet|tftp|udp|vnc|wss?)://(?:\S+(?::\S*)?@)?(?:(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)|(?:(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+)(?:\.(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+)*(?:\.(?:[a-z\u00a1-\uffff0-9]-*){1,}[a-z\u00a1-\uffff0-9]{1,})?|/(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+)(?::\d{1,5})?(?:/[a-zA-Z0-9:@%._~!$&()*+,;=?/-]*)(?:#[a-zA-Z0-9:@%._~!$&()*+,;=?/-]*)?)""",
    )

    private val bareWwwPattern = Regex(
        """(?i)(www\.)[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]""",
    )

    data class UrlMatch(
        val url: String,
        val start: Int,
        val end: Int,
    )

    fun findUrls(text: String): List<UrlMatch> {
        val result = mutableListOf<UrlMatch>()

        urlPattern.findAll(text).forEach {
            result.add(
                UrlMatch(
                    normalizeUrl(it.value),
                    it.range.first,
                    it.range.last + 1,
                ),
            )
        }

        bareWwwPattern.findAll(text).forEach {
            result.add(
                UrlMatch(
                    normalizeUrl(it.value),
                    it.range.first,
                    it.range.last + 1,
                ),
            )
        }

        return result.sortedBy { it.start }
    }

    fun matches(word: String): Boolean =
        urlPattern.containsMatchIn(word) ||
            bareWwwPattern.containsMatchIn(word)

    fun normalizeUrlFromWord(word: String): String =
        normalizeUrl(word)

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim { it <= ' ' }

        if (
            trimmed.startsWith("http://") ||
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