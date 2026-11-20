package dev.drosh.terminal.session

import dev.drosh.domain.block.BlockEvent
import dev.drosh.domain.block.StyledLine
import dev.drosh.domain.block.StyledSpan

/**
 * Parses the block wire format defined in `native/src/block.rs`.
 *
 * ```text
 * S <idHex> <promptHex> <commandHex> <cwdHex> <startedMsDecimal>
 * O <idHex> <outputBytesHex>
 * F <idHex> <exitCodeDecimal> <completedMsDecimal>
 * ```
 *
 * Records are separated by `\n`, fields by one space. All text fields are
 * hexadecimal UTF-8 — output is raw PTY bytes with SGR still in them — so a
 * tab, a newline or a wide glyph cannot break the framing. Hand-rolled hex on
 * both sides keeps the native crate dependency-free.
 */
internal sealed interface ParsedRecord {
    data class Started(
        val id: String,
        val prompt: String,
        val command: String,
        val cwd: String?,
        val startedAtMs: Long,
    ) : ParsedRecord

    /** [text] is the raw decoded output; [lines] is its styled projection. */
    data class Output(
        val id: String,
        val text: String,
        val lines: List<StyledLine>,
    ) : ParsedRecord

    data class Finished(
        val id: String,
        val exitCode: Int,
        val completedAtMs: Long,
    ) : ParsedRecord
}

internal object BlockWireParser {

    /** Parse one drain's worth of records; malformed lines are ignored. */
    fun parse(wire: String, styles: SgrStyledLineParser): List<ParsedRecord> {
        if (wire.isEmpty()) return emptyList()
        return wire.split('\n').mapNotNull { line ->
            val fields = line.split(' ')
            when (fields.firstOrNull()) {
                "S" -> {
                    if (fields.size != 6) return@mapNotNull null
                    ParsedRecord.Started(
                        id = fields[1],
                        prompt = decodeText(fields[2]) ?: return@mapNotNull null,
                        command = decodeText(fields[3]) ?: return@mapNotNull null,
                        cwd = decodeText(fields[4])?.takeIf { it.isNotEmpty() },
                        startedAtMs = fields[5].toLongOrNull() ?: 0L,
                    )
                }
                "O" -> {
                    if (fields.size != 3) return@mapNotNull null
                    val bytes = decodeHex(fields[2]) ?: return@mapNotNull null
                    val text = String(bytes, Charsets.UTF_8)
                    ParsedRecord.Output(
                        id = fields[1],
                        text = text,
                        lines = styles.parse(text),
                    )
                }
                "F" -> {
                    if (fields.size != 4) return@mapNotNull null
                    ParsedRecord.Finished(
                        id = fields[1],
                        exitCode = fields[2].toIntOrNull() ?: return@mapNotNull null,
                        completedAtMs = fields[3].toLongOrNull() ?: 0L,
                    )
                }
                else -> null
            }
        }
    }

    /** The domain shape of the same records, for consumers that want events. */
    fun toEvents(records: List<ParsedRecord>): List<BlockEvent> = records.map { record ->
        when (record) {
            is ParsedRecord.Started -> BlockEvent.Started(
                record.id, record.prompt, record.command, record.cwd, record.startedAtMs,
            )
            is ParsedRecord.Output -> BlockEvent.Output(record.id, record.lines)
            is ParsedRecord.Finished -> BlockEvent.Finished(
                record.id, record.exitCode, record.completedAtMs,
            )
        }
    }

    fun decodeHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var index = 0
        while (index < out.size) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
            index++
        }
        return out
    }

    /** The inverse, used by tests to build wire fields. */
    fun encodeHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value shr 4])
            out.append(digits[value and 0x0f])
        }
        return out.toString()
    }

    private fun decodeText(hex: String): String? =
        decodeHex(hex)?.let { String(it, Charsets.UTF_8) }
}

/**
 * Turns the ANSI SGR stream of one command's output into styled lines.
 *
 * Style state lives across chunks: a colour set in one read applies to text in
 * the next one. The accumulator of *spans* does not — the repository merges a
 * trailing partial line into the next chunk's first line, so carrying it here
 * as well would duplicate it.
 */
internal class SgrStyledLineParser {

    private var fg: Int? = null
    private var bg: Int? = null
    private var bold = false
    private var italic = false
    private var underline = false
    private val spans = mutableListOf<StyledSpan>()
    private val text = StringBuilder()
    private val pendingCsi = StringBuilder()
    /** Style frozen when the open span began; a later SGR must not rewrite it. */
    private var spanFg: Int? = null
    private var spanBg: Int? = null
    private var spanBold = false
    private var spanItalic = false
    private var spanUnderline = false
    private var hasSpan = false

    fun parse(chunk: String): List<StyledLine> {
        val lines = mutableListOf<StyledLine>()
        // A CSI left unfinished by the previous read continues here, without
        // another ESC. Finish it before looking at ordinary text.
        var start = 0
        if (pendingCsi.isNotEmpty()) {
            val final = finalIndexOf(chunk, 0)
            if (final < 0) {
                pendingCsi.append(chunk)
                return lines
            }
            val params = pendingCsi.toString() + chunk.substring(0, final)
            pendingCsi.setLength(0)
            applySgr(params)
            start = final + 1
        }
        var index = start
        while (index < chunk.length) {
            val char = chunk[index]
            when {
                char == ESC && index + 1 < chunk.length && chunk[index + 1] == '[' -> {
                    val final = finalIndexOf(chunk, index + 2)
                    if (final < 0) {
                        // A CSI split across reads: hold its parameters until
                        // the final byte arrives, never print them as text.
                        pendingCsi.append(chunk, index + 2, chunk.length)
                        index = chunk.length
                    } else {
                        val params = pendingCsi.toString() + chunk.substring(index + 2, final)
                        pendingCsi.setLength(0)
                        applySgr(params)
                        index = final + 1
                    }
                }
                char == ESC -> index += 1 // a lone escape carries no printable text
                char == '\r' -> index += 1 // a carriage return is a cursor move
                char == '\n' -> {
                    lines.add(finishLine())
                    index += 1
                }
                else -> {
                    appendText(char)
                    index += 1
                }
            }
        }
        if (text.isNotEmpty() || spans.isNotEmpty()) {
            lines.add(finishLine())
        }
        return lines
    }

    /** Called at a block boundary: no style from the previous command carries over. */
    fun reset() {
        fg = null
        bg = null
        bold = false
        italic = false
        underline = false
        spans.clear()
        text.setLength(0)
        pendingCsi.setLength(0)
        hasSpan = false
    }

    private fun finishLine(): StyledLine {
        flushSpan()
        val line = StyledLine(spans.toList())
        spans.clear()
        return line
    }

    /** Split the open run when the style changes mid-text. */
    private fun appendText(char: Char) {
        if (hasSpan && (spanFg != fg || spanBg != bg || spanBold != bold ||
            spanItalic != italic || spanUnderline != underline)
        ) {
            flushSpan()
        }
        if (!hasSpan) {
            spanFg = fg
            spanBg = bg
            spanBold = bold
            spanItalic = italic
            spanUnderline = underline
            hasSpan = true
        }
        text.append(char)
    }

    private fun flushSpan() {
        if (text.isEmpty()) return
        spans.add(StyledSpan(text.toString(), spanFg, spanBg, spanBold, spanItalic, spanUnderline))
        text.setLength(0)
        hasSpan = false
    }

    private fun finalIndexOf(chunk: String, start: Int): Int {
        for (index in start until chunk.length) {
            val char = chunk[index]
            if (char in '\u0040'..'\u007e') return index
        }
        return -1
    }

    private fun applySgr(parameters: String) {
        if (parameters.isEmpty()) {
            resetStyle()
            return
        }
        val codes = parameters.split(';').map { it.toIntOrNull() ?: 0 }
        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> resetStyle()
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                39 -> fg = null
                49 -> bg = null
                38, 48 -> {
                    val extended = extendedColor(codes, index)
                    if (extended != null) {
                        if (code == 38) fg = extended.first else bg = extended.first
                        index = extended.second
                    }
                }
                in 30..37 -> fg = ANSI_COLORS[code - 30]
                in 40..47 -> bg = ANSI_COLORS[code - 40]
                in 90..97 -> fg = ANSI_COLORS[code - 90 + 8]
                in 100..107 -> bg = ANSI_COLORS[code - 100 + 8]
            }
            index++
        }
    }

    private fun resetStyle() {
        fg = null
        bg = null
        bold = false
        italic = false
        underline = false
    }

    /** @return the packed ARGB colour and the index of its last consumed code. */
    private fun extendedColor(codes: List<Int>, at: Int): Pair<Int, Int>? {
        if (at + 1 >= codes.size) return null
        return when (codes[at + 1]) {
            5 -> if (at + 2 < codes.size) color256(codes[at + 2]) to at + 2 else null
            2 -> if (at + 4 < codes.size) {
                argb(codes[at + 2], codes[at + 3], codes[at + 4]) to at + 4
            } else {
                null
            }
            else -> null
        }
    }

    private fun color256(index: Int): Int = when (index) {
        in 0..15 -> ANSI_COLORS[index]
        in 16..231 -> {
            val value = index - 16
            val level = intArrayOf(0, 95, 135, 175, 215, 255)
            argb(level[value / 36], level[(value / 6) % 6], level[value % 6])
        }
        in 232..255 -> {
            val gray = 8 + (index - 232) * 10
            argb(gray, gray, gray)
        }
        else -> 0
    }

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or ((red and 0xff) shl 16) or ((green and 0xff) shl 8) or (blue and 0xff)

    private companion object {
        const val ESC = '\u001b'
        val ANSI_COLORS: IntArray = intArrayOf(
            0x000000, 0xcd3131, 0x0dbc79, 0xe5e510, 0x2472c8, 0xbc3fbc, 0x11a8cd, 0xe5e5e5,
            0x666666, 0xf14c4c, 0x23d18b, 0xf5f543, 0x3b8eea, 0xd670d6, 0x29b8db, 0xffffff,
        ).map { (0xff shl 24) or it }.toIntArray()
    }
}
