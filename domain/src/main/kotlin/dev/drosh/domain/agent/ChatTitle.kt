package dev.drosh.domain.agent

/**
 * How a conversation gets its name.
 *
 * ## The prompt itself is never touched
 *
 * The first user message goes to the model exactly as typed. Nothing in this
 * file — or anywhere else in the naming path — rewrites, truncates or summarises
 * it on the way to a request. A model asked to name a conversation while also
 * being told a shorter version of what the user actually said is a model
 * working from a paraphrase, and paraphrasing is where "why is my screenshot
 * not being sent" comes from.
 *
 * The name is therefore a *second* request, about the first one, made after the
 * answer has started coming back.
 *
 * ## When there is nothing to name
 *
 * [isWorthNaming] refuses the short cases. "hi" and "thanks" become titles
 * verbatim or not at all, and a chat list full of "Sure, I can help with that"
 * is worse than a chat list of honest first words. Below the threshold the old
 * behaviour stands: the chat keeps the name derived from what was typed.
 */
object ChatTitle {

    /** Longest title accepted, in characters. */
    const val MAX_CHARS: Int = 60

    /** Longest title accepted, in words. */
    const val MAX_WORDS: Int = 6

    /**
     * Shortest first message worth asking about.
     *
     * In words rather than characters: a two-word question ("fix the build")
     * is exactly what a title should be derived from, and forty characters of
     * `ok` is still just `ok`.
     */
    const val MIN_WORDS: Int = 4

    /** How much of the first message is sent, so a pasted log is not the prompt. */
    const val MAX_SOURCE_CHARS: Int = 1200

    /**
     * The instruction the naming request carries.
     *
     * Plain, second person, and with the constraints stated rather than
     * demonstrated: a model that is told "five words" produces five words far
     * more reliably than one shown five examples.
     */
    const val SYSTEM_PROMPT: String =
        "You name conversations. Reply with the title only: no quotes, no " +
            "punctuation at the end, no explanation. Use at most 5 words and at " +
            "most 60 characters. Describe what the user is asking for in the " +
            "language they wrote in. If there is nothing to describe, reply " +
            "with nothing at all."

    /** Whether the first message carries enough to be worth a naming call. */
    fun isWorthNaming(userText: String): Boolean =
        userText.trim().split(WHITESPACE).count { it.isNotBlank() } >= MIN_WORDS

    /** The part of the first message the naming request is about. */
    fun sourceFor(userText: String): String =
        userText.trim().take(MAX_SOURCE_CHARS)

    /**
     * A model-authored title, or null when it is not one.
     *
     * Models answer this prompt in ways no instruction anticipated — a
     * `Title:` prefix, a quoted string, a refusal, three blank lines and a
     * word. Everything here is about not putting that in a chat list, and
     * refusing is always an option: a null keeps the derived name, which is
     * what the user would have got anyway.
     */
    fun sanitize(raw: String): String? {
        // The first line only: a model that explains itself after answering
        // would otherwise have the explanation in the chat name.
        val firstLine = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.trim()
            ?: return null

        val unwrapped = firstLine
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix("Title:")
            .removePrefix("title:")
            .trim()
            .trimEnd('.', '!', '?', ':', ',', ';')
            .trim()

        if (unwrapped.length < MIN_TITLE_CHARS) return null
        if (unwrapped.length > MAX_CHARS) return null
        if (unwrapped.split(WHITESPACE).count { it.isNotBlank() } > MAX_WORDS) return null

        // A refusal or an apology is text, and a chat named "I cannot help with
        // that" tells the user nothing they can act on.
        if (REFUSALS.any { unwrapped.contains(it, ignoreCase = true) }) return null

        return unwrapped
    }

    /**
     * The title Drosh used before any model was asked.
     *
     * Kept as a function rather than folded into the call sites: it is the
     * fallback the short-message case keeps, and it is what makes "no title" a
     * non-event instead of a chat with an empty name in the list.
     */
    fun derivedFrom(userText: String): String = userText.trim().take(DERIVED_CHARS)

    /** Below this, the answer is a single character or an emoji. */
    private const val MIN_TITLE_CHARS: Int = 2

    private const val DERIVED_CHARS: Int = 48

    private val WHITESPACE: Regex = Regex("""\s+""")

    private val REFUSALS: List<String> = listOf(
        "i cannot",
        "i can't",
        "i am unable",
        "i'm unable",
        "as an ai",
        "no title",
    )
}