package dev.drosh.terminal.session

import dev.drosh.domain.block.BlockEngineState
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.ShellPhase
import dev.drosh.domain.block.ShellState
import dev.drosh.domain.block.ShellStateSource
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import dev.drosh.terminal.engine.core.EngineSession

/**
 * Wires the Termux terminal session into the block engine via the
 * transcript snapshot of the emulator screen.
 *
 * Each [onSessionTextChanged] call:
 *  1. Pulls the current transcript.
 *  2. Strips ANSI sequences via [AnsiStripper].
 *  3. Diffs against the previously seen transcript using a rolling
 *     anchor (longest common tail-substring) so output survives
 *     buffer scroll.
 *  4. Detects the shell prompt suffix on the **last** line of the new
 *     text — if present, closes the running block and records the
 *     captured prompt for next time.
 *  5. Echo-suppression: drops the first appended line if it matches
 *     the most recently submitted command.
 *
 * This is a **transcript polling** design — every UI frame, the wire
 * compares what the user can see now versus what we last saw, and
 * pushes the difference into the block repository. See PLAN.md §3.
 *
 * The *state* — the phase, the working directory and the editor target — is
 * not inferred here when the terminal can report it. [ShellStateSource] is
 * the seam: a vendored Termux session binds no source, so the wire keeps
 * reading the screen exactly as it always has, while a session that runs on
 * the engine binds the engine's own state, and the marks the engine taps —
 * not the shape of a prompt — decide those facts. The text (the transcript,
 * the prompt, the command) stays this class's work either way.
 */
class BlockEngineWire(
    private val blockRepository: BlockRepository,
) : BlockEngineState {

    private var previousTranscript: String = ""

    @Volatile
    override var lastPrompt: String = DEFAULT_PROMPT
        private set

    /** Last directory inferred from the prompt's path component. */
    @Volatile
    private var inferredDir: String = "~"

    /**
     * The directory the session is in.
     *
     * The engine reports the working directory exactly (`OSC 7`), so an
     * engine-backed session answers with that path's last segment — the same
     * shape the prompt gives. A vendored Termux session has no such report
     * the block engine can read, so the wire keeps the segment it inferred
     * from the prompt.
     */
    override val lastDir: String
        get() = reportedShellState()?.workingDirectory?.let { lastSegmentOf(it) } ?: inferredDir

    /**
     * False while an interactive program owns the terminal. See
     * [BlockEngineState.awaitingShellInput] for why the UI needs this.
     */
    @Volatile
    private var inferredAwaitingShellInput: Boolean = true

    /**
     * True when the shell itself is at a prompt and waiting for a command.
     *
     * The engine's marks answer this: the shell is at its prompt between the
     * prompt mark and the command mark, and it is not there once the command
     * runs. The marks cannot tell an interactive program from a plain command,
     * so this answer is coarser than the screen's — the screen knows a REPL by
     * its prompt, the marks only know that something is running.
     *
     * A vendored Termux session has no such report, so the wire keeps
     * reading the last line of the screen.
     */
    override val awaitingShellInput: Boolean
        get() = when (reportedShellState()?.phase) {
            ShellPhase.PROMPT, ShellPhase.TYPING -> true
            ShellPhase.RUNNING, ShellPhase.FINISHED -> false
            null, ShellPhase.UNKNOWN -> inferredAwaitingShellInput
        }

    /**
     * Where the shell is in its cycle, as the terminal reports it.
     *
     * [ShellPhase.UNKNOWN] when nothing reports it, which is the case for a
     * vendored Termux session: the wire infers the phase from the screen and
     * has no marks to read.
     */
    override val shellPhase: ShellPhase
        get() = reportedShellState()?.phase ?: ShellPhase.UNKNOWN

    /** The working directory the shell reported, when it reported one. */
    override val workingDirectory: String?
        get() = reportedShellState()?.workingDirectory

    /** The file the shell last asked the editor to open, when it reported one. */
    override val editorTarget: String?
        get() = reportedShellState()?.editorTarget

    /**
     * The shell state of the active session, when the terminal knows it.
     *
     * Null for a vendored Termux session: the emulator's shell-integration
     * state is not something the block engine reads, so the wire keeps
     * inferring the phase, the directory and the editor target from the
     * screen.
     */
    @Volatile
    private var shellStateSource: ShellStateSource? = null

    private var pendingEcho: String? = null

    /** True while we are waiting for the echo of a `clear` command. */
    private var pendingEchoWasClear: Boolean = false

    /**
     * The session whose output is being ingested. Block mode renders the same
     * sessions classic mode does, so a background session printing something
     * must not append to whatever the user is looking at.
     */
    private var activeSessionId: String? = null

    /**
     * Rebinds to another session without discarding its history. The diff anchor
     * is re-seeded from the new session's own transcript so the first poll
     * cannot mistake its backlog for fresh output.
     *
     * [shellState] is the state source that goes with the session: null for a
     * vendored Termux session, which has none the block engine can read. The
     * engine's session is not a [TerminalSession] — it has no screen to diff —
     * so it arrives with a source and no session.
     */
    fun onSessionChanged(
        sessionId: String?,
        session: TerminalSession?,
        shellState: ShellStateSource? = null,
    ) {
        // The source belongs to the session, so it is bound before the early
        // return: the same session bound again may come with a source where it
        // had none.
        shellStateSource = shellState
        if (activeSessionId == sessionId) return
        activeSessionId = sessionId
        previousTranscript = session?.let { snapshotOf(it) } ?: ""
        pendingEcho = null
        blockRepository.setActiveSession(sessionId)
    }

    private fun snapshotOf(session: TerminalSession): String {
        val emulator = session.emulator ?: return ""
        return AnsiStripper.strip(emulator.getScreen().getTranscriptTextWithoutJoinedLines())
    }

    /**
     * Re-seeds the diff anchor against [session]'s current transcript without
     * changing which session is active. Used after a restart, where the id is
     * unchanged but the process and everything on screen is new.
     */
    fun reanchor(session: TerminalSession) {
        previousTranscript = snapshotOf(session)
        pendingEcho = null
    }

    fun onSessionTextChanged(session: TerminalSession, sessionId: String?) {
        if (sessionId != activeSessionId) return
        val emulator: TerminalEmulator = session.emulator ?: return
        val raw = emulator.getScreen().getTranscriptTextWithoutJoinedLines()
        val current = AnsiStripper.strip(raw)
        if (current.isEmpty()) {
            previousTranscript = ""
            return
        }

        val appended = if (previousTranscript.isEmpty()) {
            current
        } else {
            val anchor = findRollingAnchor(previousTranscript, current)
                ?: return resetAnchor(current)
            val idx = current.lastIndexOf(anchor)
            if (idx < 0) return resetAnchor(current)
            current.substring(idx + anchor.length)
        }
        previousTranscript = current

        // A program that has taken the terminal, such as a Python REPL, leaves a
        // marker of its own instead of a shell prompt. Decided before the
        // input-line handling below, because a line typed into a REPL is echoed
        // back and must not be swallowed as a shell echo.
        inferredAwaitingShellInput = !isProgramPrompt(current.substringAfterLast('\n').trimEnd())

        if (appended.isEmpty()) return

        val lines = appended.split('\n')
        val nonEmpty = lines
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() || lines.size == 1 }
        if (nonEmpty.isEmpty()) return

        // The transcript is diffed, but the terminal *rewrites* the line the
        // user is typing on every keystroke. Without this the diff sees each new
        // character as appended output, and a command typed in classic mode
        // arrives at the block engine one character per block. The live input
        // line is pulled off here and kept as the pending echo instead.
        //
        // Skipped while a program owns the terminal: there the typed line is the
        // program's own prompt and its echo belongs in the output.
        val liveLine = current.substringAfterLast('\n').trimEnd('\r')
        val inputMatch = if (awaitingShellInput) {
            PROMPT_WITH_INPUT_REGEX.find(liveLine.trimEnd())
        } else null
        val isEditing = inputMatch != null

        val firstIsEcho = pendingEcho?.let { echo ->
            nonEmpty.first().trimEnd() == echo || nonEmpty.first().trimEnd().endsWith(echo)
        } ?: false
        if (firstIsEcho) pendingEcho = null
        val linesAfterEcho = if (firstIsEcho) nonEmpty.drop(1) else nonEmpty

        if (isEditing) {
            // Whatever has been typed so far is the echo that will arrive when
            // the command actually runs, so suppress it then. The prompt is
            // group 1, the typed text group 2.
            val typed = inputMatch!!.groupValues[2].trimEnd()
            if (typed.isNotEmpty()) pendingEcho = typed
            updatePromptFrom(inputMatch.groupValues[1])
            if (linesAfterEcho.size == 1) {
                // The only appended line is the input line being edited.
                return
            }
            // Anything before it is real output; the input line is not.
            blockRepository.onBootOutput(linesAfterEcho.dropLast(1).joinToString("\n"))
            return
        }

        // `clear` command — drop all blocks and skip output.
        if (pendingEchoWasClear) {
            pendingEchoWasClear = false
            blockRepository.clear()
            return
        }

        if (linesAfterEcho.isEmpty()) return

        val lastLineOfAppended = linesAfterEcho.last()
        val promptSuffix = PROMPT_SUFFIX_REGEX.find(lastLineOfAppended)
        val completeAppended = promptSuffix != null
        val fullPromptLine = if (completeAppended) {
            lastLineOfAppended.trimEnd('\r').trimEnd()
        } else ""
        val promptText = if (completeAppended) {
            lastLineOfAppended.substring(0, promptSuffix!!.range.first).trimEnd()
        } else ""

        if (completeAppended) {
            // Drop the prompt line itself — only push the lines that
            // precede it (the actual command output).
            val outputLines = linesAfterEcho.dropLast(1)
            if (outputLines.isNotEmpty()) {
                blockRepository.onOutputChunk(outputLines.joinToString("\n"))
            }
            lastPrompt = fullPromptLine.ifBlank { DEFAULT_PROMPT }
            updateDirFromPrompt(promptText.ifBlank { DEFAULT_PROMPT })
            pendingEcho = null
            blockRepository.onCommandCompleted(exitCode = 0)
            return
        }

        blockRepository.onBootOutput(linesAfterEcho.joinToString("\n"))

        val lastVisibleLine = current.substringAfterLast('\n').trimEnd('\r')
        if (linesAfterEcho.size == 1 && lastLineOfAppended != lastVisibleLine) {
            val visibleSuffix = PROMPT_SUFFIX_REGEX.find(lastVisibleLine)
            if (visibleSuffix != null) {
                val visiblePromptText = lastVisibleLine.substring(0, visibleSuffix.range.first).trimEnd()
                val visibleFullPrompt = lastVisibleLine.trimEnd()
                lastPrompt = visibleFullPrompt.ifBlank { DEFAULT_PROMPT }
                updateDirFromPrompt(visiblePromptText.ifBlank { DEFAULT_PROMPT })
                pendingEcho = null
                blockRepository.onCommandCompleted(exitCode = 0)
            }
        }
    }

    /**
     * Engine-backed session text change.
     *
     * Mirrors [onSessionTextChanged] but reads the transcript from the
     * Rust engine instead of the Termux emulator's screen. The diff logic
     * is the same; only the transcript source differs.
     */
    fun onEngineSessionTextChanged(session: EngineSession, sessionId: String?) {
        if (sessionId != activeSessionId) return
        val current = session.transcript().joinToString("\n")
        if (current.isEmpty()) {
            previousTranscript = ""
            return
        }

        val appended = if (previousTranscript.isEmpty()) {
            current
        } else {
            val anchor = findRollingAnchor(previousTranscript, current)
                ?: return resetAnchor(current)
            val idx = current.lastIndexOf(anchor)
            if (idx < 0) return resetAnchor(current)
            current.substring(idx + anchor.length)
        }
        previousTranscript = current

        val liveLine = current.substringAfterLast('\n').trimEnd('\r')
        val inputMatch = if (awaitingShellInput) {
            PROMPT_WITH_INPUT_REGEX.find(liveLine.trimEnd())
        } else null
        val isEditing = inputMatch != null

        val lines = appended.split('\n')
        val nonEmpty = lines
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() || lines.size == 1 }
        if (nonEmpty.isEmpty()) return

        val firstIsEcho = pendingEcho?.let { echo ->
            nonEmpty.first().trimEnd() == echo || nonEmpty.first().trimEnd().endsWith(echo)
        } ?: false
        if (firstIsEcho) pendingEcho = null
        val linesAfterEcho = if (firstIsEcho) nonEmpty.drop(1) else nonEmpty

        if (isEditing) {
            val typed = inputMatch!!.groupValues[2].trimEnd()
            if (typed.isNotEmpty()) pendingEcho = typed
            updatePromptFrom(inputMatch.groupValues[1])
            if (linesAfterEcho.size == 1) return
            blockRepository.onBootOutput(linesAfterEcho.dropLast(1).joinToString("\n"))
            return
        }

        if (pendingEchoWasClear) {
            pendingEchoWasClear = false
            blockRepository.clear()
            return
        }

        if (linesAfterEcho.isEmpty()) return

        val lastLineOfAppended = linesAfterEcho.last()
        val promptSuffix = PROMPT_SUFFIX_REGEX.find(lastLineOfAppended)
        val completeAppended = promptSuffix != null

        if (completeAppended) {
            val outputLines = linesAfterEcho.dropLast(1)
            if (outputLines.isNotEmpty()) {
                blockRepository.onOutputChunk(outputLines.joinToString("\n"))
            }
            lastPrompt = lastLineOfAppended.trimEnd('\r').trimEnd().ifBlank { DEFAULT_PROMPT }
            updateDirFromPrompt(lastLineOfAppended.substring(0, promptSuffix!!.range.first).trimEnd().ifBlank { DEFAULT_PROMPT })
            pendingEcho = null
            blockRepository.onCommandCompleted(exitCode = 0)
            return
        }

        blockRepository.onBootOutput(linesAfterEcho.joinToString("\n"))

        val lastVisibleLine = current.substringAfterLast('\n').trimEnd('\r')
        if (linesAfterEcho.size == 1 && lastLineOfAppended != lastVisibleLine) {
            val visibleSuffix = PROMPT_SUFFIX_REGEX.find(lastVisibleLine)
            if (visibleSuffix != null) {
                val visiblePromptText = lastVisibleLine.substring(0, visibleSuffix.range.first).trimEnd()
                val visibleFullPrompt = lastVisibleLine.trimEnd()
                lastPrompt = visibleFullPrompt.ifBlank { DEFAULT_PROMPT }
                updateDirFromPrompt(visiblePromptText.ifBlank { DEFAULT_PROMPT })
                pendingEcho = null
                blockRepository.onCommandCompleted(exitCode = 0)
            }
        }
    }

    /**
     * Re-seed the diff anchor against an engine session's transcript.
     */
    fun reanchorEngine(session: EngineSession) {
        previousTranscript = session.transcript().joinToString("\n")
        pendingEcho = null
    }

    fun onCommandSubmitted(command: String) {
        pendingEcho = command
        if (command.trim() == "clear") pendingEchoWasClear = true
    }

    /** Clears only the active session's blocks and re-anchors the diff. */
    fun reset() {
        previousTranscript = ""
        pendingEcho = null
        blockRepository.clear()
    }

    private fun resetAnchor(current: String) {
        previousTranscript = current
    }

    /** Records the prompt and directory from a line shaped `<prompt><typed>`. */
    private fun updatePromptFrom(promptText: String) {
        val prompt = promptText.trimEnd()
        if (prompt.isBlank()) return
        lastPrompt = prompt
        updateDirFromPrompt(prompt)
    }

    /**
     * True when the live line looks like a prompt belonging to an interactive
     * program rather than to the shell.
     *
     * Deliberately a short, conservative list: a false positive hides the input
     * bar, and a false negative sends REPL input through the command path. These
     * are the markers a shell hands over with, and extending the set is a
     * one-line change.
     */
    private fun isProgramPrompt(line: String): Boolean {
        if (line.isEmpty()) return false
        // A shell prompt always ends in one of these, so its presence means the
        // shell still owns the terminal.
        if (PROMPT_SUFFIX_REGEX.containsMatchIn(line) &&
            PROMPT_WITH_INPUT_REGEX.matches(line)
        ) return false
        return PROGRAM_PROMPT_REGEX.containsMatchIn(line)
    }

    private fun findRollingAnchor(previous: String, current: String): String? {
        val maxLen = minOf(previous.length, MAX_ANCHOR_BYTES)
        val minLen = minOf(maxLen, MIN_ANCHOR_BYTES)
        var len = maxLen
        while (len >= minLen) {
            val tail = previous.substring(previous.length - len)
            if (current.lastIndexOf(tail) >= 0) return tail
            len--
        }
        return null
    }

    private companion object {
        const val DEFAULT_PROMPT = "muhofy@drosh:~/Drosh$"
        const val MIN_ANCHOR_BYTES = 16
        const val MAX_ANCHOR_BYTES = 8192
        val PROMPT_SUFFIX_REGEX = Regex("""[#$❯➜]\s*$""")
        // Last `:`..suffix segment is the path: `user@host:~/path`.
        val PROMPT_DIR_REGEX = Regex(""":([^:$#❯➜]*)$""")

        /**
         * A prompt with the user mid-command: `~$ ls`, `muhofy@drosh:~/Drosh$ git s`,
         * `~/Drosh git:(main) ❯ npm i`. Group 1 is the prompt, group 2 the typed text.
         *
         * The leading lookahead requires the line's first token to contain `@`,
         * `/` or `~`, which is what keeps output such as `Total cost: $ 5.00` or
         * `100% $ 3 done` from being read as a prompt with an argument.
         */
        val PROMPT_WITH_INPUT_REGEX = Regex("""^(?=[^\s]*[@~/])([^\n]*?[$#❯➜])[ \t]+(\S.*)$""")

        /**
         * Prompts of interactive programs that take the terminal over from the
         * shell: Python's `>>>` and its `...` continuation, psql's `=>`, node's
         * `>`, irb's `>>`.
         */
        val PROGRAM_PROMPT_REGEX = Regex("""(>>>|\.\.\.|=>)\s*$""")
    }

    private fun updateDirFromPrompt(prompt: String) {
        val match = PROMPT_DIR_REGEX.find(prompt) ?: return
        val path = match.groupValues[1]
        if (path.isBlank()) return
        inferredDir = lastSegmentOf(path)
    }

    /**
     * The last segment of a path, or `~` when the path has none.
     *
     * The prompt's path and the engine's working directory are the same kind
     * of thing seen from two sides, so both end up as the short label the
     * block header shows.
     */
    private fun lastSegmentOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { "~" }

    /**
     * The shell state the terminal reports, or null when it reports none.
     */
    private fun reportedShellState(): ShellState? = shellStateSource?.currentState()
}
