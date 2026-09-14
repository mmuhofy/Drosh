package com.iris.irisshell.terminal

import com.iris.irisshell.domain.block.BlockEngineState
import com.iris.irisshell.domain.block.BlockRepository
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession

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
    override var lastDir: String = "~"
        private set

    private var pendingEcho: String? = null

    /** True while we are waiting for the echo of a `clear` command. */
    private var pendingEchoWasClear: Boolean = false

    fun onSessionTextChanged(session: TerminalSession) {
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
        if (appended.isEmpty()) return

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

    fun onCommandSubmitted(command: String) {
        pendingEcho = command
        if (command.trim() == "clear") pendingEchoWasClear = true
    }

    fun reset() {
        previousTranscript = ""
        pendingEcho = null
        blockRepository.clear()
    }

    private fun resetAnchor(current: String) {
        previousTranscript = current
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
    }

    private fun updateDirFromPrompt(prompt: String) {
        val match = PROMPT_DIR_REGEX.find(prompt) ?: return
        val path = match.groupValues[1]
        if (path.isBlank()) return
        val tail = path.trimEnd('/').substringAfterLast('/').ifBlank { "~" }
        lastDir = tail
    }
}
