package dev.drosh.terminal.engine.shell

/**
 * The shell's phase, as its `OSC 133` marks report it.
 *
 * A shell that has not opted in stays at [UNKNOWN], and the UI must not assume
 * otherwise: a terminal that shows "ready" for a shell that never said so is a
 * terminal that lies.
 */
enum class Phase(val code: Int) {
    /** No mark has been seen — the shell has not opted in. */
    UNKNOWN(0),

    /** The prompt is on screen, waiting for a command. */
    PROMPT(1),

    /** The user is typing the command. */
    TYPING(2),

    /** The command is running. */
    RUNNING(3),

    /** The command finished. */
    FINISHED(4);

    companion object {
        /** The phase for a code the native side reported. */
        fun of(code: Int): Phase = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * The `OSC 133` scanner, as Kotlin sees it.
 *
 * ## Why a scanner and not the engine
 *
 * `libghostty-vt` parses the sequences but exposes no API for them: Ghostty
 * reads them in its own Zig application layer, above the VT core. So does this.
 *
 * ## The incremental rule
 *
 * A mark can be split across two reads — a shell writing `ESC ] 133 ;` and then
 * `A ST` is what a slow pipe does. The scanner keeps a carry buffer and a mark
 * that does not complete is retried with the next chunk rather than dropped.
 *
 * The scanner never modifies the bytes: the same chunk goes to the parser
 * unchanged. It observes; it does not filter.
 */
class Scanner {

    private var carry = ByteArray(CARRY_BYTES)
    private var carryLength = 0

    /** The last phase the shell reported. */
    var phase: Phase = Phase.UNKNOWN
        private set

    private var directory = ByteArray(CARRY_BYTES)
    private var directoryLength = 0

    /**
     * Scan one chunk of the PTY's output.
     *
     * Returns the marks it completed. The strings in the marks are copies: a
     * caller that keeps one is not holding a buffer the scanner will reuse.
     */
    fun scan(chunk: ByteArray): List<Mark> {
        val out = ArrayList<Mark>(4)

        // The carried bytes and the new chunk are scanned as one buffer, so a
        // mark split across two reads is still found.
        //
        // The carry used to be written and never read: an incomplete mark was
        // kept for the next chunk and the next chunk was scanned on its own.
        // A shell's mark that landed across a read boundary — which is most of
        // them, since a read is however much the pipe had — was never found,
        // and the phase never moved off "unknown".
        val buffer = if (carryLength > 0) {
            ByteArray(carryLength + chunk.size).also {
                System.arraycopy(carry, 0, it, 0, carryLength)
                System.arraycopy(chunk, 0, it, carryLength, chunk.size)
            }
        } else {
            chunk
        }

        var index = 0
        while (index < buffer.size) {
            var start = index
            while (start < buffer.size && buffer[start] != ESC.toByte()) start += 1
            if (start >= buffer.size) break
            index = start

            val consumed = tryMark(buffer, index, out)
            if (consumed == 0) {
                // Incomplete: keep it for the next chunk.
                carryLength = (buffer.size - index).coerceAtMost(carry.size)
                System.arraycopy(buffer, index, carry, 0, carryLength)
                return out
            }
            index += consumed
        }

        carryLength = 0
        return out
    }

    /** The working directory the shell last reported, or null. */
    fun currentDirectory(): String? =
        if (directoryLength == 0) null else String(directory, 0, directoryLength, Charsets.UTF_8)

    /**
     * Try to read one mark at [offset]. Returns the bytes consumed, or 0 when
     * the mark is incomplete.
     */
    private fun tryMark(chunk: ByteArray, offset: Int, out: MutableList<Mark>): Int {
        // ESC ] ...
        if (offset + 1 >= chunk.size) return 0
        if (chunk[offset] != ESC.toByte() || chunk[offset + 1] != ']'.code.toByte()) return 0

        var end = offset + 2
        var terminatorLength = 0
        while (end < chunk.size) {
            if (chunk[end] == BEL.toByte()) {
                terminatorLength = 1
                break
            }
            if (chunk[end] == ESC.toByte()) {
                if (end + 1 >= chunk.size) return 0 // may be the start of ESC \
                if (chunk[end + 1] == '\\'.code.toByte()) {
                    terminatorLength = 2
                    break
                }
            }
            end += 1
        }
        if (terminatorLength == 0) return 0

        val payload = String(chunk, offset + 2, end - offset - 2, Charsets.UTF_8)
        dispatch(payload, out)
        return end + terminatorLength - offset
    }

    /** Turn an OSC payload into a mark. */
    private fun dispatch(payload: String, out: MutableList<Mark>) {
        if (payload.startsWith("133;")) {
            val rest = payload.substring(4)
            if (rest.isEmpty()) return

            when (rest[0]) {
                'A' -> {
                    phase = Phase.PROMPT
                    out.add(Mark.PromptStart)
                }

                'B' -> {
                    phase = Phase.TYPING
                    out.add(Mark.CommandStart)
                }

                'C' -> {
                    phase = Phase.RUNNING
                    out.add(Mark.CommandExec)
                }

                'D' -> {
                    phase = Phase.FINISHED
                    val status = if (rest.length > 2 && rest[1] == ';') {
                        rest.substring(2).toIntOrNull()
                    } else {
                        null
                    }
                    out.add(Mark.CommandFinished(status))
                }
            }
            return
        }

        if (payload.startsWith("7;")) {
            var value = payload.substring(2)
            // Strip the `file://host` prefix: the UI shows a path, not a URL.
            val scheme = value.indexOf("://")
            if (scheme >= 0) {
                val after = value.substring(scheme + 3)
                val slash = after.indexOf('/')
                if (slash >= 0) value = after.substring(slash)
            }
            storeDirectory(value)
            out.add(Mark.WorkingDirectory(value))
            return
        }

        if (payload.startsWith("1339;")) {
            out.add(Mark.EditorTarget(payload.substring(5)))
        }
    }

    private fun storeDirectory(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        directoryLength = bytes.size.coerceAtMost(directory.size)
        System.arraycopy(bytes, 0, directory, 0, directoryLength)
    }

    private companion object {
        const val ESC = 0x1b
        const val BEL = 0x07

        /** Far more than any real mark; the longest is an `OSC 7` path. */
        const val CARRY_BYTES = 512
    }
}

/** A mark, as the scanner reports it. */
sealed interface Mark {

    /** `OSC 133 ; A`: the prompt is showing. */
    data object PromptStart : Mark

    /** `OSC 133 ; B`: the user is typing. */
    data object CommandStart : Mark

    /** `OSC 133 ; C`: the command is running. */
    data object CommandExec : Mark

    /** `OSC 133 ; D`: the command finished, with its status when the shell said. */
    data class CommandFinished(val status: Int?) : Mark

    /** `OSC 7`: the working directory changed. */
    data class WorkingDirectory(val path: String) : Mark

    /** `OSC 1339`: the shell asked the editor to open a file. */
    data class EditorTarget(val path: String) : Mark
}
