package dev.drosh.domain.block

/**
 * Command-boundary detector for the block engine.
 *
 * Layered detection strategy (per `docs/block-engine/PLAN.md` §7):
 *   1. **OSC 133**  — primary, requires the shell prompt to emit
 *      `ESC ] 133 ; A/B/C ST` markers. v2 feature.
 *   2. **ANSI cursor tracking** — secondary. v2 feature.
 *   3. **Prompt regex** — fallback, always available. Multi-line aware:
 *      handles 1-line prompts (`user@host:path$`), 2-line prompts
 *      (`user@host:path` on row N-1 + `$` on row N), spaceship/oh-my-zsh
 *      style with `❯` / `➜` markers, and bare single-character markers
 *      with trailing whitespace.
 *
 * Hardcoded for v1 — v2 will add automatic capture from the running
 * shell (mount-time sentinel echo + parse).
 */
class CommandBoundaryDetector {

    /**
     * Scan the provided lines (top-to-bottom order) for a prompt
     * marker. Returns the most recent match — the one that closes the
     * currently-running block.
     */
    fun detectPromptReady(lines: List<String>): CommandBoundary {
        if (lines.isEmpty()) return CommandBoundary.None
        val lastIdx = lines.lastIndex
        val last = lines[lastIdx].trimEnd()

        // Single-line prompt: last line alone ends with a marker.
        if (isPromptLine(last)) {
            return CommandBoundary.PromptReady(atLine = lastIdx)
        }

        // Two-line prompt: second-to-last line is user@host info, last
        // line is bare marker (e.g. `muhofy@drosh:~/Drosh` +
        // `$ `).
        if (lastIdx >= 1) {
            val prev = lines[lastIdx - 1].trimEnd()
            if (isUserHostContext(prev) && isBareMarker(last)) {
                return CommandBoundary.PromptReady(atLine = lastIdx)
            }
        }

        return CommandBoundary.None
    }

    private fun isPromptLine(line: String): Boolean {
        if (line.isEmpty()) return false
        val tail = line.last()
        if (tail !in PROMPT_TERMINATORS) return false

        // Bare single-char marker must have trailing whitespace or be a
        // 1-3 char line (e.g. `❯ `, `$ `, `> `).
        if (isBareMarker(line)) return true

        // user@host:path patterns.
        if ('@' in line && ':' in line) {
            val atIdx = line.indexOf('@')
            val colonIdx = line.indexOf(':', atIdx)
            if (colonIdx > atIdx) return true
        }

        // Spaceship / oh-my-zsh with status: `~/path git:(main) ❯`.
        if (line.endsWith("❯") || line.endsWith("➜") ||
            line.endsWith("✗") || line.endsWith("✓")
        ) return true

        return false
    }

    private fun isUserHostContext(line: String): Boolean {
        if (line.isEmpty()) return false
        return '@' in line && (':' in line || ' ' in line) && line.length <= MAX_USERHOST_LEN
    }

    private fun isBareMarker(line: String): Boolean {
        if (line.isEmpty()) return false
        val tail = line.last()
        if (tail !in BARE_MARKERS) return false
        // Must be short — a 1-3 char line consisting mostly of the marker.
        return line.length <= 4 && line.all { it == tail || it.isWhitespace() }
    }

    private companion object {
        val PROMPT_TERMINATORS = setOf('$', '#', '%', '❯', '➜', '>', '✗', '✓')
        val BARE_MARKERS = setOf('$', '#', '%', '❯', '➜', '>')
        const val MAX_USERHOST_LEN = 80
    }
}
