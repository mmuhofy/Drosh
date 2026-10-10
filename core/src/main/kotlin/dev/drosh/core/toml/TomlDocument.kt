package dev.drosh.core.toml

/**
 * A minimal TOML reader/writer for Drosh's `settings.toml`.
 *
 * Deliberately not a general TOML implementation: it covers exactly what the
 * settings file uses — `[section]` headers, `key = value` pairs of string,
 * integer, float, boolean and string-array values, `#` comments and blank
 * lines — and refuses everything else rather than guessing. A settings file
 * is written by this class and read back by it, so the two stay in step by
 * construction; a hand-edit that lands outside the grammar is a
 * [TomlParseException] the caller answers by falling back to the backup copy,
 * not a silent misread.
 *
 * Why hand-rolled rather than a library: the file is small, flat and written
 * by us, and the dependency would buy table arrays, inline tables and date
 * types the settings surface has no use for.
 *
 * Pure JVM — no Android types, so it is unit-testable on the JVM.
 */
class TomlDocument {

    /** Section name → key → value, both in insertion order. */
    private val sections = LinkedHashMap<String, LinkedHashMap<String, TomlValue>>()

    /** Section names in the order they were added or parsed. */
    fun sectionNames(): List<String> = sections.keys.toList()

    /** Keys of [section] in order, or an empty list when the section is absent. */
    fun keysOf(section: String): List<String> =
        sections[section]?.keys?.toList() ?: emptyList()

    fun getString(section: String, key: String, default: String): String =
        getStringOrNull(section, key) ?: default

    /**
     * The raw string at [section].[key], or null when the key is absent or
     * holds another type.
     *
     * The gap this covers: an enum resolver answers its own default from a
     * null, and a "" would have to be distinguished from a real value that
     * happens to be empty.
     */
    fun getStringOrNull(section: String, key: String): String? =
        (sections[section]?.get(key) as? TomlValue.Str)?.value

    fun getInt(section: String, key: String, default: Int): Int =
        (sections[section]?.get(key) as? TomlValue.Int)?.value ?: default

    fun getFloat(section: String, key: String, default: Float): Float =
        (sections[section]?.get(key) as? TomlValue.Float)?.value ?: default

    fun getBoolean(section: String, key: String, default: Boolean): Boolean =
        (sections[section]?.get(key) as? TomlValue.Bool)?.value ?: default

    fun getStringList(section: String, key: String, default: List<String>): List<String> =
        (sections[section]?.get(key) as? TomlValue.StrList)?.value ?: default

    /**
     * True when [section] holds [key] at all, whatever its type.
     *
     * A key present with the wrong type is answered by the typed getters with
     * their default, so this is only needed to tell "absent" from "present but
     * unreadable" — which matters for migration, where a half-written file
     * must not be mistaken for a fresh install.
     */
    fun contains(section: String, key: String): Boolean =
        sections[section]?.containsKey(key) == true

    fun putString(section: String, key: String, value: String) {
        tableFor(section)[key] = TomlValue.Str(value)
    }

    fun putInt(section: String, key: String, value: Int) {
        tableFor(section)[key] = TomlValue.Int(value)
    }

    fun putFloat(section: String, key: String, value: Float) {
        tableFor(section)[key] = TomlValue.Float(value)
    }

    fun putBoolean(section: String, key: String, value: Boolean) {
        tableFor(section)[key] = TomlValue.Bool(value)
    }

    fun putStringList(section: String, key: String, value: List<String>) {
        tableFor(section)[key] = TomlValue.StrList(value.toList())
    }

    private fun tableFor(section: String): LinkedHashMap<String, TomlValue> =
        sections.getOrPut(section) { LinkedHashMap() }

    /**
     * Renders the document.
     *
     * Sections keep insertion order and are only written when non-empty, so a
     * document built by get-then-put does not grow empty headers. Floats go
     * through [Float.toString], which is locale-independent and round-trips
     * through the parser; integers never pick up a decimal point.
     */
    fun render(): String = buildString {
        sections.forEach { (name, entries) ->
            if (entries.isEmpty()) return@forEach
            if (isNotEmpty()) append('\n')
            append("[$name]\n")
            entries.forEach { (key, value) -> append(key).append(" = ").append(value.render()).append('\n') }
        }
    }

    companion object {

        /** Parses [text]. Throws [TomlParseException] on the first bad line. */
        fun parse(text: String): TomlDocument {
            val document = TomlDocument()
            var current: LinkedHashMap<String, TomlValue>? = null
            text.lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                val lineNumber = index + 1
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("[")) {
                    if (!line.endsWith("]") || line.length <= 2) {
                        throw TomlParseException("malformed section header at line $lineNumber: $line")
                    }
                    val name = line.substring(1, line.length - 1).trim()
                    if (name.isEmpty() || name.contains('[') || name.contains(']')) {
                        throw TomlParseException("malformed section name at line $lineNumber: $line")
                    }
                    current = document.tableFor(name)
                    return@forEachIndexed
                }
                if (current == null) {
                    throw TomlParseException("key outside any section at line $lineNumber: $line")
                }
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    throw TomlParseException("expected 'key = value' at line $lineNumber: $line")
                }
                val key = line.substring(0, eq).trim()
                if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
                    throw TomlParseException("malformed key at line $lineNumber: $line")
                }
                current[key] = parseValue(stripTrailingComment(line.substring(eq + 1).trim()), lineNumber)
            }
            return document
        }

        private fun parseValue(raw: String, lineNumber: Int): TomlValue = when {
            raw.startsWith("\"") -> TomlValue.Str(unescape(raw, lineNumber))
            raw.startsWith("[") -> TomlValue.StrList(parseStringArray(raw, lineNumber))
            raw == "true" -> TomlValue.Bool(true)
            raw == "false" -> TomlValue.Bool(false)
            INT.matches(raw) -> TomlValue.Int(raw.toInt())
            FLOAT.matches(raw) -> TomlValue.Float(raw.toFloat())
            else -> throw TomlParseException("unrecognised value at line $lineNumber: $raw")
        }

        /**
         * Drops a trailing `# comment`, quotes-aware.
         *
         * A quoted value can legitimately contain a `#` — a MOTD banner is
         * box-drawing and hashes among them — so the comment only starts where
         * the value has already ended. An unquoted value ends at its first
         * whitespace, and anything after that is only legal as a comment.
         */
        private fun stripTrailingComment(value: String): String {
            if (value.isEmpty()) return value
            if (value.startsWith("\"")) {
                var i = 1
                while (i < value.length) {
                    when (value[i]) {
                        '\\' -> i += 2
                        '"' -> {
                            val rest = value.substring(i + 1).trim()
                            if (rest.isEmpty()) return value
                            if (!rest.startsWith("#")) {
                                throw TomlParseException("unexpected text after string: $rest")
                            }
                            return value.substring(0, i + 1)
                        }
                        else -> i++
                    }
                }
                throw TomlParseException("unterminated string: $value")
            }
            if (value.startsWith("[")) return value
            val comment = value.indexOf('#')
            return if (comment < 0) value else value.substring(0, comment).trim()
        }

        private val INT = Regex("[+-]?[0-9]+")
        private val FLOAT = Regex("[+-]?[0-9]+\\.[0-9]+([eE][+-]?[0-9]+)?")

        private fun parseStringArray(raw: String, lineNumber: Int): List<String> {
            if (!raw.endsWith("]")) {
                throw TomlParseException("unterminated array at line $lineNumber: $raw")
            }
            val inner = raw.substring(1, raw.length - 1).trim()
            if (inner.isEmpty()) return emptyList()
            if (!inner.startsWith("\"")) {
                throw TomlParseException("only string arrays are supported at line $lineNumber: $raw")
            }
            val out = mutableListOf<String>()
            var rest = inner
            while (rest.isNotEmpty()) {
                if (!rest.startsWith("\"")) {
                    throw TomlParseException("expected a quoted string at line $lineNumber: $raw")
                }
                // Walk to the closing quote, skipping escaped ones, so a value
                // containing a comma or a bracket does not split the array.
                var i = 1
                while (i < rest.length) {
                    val c = rest[i]
                    if (c == '\\') { i += 2; continue }
                    if (c == '"') break
                    i++
                }
                if (i >= rest.length) {
                    throw TomlParseException("unterminated string at line $lineNumber: $raw")
                }
                out += unescape(rest.substring(0, i + 1), lineNumber)
                rest = rest.substring(i + 1).trim()
                if (rest.isEmpty()) break
                if (!rest.startsWith(",")) {
                    throw TomlParseException("expected ',' between array items at line $lineNumber: $raw")
                }
                rest = rest.substring(1).trim()
            }
            return out
        }

        /** Strips the surrounding quotes and resolves `\\`, `\"`, `\n` and `\t`. */
        private fun unescape(quoted: String, lineNumber: Int): String {
            if (quoted.length < 2 || !quoted.startsWith("\"") || !quoted.endsWith("\"")) {
                throw TomlParseException("expected a quoted string at line $lineNumber: $quoted")
            }
            val body = quoted.substring(1, quoted.length - 1)
            if ('\\' !in body) return body
            val out = StringBuilder(body.length)
            var i = 0
            while (i < body.length) {
                val c = body[i]
                if (c != '\\' || i == body.length - 1) {
                    out.append(c)
                    i++
                    continue
                }
                when (val escape = body[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    else -> throw TomlParseException("unknown escape '\\$escape' at line $lineNumber")
                }
                i += 2
            }
            return out.toString()
        }

        private fun String.escape(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }

    /** The value types the settings file can hold. */
    sealed interface TomlValue {
        fun render(): String

        class Str(val value: String) : TomlValue {
            override fun render(): String = "\"" + value.escape() + "\""
        }

        class Int(val value: kotlin.Int) : TomlValue {
            override fun render(): String = value.toString()
        }

        class Float(val value: kotlin.Float) : TomlValue {
            override fun render(): String = value.toString()
        }

        class Bool(val value: Boolean) : TomlValue {
            override fun render(): String = value.toString()
        }

        class StrList(val value: List<String>) : TomlValue {
            override fun render(): String =
                value.joinToString(", ", "[", "]") { "\"" + it.escape() + "\"" }
        }
    }
}

/** The file could not be read as TOML. The caller falls back to its backup. */
class TomlParseException(message: String) : RuntimeException(message)
