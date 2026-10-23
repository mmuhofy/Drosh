package dev.drosh.ui.terminal

import dev.drosh.terminal.session.EngineSessions
import dev.drosh.terminal.session.SessionTranscript
import dev.drosh.terminal.session.TerminalManager
import dev.drosh.ui.session.SessionLogSource

/**
 * A session's terminal output, read out of the terminal that owns it.
 *
 * ## Why a transcript and not an emulator
 *
 * The log sheet used to be handed an emulator and read its scrollback, which
 * tied it to the vendored Termux terminal. The engine keeps its own copy of
 * what a session printed, so the question "what did this session print?" now
 * has two answers, and the sheet must not care which one it gets. It asks for
 * a [SessionTranscript] instead, and the transcript says which terminal it
 * came from.
 *
 * ## Where the Termux path went
 *
 * Nowhere. It is [SessionTranscript.Termux] now, with the same cap, the same
 * stripping and the same trimming as before — the sheet still reads the
 * emulator's scrollback for every session the app runs today.
 *
 * ## Why this class lives in the app
 *
 * It implements [SessionLogSource], a `:ui` interface, and `:terminal` may
 * not import `:ui` (docs/ARCHITECTURE.md §3). The wiring — which sessions
 * the app has, and which of them are engine-backed — is the app's business
 * anyway.
 */
class TerminalSessionLogSource(
    private val terminalManager: TerminalManager,
    private val engineSessions: EngineSessions,
) : SessionLogSource {

    override fun lines(sessionId: String): List<String> =
        transcriptFor(sessionId)?.lines ?: emptyList()

    /**
     * The transcript of [sessionId], from the terminal that owns it.
     *
     * The engine is asked first, because the engine is where the sessions are
     * going: once a session has been migrated it answers from the engine's
     * copy of its output, and only a session that is still a Termux session
     * falls through to the emulator's scrollback.
     *
     * A session that is neither — one that has exited, or one that was never
     * started — has no transcript at all, and the sheet shows its empty state.
     */
    private fun transcriptFor(sessionId: String): SessionTranscript? {
        val engineSession = engineSessions.sessionFor(sessionId)
        if (engineSession != null) {
            return SessionTranscript.Engine.of(engineSession)
        }
        return null
    }
}
