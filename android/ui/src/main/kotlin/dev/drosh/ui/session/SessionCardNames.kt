package dev.drosh.ui.session

import dev.drosh.domain.session.isGeneratedSessionName

/**
 * The name a card shows, in the order Termux treats as the session identity.
 *
 * Termux presents the title the program set with OSC 0 / OSC 2 as the session's
 * name — `TerminalSession.getTitle()` delegates to `TerminalEmulator.getTitle()`.
 * It never reads the foreground process name from /proc, which is why a runtime
 * binary such as `libchroot-ng.so` must never become a card title. Drosh follows
 * that order: OSC title first, then a user rename, then [fallbackName].
 *
 * A persisted rename lives in `session.name`. The default "Default" is generated
 * by the repository, not chosen by the user, so it never wins over a real title.
 * [localNames] is a rename staged by this screen, so it belongs to the same
 * "user rename" tier, just ahead of the stored value.
 *
 * Source: github.com/termux/termux-app —
 *   terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java
 */
internal fun sessionDisplayName(
    sessionId: String,
    localNames: Map<String, String>,
    titles: Map<String, String>,
    fallbackName: String,
    storedName: String? = null,
): String =
    titles[sessionId]?.takeIf { it.isNotBlank() }
        ?: localNames[sessionId]?.takeIf { it.isNotBlank() }
        ?: storedName?.takeUnless(::isGeneratedSessionName)
        ?: fallbackName

/**
 * The user rename shown on a card's second line when the OSC title took the
 * first. Null when there is no rename or it would repeat the first line.
 */
internal fun sessionSubtitle(
    sessionId: String,
    localNames: Map<String, String>,
    storedName: String?,
    displayName: String,
): String? {
    val rename = localNames[sessionId]?.takeIf { it.isNotBlank() }
        ?: storedName?.takeUnless(::isGeneratedSessionName)
    return rename?.takeIf { it != displayName }
}
