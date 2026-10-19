package dev.drosh.data.backup.termux

/**
 * A Termux dotfile, rewritten for the Ubuntu userspace Drosh runs under
 * PRoot.
 *
 * A `.zshrc` written on Termux is full of references to a world that does
 * not exist here: `$PREFIX` paths that point into the Android app's data
 * directory, `pkg install` aliases, `termux-open-url` calls, a
 * `LD_LIBRARY_PATH` aimed at bionic's libraries. Carrying them across
 * verbatim would give the user a shell that fails on startup — every
 * new terminal in Drosh would open with a syntax error and an empty
 * `$PATH`.
 *
 * The rewrite is **line-wise**: a line is either kept exactly as the user
 * wrote it or dropped. That is a deliberate choice over "clever" in-place
 * editing of `$PREFIX` path segments. Dropping the path segment from
 * `PATH="$PREFIX/bin:$PATH"` would leave `PATH=":$PATH"` — an empty entry,
 * which every shell reads as "the current directory", a well-known
 * way to end up running a binary out of `~/downloads`. Half-rewritten
 * rc files fail in far more confusing ways than the lines we lose, and
 * what is lost is counted ([DotfileResult.droppedLineCount]) so the caller
 * can still put the original in front of the user.
 *
 * What we keep: aliases, exports, prompt and history settings, shell
 * options, `source` of user-local paths. What we drop: anything Termux-
 * specific. A whole-line comment (`# …`) is *inert* and is kept even if it
 * mentions `$PREFIX` — comments are how users explain these files to
 * themselves, and stripping them would help no one. Note that only
 * whole-line comments are recognised: this is why `PS1='%# '` survives
 * when a naive "strip everything after #" rewrite would have eaten the
 * prompt's own `%#`.
 */
object DotfileAdapter {

    /**
     * Rewrites [lines] — the content of [fileName], one string per line —
     * for the Ubuntu side.
     *
     * [fileName] is carried for symmetry with the caller, which knows the
     * file it read; no rule here is per-name yet, but the seam is where
     * `.inputrc` (readline, not shell) would eventually diverge.
     *
     * A multi-line construct (`if … fi`, `case … esac`, `while … done`, a
     * function, a heredoc) is dropped as a unit when any line inside it is
     * Termux-specific, because a half-emptied block is a syntax error, not a
     * rewrite: `if [ -d "$PREFIX" ]; then` and its `fi` must go together,
     * and an `else` branch that survived would at best run in a construct
     * whose condition was dropped.
     */
    fun adapt(fileName: String, lines: List<String>): DotfileResult {
        val dropped = BooleanArray(lines.size)
        val open = mutableListOf<Frame>()
        var heredocTag: String? = null

        for (index in lines.indices) {
            val raw = lines[index]

            // Heredoc bodies are inert data, not shell — never comment-checked,
            // because `# anything` inside a heredoc is content. They are still
            // scanned for markers, so a body carrying $PREFIX drags the
            // redirection with it.
            val tag = heredocTag
            if (tag != null) {
                if (raw.trim() == tag) {
                    heredocTag = null
                    // The tag closes the redirection: pop it here, since the
                    // keyword-matching closer below never sees the tag line.
                    // `open` cannot be empty — the redirection pushed the
                    // frame — but a migration that throws helps nobody.
                    if (open.isNotEmpty()) {
                        val frame = open.removeAt(open.lastIndex)
                        if (frame.tainted) {
                            sweep(dropped, frame.openerLine, index)
                            open.lastOrNull()?.tainted = true
                        }
                    }
                } else if (hasTermuxMarker(raw.trim())) {
                    // `open` cannot be empty here — the redirection pushed the
                    // frame — but a migration that throws helps nobody.
                    open.lastOrNull()?.tainted = true
                }
                continue
            }

            val code = raw.trim()
            if (code.isEmpty() || code.startsWith("#")) continue

            val marker = hasTermuxMarker(code)
            val enclosing = open.lastOrNull()

            // Marked first, then closing: a terminator line can carry trailing
            // commands (`fi; pkg install x`), and those must not escape the
            // sweep just because the line starts with `fi`.
            if (marker) {
                dropped[index] = true
                enclosing?.tainted = true
            }

            val closer = closerFor(code)
            if (enclosing != null && closer == enclosing.construct) {
                open.removeAt(open.lastIndex)
                if (enclosing.tainted) {
                    sweep(dropped, enclosing.openerLine, index)
                    // An emptied block is a syntax error, so the taint travels
                    // outward: whatever encloses this construct goes with it.
                    open.lastOrNull()?.tainted = true
                }
                continue
            }

            // A line that opens a construct (unless it also closes it on the
            // same line — `if x; then y; fi` is one physical line of shell
            // and needs no bookkeeping).
            val opener = openerFor(code)
            if (opener != null && !isSelfContained(code, opener)) {
                open += Frame(opener, index, tainted = marker)
            }

            val heredoc = HEREDOC.find(code)
            if (heredoc != null) {
                open += Frame(Construct.HEREDOC, index, tainted = marker)
                heredocTag = heredoc.groupValues[2]
            }
        }

        // A construct that never closed was malformed shell already. A tainted
        // one takes the rest of the file with it; an untainted one is left
        // exactly as the line-wise rules decided.
        for (frame in open) {
            if (frame.tainted) sweep(dropped, frame.openerLine, lines.lastIndex)
        }

        val kept = ArrayList<String>(lines.size)
        var droppedCount = 0
        for (index in lines.indices) {
            if (dropped[index]) droppedCount++ else kept += lines[index]
        }
        return DotfileResult(rewrittenLines = kept, droppedLineCount = droppedCount)
    }

    /**
     * The output of an [adapt] pass.
     *
     * @param rewrittenLines the lines to write, in order, unchanged.
     * @param droppedLineCount how many input lines were Termux-specific. The
     *   caller is *meant* to divide this by (`size` + [droppedLineCount]) —
     *   a dotfile reduced to a fraction of itself is better preserved
     *   verbatim in `~/imported-from-termux/` than installed as a rewrite,
     *   and this is the number that says which happened.
     */
    data class DotfileResult(
        val rewrittenLines: List<String>,
        val droppedLineCount: Int,
    )

    /**
     * A shell construct opened earlier in the file and still on the stack.
     *
     * @property openerLine first line of the construct, so a sweep starts at
     *   the `if`, not at the line that tainted it.
     * @property tainted set the moment any line inside the construct turns
     *   out to be Termux-specific — including heredoc *bodies*, which are
     *   inert but still carry the user's intent.
     */
    private class Frame(
        val construct: Construct,
        val openerLine: Int,
        var tainted: Boolean,
    )

    /** The multi-line constructs we know how to keep or drop as a unit. */
    private enum class Construct {
        /** `if … fi` (and every `elif`/`else` inside it — one block). */
        IF,

        /** `for`/`while`/`until`/`select … done`. */
        LOOP,

        /** `case … esac`. */
        CASE,

        /** `name() { … }`. */
        FUNCTION,

        /** `<<TAG … TAG` — closed by the tag line, not by a keyword. */
        HEREDOC,
    }

    /**
     * True when a line of shell is Termux-specific and must not travel.
     *
     * The rules, and why each exists:
     *
     * 1. `$PREFIX` / `${PREFIX}` — the Termux prefix variable. It points at
     *    `/data/data/com.termux/files/usr`, which does not exist in the
     *    guest, so every use of it is broken beyond repair.
     * 2. `PREFIX=…` — the definition itself (however spelled: bare,
     *    `export`, `declare`, `local`). A line that assigns the Android path
     *    is the same information as a line that reads it.
     * 3. `TERMUX_*` variables — `export TERMUX_STORAGE=…`,
     *    `${TERMUX_VERSION}`, `export TERM=xterm` is *not* one of these:
     *    matching is word-anchored on `TERMUX_`, so the ordinary `TERM`
     *    every tty needs is kept.
     * 4. any `.termux` path (`/data/data/com.termux/…`, `~/.termux`) —
     *    covers the app's private directories and properties.
     * 5. `LD_LIBRARY_PATH` — bionic's library search path. Under glibc it
     *    is at best unnecessary and at worst points the loader at binaries
     *    built for a different libc.
     * 6. the word `pkg` as a command — Termux's apt front-end. Matched with
     *    word boundaries on both sides, so the real Ubuntu tool
     *    `pkg-config` is *not* caught.
     * 7. `termux-*` tool names — `termux-open-url`, `termux-setup-storage`,
     *    `termux-wake-lock`: bridges to the Android side.
     */
    internal fun hasTermuxMarker(code: String): Boolean =
        code.contains("\$PREFIX") ||
            code.contains("\${PREFIX}") ||
            code.contains("\$TERMUX_") ||
            code.contains("\${TERMUX_") ||
            TERMUX_VAR_ASSIGNED.containsMatchIn(code) ||
            PREFIX_ASSIGNED.containsMatchIn(code) ||
            code.contains(".termux") ||
            code.contains("LD_LIBRARY_PATH") ||
            PKG_WORD.containsMatchIn(code) ||
            TERMUX_TOOL.containsMatchIn(code)

    /** Termux's apt wrapper, as a command word — `pkg-config` excluded. */
    private val PKG_WORD = Regex("""(?<![\w-])pkg(?![\w-])""")

    /** The `termux-*` tool names and anything they own. */
    private val TERMUX_TOOL = Regex("""(?<![\w-])termux-[\w-]+""")

    /** `export TERMUX_STORAGE=…`, `declare TERMUX_X=…`, bare `TERMUX_X=…`. */
    private val TERMUX_VAR_ASSIGNED = Regex("""(?<![\w-])TERMUX_[A-Za-z0-9_]+\s*=""")

    /** The prefix definition in any of its shell spellings. */
    private val PREFIX_ASSIGNED = Regex(
        """(?<![\w-])(?:export\s+|declare\s+|typeset\s+|readonly\s+|local\s+)?PREFIX\s*=""",
    )

    /** `<<EOF`, `<<- 'EOF'`, `<< EOF` — the quote, when present, must match. */
    private val HEREDOC = Regex("""<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1""")

    /** `name() {` and `function name {`, with or without the parameter list. */
    private val FUNCTION_LINE = Regex(
        """^(?:function\s+[\w.-]+\s*(?:\(\))?|[\w.-]+\s*\(\))\s*(?:\{|$)""",
    )

    /** The separators that end one command and start another. */
    private val COMMAND_SEPARATOR = Regex("""[;&|()]+""")

    /** The first word of a command segment — what the shell will run. */
    private val LEADING_WORD = Regex("""^[A-Za-z_][A-Za-z0-9_-]*""")

    /** Which construct, if any, does [code] close. */
    private fun closerFor(code: String): Construct? = when {
        isTerminator(code, "fi") -> Construct.IF
        isTerminator(code, "done") -> Construct.LOOP
        isTerminator(code, "esac") -> Construct.CASE
        code.startsWith("}") -> Construct.FUNCTION
        else -> null
    }

    /**
     * True when [code] *starts with* [token] as a whole word.
     *
     * Whole word matters: `donex` is a command name, `done_x` is not the
     * keyword either, while `done < file`, `done;` and `done)` all are.
     */
    private fun isTerminator(code: String, token: String): Boolean {
        if (!code.startsWith(token)) return false
        if (code.length == token.length) return true
        val next = code[token.length]
        return !(next.isLetterOrDigit() || next == '_' || next == '-')
    }

    /**
     * Which construct, if any, does [code] open.
     *
     * Command words are the first word of each `;`/`&&`/`|`-separated
     * segment, which is where the shell looks for keywords — so keywords
     * appearing inside quotes (an alias body, a command substitution) are
     * not mistaken for control flow.
     */
    private fun openerFor(code: String): Construct? {
        for (word in commandWords(code)) {
            when (word) {
                "if" -> return Construct.IF
                "for", "while", "until", "select" -> return Construct.LOOP
                "case" -> return Construct.CASE
            }
        }
        if (FUNCTION_LINE.matches(code)) return Construct.FUNCTION
        return null
    }

    /**
     * True when a construct opens *and* closes on this one line, so pushing
     * a frame for it would never pop and would swallow the rest of the file.
     *
     * `if x; then export PATH=$PREFIX/bin; fi` is a single decision the
     * line-wise rules already got right.
     */
    private fun isSelfContained(code: String, construct: Construct): Boolean {
        if (construct == Construct.FUNCTION) return code.contains("}")
        val closer = when (construct) {
            Construct.IF -> "fi"
            Construct.LOOP -> "done"
            Construct.CASE -> "esac"
            Construct.HEREDOC, Construct.FUNCTION -> return false
        }
        return commandWords(code).contains(closer)
    }

    /** The first word of every command segment on the line. */
    private fun commandWords(code: String): List<String> {
        val words = mutableListOf<String>()
        for (segment in code.split(COMMAND_SEPARATOR)) {
            val word = LEADING_WORD.find(segment.trim()) ?: continue
            words += word.value
        }
        return words
    }

    /** Marks [from]..[to] inclusive as dropped. */
    private fun sweep(dropped: BooleanArray, from: Int, to: Int) {
        for (index in from..to) dropped[index] = true
    }
}
