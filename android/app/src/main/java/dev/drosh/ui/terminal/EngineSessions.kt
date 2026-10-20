package dev.drosh.ui.terminal

import dev.drosh.terminal.engine.core.EngineSession

/**
 * The engine-backed sessions the app knows about, by the app's session id.
 *
 * ## Why the app has to be told
 *
 * The engine's session belongs to the view that renders it
 * ([dev.drosh.terminal.engine.view.TerminalView]), so the app cannot look one
 * up by id on its own: whoever starts an engine session says which id it
 * answers to, and the log sheet finds it the same way it finds a Termux
 * session — through an id.
 *
 * ## Why it is empty today
 *
 * Every session in the app is still a Termux session. The engine's own
 * screen — the thing that would start engine sessions and register them here
 * — arrives with the migration (project P3). Until then [NONE] is the honest
 * answer: there is no engine session for any id, and the log sheet reads the
 * emulator's scrollback, which is where the output actually is.
 */
interface EngineSessions {

    /**
     * The engine session behind the app's session [sessionId], or null when
     * this id names no engine-backed session.
     */
    fun sessionFor(sessionId: String): EngineSession?

    companion object {

        /**
         * No engine-backed sessions: the app has not migrated to the engine
         * yet, and saying so is better than answering with a session that is
         * not there.
         */
        val NONE: EngineSessions = object : EngineSessions {
            override fun sessionFor(sessionId: String): EngineSession? = null
        }
    }
}
