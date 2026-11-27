package dev.drosh.ui.block

/** How many suggestions the strip offers at once. */
internal const val PREDICTION_LIMIT = 3

/** Below this many typed characters a prefix matches half the history. */
private const val PREDICTION_MIN_CHARS = 2

/**
 * The commands this session already ran that start with what is being typed.
 *
 * ## What a prediction is, and is not
 *
 * It is a *whole line* taken from this session's own history. Nothing is
 * invented, no model is consulted, and nothing leaves the device: a suggestion
 * the user cannot trace back to a command they ran would be a guess dressed up
 * as a memory.
 *
 * The match is a case-insensitive prefix on the trimmed line, so it behaves the
 * way a shell's own history search does. A single character is not a prediction
 * — it would offer everything — and the line being typed is never suggested back
 * to the user.
 *
 * [history] must be most-recent-first; the first suggestion is then the most
 * recent command that fits, which is the one a user is most likely to repeat.
 */
internal fun predictCommands(
    line: String,
    history: List<String>,
    limit: Int = PREDICTION_LIMIT,
): List<String> {
    if (limit <= 0) return emptyList()
    val typed = line.trimStart()
    if (typed.length < PREDICTION_MIN_CHARS || typed.contains('\n')) return emptyList()

    val seen = HashSet<String>()
    return history.asSequence()
        .map { it.trim() }
        .filter { candidate ->
            candidate.length > typed.length &&
                candidate.startsWith(typed, ignoreCase = true) &&
                seen.add(candidate.lowercase())
        }
        .take(limit)
        .toList()
}
