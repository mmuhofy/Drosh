package dev.drosh.domain

/**
 * Shared URL detection utility used by both the block engine rendering path
 * (Compose UI in the `ui` module) and the classic Termux terminal view
 * (TUI overlay + tap handler in the `terminal` module).
 *
 * Regex modelled on termux-app's `TermuxUrlUtils.URL_MATCH_REGEX`
 * (termux/termux-app — `termux-shared/.../data/TermuxUrlUtils.java`),
 * which recognises the full set of URI schemes Termux supports and an
 * IPv4/host/path:port/query/fragment grammar. We add a bare `www.` pattern
 * on top so non-schemed `www.` links are still matched (and normalised to
 * `https://`).
 *
 * Supported URI schemes: dav, dict, dns, file, finger, ftp(s), git, gemini,
 * gopher, http(s), imap(s), irc(6|s), ipfs/ipns, ldap(s), pop3(s), redis(s),
 * rsync, rtsp(s|u), sftp, smb(s), smtp(s), svn(+ssh), telnet, tftp, udp,
 * vnc, ws(s). Plus bare `www.` hostnames.
 */
object UrlDetector {

    private val urlPattern = Regex(
        """(
(
(?:
    dav|dict|dns|file|finger|ftps?|git|gemini|gopher|https?|imaps?|
    irc[6s]?|ip[fn]s|ldaps?|pop3s?|rediss?|rsync|rtsp[su]?|sftp|
    smtps?|svn(?:\+ssh)?|telnet|tftp|udp|vnc|wss?
)://
(?:
(?:\S+(?::\S*)?@)?
(?:
(?:
(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.
){3}
(?:25[0-5]|2[0-4]\d|[01]?\d\d?)
|
(?:(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+)
(?:\.(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+)
*(?:\.(?:[a-z\u00a1-\uffff0-9]-*){1,}[a-z\u00a1-\uffff0-9]{1,})?
|
/(?:[a-z\u00a1-\uffff0-9]-*)*[a-z\u00a1-\uffff0-9]+
)
(?::\d{1,5})?
(?:/[a-zA-Z0-9:@%\-._~!$&()*+,;=?/]*)?
(?:#[a-zA-Z0-9:@%\-._~!$&()*+,;=?/]*)?
)
)
)""",
        RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.DOTALL,
    )

    // Bare "www." hostnames (no scheme) — normalised to https:// on use.
    private val bareWwwPattern = Regex(
        "(www\\.)[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]",
        RegexOption.IGNORE_CASE,
    )

    data class UrlMatch(
        val url: String,
        val start: Int,
        val end: Int,
    )

    /**
     * Returns all URL matches in [text] with their positions, sorted by
     * position. Both schemed URLs and bare `www.` hostnames are matched.
     */
    fun findUrls(text: String): List<UrlMatch> {
        val result = mutableListOf<UrlMatch>()
        urlPattern.findAll(text).forEach {
            result.add(UrlMatch(normalizeUrl(it.value), it.range.first, it.range.last + 1))
        }
        bareWwwPattern.findAll(text).forEach {
            result.add(UrlMatch(normalizeUrl(it.value), it.range.first, it.range.last + 1))
        }
        return result.sortedBy { it.start }
    }

    /**
     * Checks if [word] contains a URL match (for word-level detection in the
     * classic terminal tap handler).
     */
    fun matches(word: String): Boolean =
        urlPattern.containsMatchIn(word) || bareWwwPattern.containsMatchIn(word)

    /**
     * Normalises a bare word into a clickable URL.
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
