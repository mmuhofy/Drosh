package dev.drosh.domain.block

/**
 * Where the block engine gets the facts the terminal already knows.
 *
 * The block engine used to infer the phase, the working directory and the
 * editor target by reading the screen, because the screen was all it had. The
 * engine knows them exactly, from the shell-integration marks it taps where
 * the bytes enter, so this is the seam between the two: the engine's session
 * answers with what the marks say, and a terminal that cannot report them
 * answers with null — which leaves the block engine inferring, exactly as it
 * did before.
 *
 * Implementations are read on demand rather than pushed: the block mode polls
 * its state anyway, and a pull keeps the source free of a lifetime of its
 * own.
 */
interface ShellStateSource {
    /**
     * The state of the shell behind the session, or null when the source has
     * nothing to say — a session the terminal does not know, or a shell that
     * has not reported in yet.
     */
    fun currentState(): ShellState?
}
