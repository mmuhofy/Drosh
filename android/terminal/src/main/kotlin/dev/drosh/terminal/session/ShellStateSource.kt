package dev.drosh.terminal.session

import dev.drosh.domain.block.ShellPhase
import dev.drosh.domain.block.ShellState
import dev.drosh.domain.block.ShellStateSource
import java.net.URLDecoder

/**
 * The shell state of a session that runs on the engine.
 *
 * The engine taps the shell-integration marks where the bytes enter, so it
 * knows the phase, the working directory and the editor target without anyone
 * reading the screen. This is the adapter that hands those facts to the block
 * engine in the shape the domain speaks.
 *
 * The engine half knows nothing about sessions and the session half knows
 * everything about them, so this file is where the two meet — and it lives on
 * the session side of that line.
 */
class ShellStateSource(
    private val session: ShellSession?,
) : ShellStateSource {

    // An engine with no marks to read answers nothing: the caller shows a
    // neutral shell state rather than an invented one.
    override fun currentState(): ShellState? = session?.shellState()
}

/**
 * The path inside a `file://` URI.
 *
 * OSC 7 sends a URI, not a path. The plus is protected on the way in,
 * because a plus is a legal character in a path and the decoder would
 * otherwise turn it into a space.
 */
private fun pathOf(uri: String): String? {
    if (!uri.startsWith(FILE_URI_PREFIX)) return null
    val raw = uri.removePrefix(FILE_URI_PREFIX)
    val decoded = try {
        URLDecoder.decode(raw.replace("+", PLUS_ESCAPE), "UTF-8")
    } catch (e: IllegalArgumentException) {
        raw
    }
    return decoded.ifEmpty { null }
}

/** The scheme OSC 7 uses. */
private const val FILE_URI_PREFIX = "file://"

/** What a plus becomes on the way into the decoder. */
private const val PLUS_ESCAPE = "%2B"
