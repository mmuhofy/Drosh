package dev.drosh.terminal.session

import dev.drosh.domain.block.ShellPhase
import dev.drosh.domain.block.ShellState
import dev.drosh.domain.block.ShellStateSource
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.BlockState as EngineBlockState
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
class EngineShellStateSource(
    private val session: EngineSession,
) : ShellStateSource {

    override fun currentState(): ShellState? = session.blockState().toShellState()
}

/**
 * The domain shape of the engine's block state.
 *
 * Null when the engine knows nothing: a session that has not seen a mark has
 * no phase, no directory and no editor target, and saying so is honest where
 * inventing a phase would not be.
 */
internal fun EngineBlockState.toShellState(): ShellState? {
    val shellPhase = when (this.phase) {
        EngineBlockState.PHASE_PROMPT -> ShellPhase.PROMPT
        EngineBlockState.PHASE_TYPING -> ShellPhase.TYPING
        EngineBlockState.PHASE_RUNNING -> ShellPhase.RUNNING
        EngineBlockState.PHASE_FINISHED -> ShellPhase.FINISHED
        else -> ShellPhase.UNKNOWN
    }
    val directory = this.workingDirectory?.let { pathOf(it) }
    val editor = this.editorTarget?.takeIf { it.isNotBlank() }
    if (shellPhase == ShellPhase.UNKNOWN && directory == null && editor == null) {
        return null
    }
    return ShellState(
        phase = shellPhase,
        exitStatus = status,
        workingDirectory = directory,
        editorTarget = editor,
    )
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
