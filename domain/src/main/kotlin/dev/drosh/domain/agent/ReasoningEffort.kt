package dev.drosh.domain.agent

/**
 * Which reasoning-effort values a model may actually be run at.
 *
 * The catalog declares what a model offers; this answers what the protocol can
 * send. Those are different questions and conflating them is how a picker ends up
 * offering `minimal` to a Gemini model that rejects it.
 *
 * ## Why this is in `:domain`
 *
 * The settings screen asks this question to build the selector, and the settings
 * screen only sees `:domain`. Putting it in `:agent` would either duplicate the
 * tiers or push an agent dependency into the data layer, and both are worse than
 * a pure function with no dependencies of its own.
 */
object ReasoningEffort {

    /**
     * The subset of [declared] that [provider]'s protocol can express for
     * [modelId], in the order the catalog declared them.
     *
     * @param declared the model's catalog list — the source of truth for both
     *        content and order
     */
    fun allowed(
        provider: LlmProvider,
        modelId: String,
        declared: List<String>,
    ): List<String> = when (provider.kind) {

        // Every gateway in this bucket takes the same flat spelling. Whether a
        // given upstream honours all seven values is a different question, and
        // the catalog's per-model list is the answer to it.
        ProviderKind.OPENAI_COMPAT -> declared

        // OpenCode's own union for this protocol excludes `max`
        // (llm/src/protocols/utils/openai-options.ts, line 5). The catalog still
        // advertises it for OpenAI models, so this is a real narrowing.
        //
        // UNTESTED — OpenAI may now accept `max` on Responses; verified only
        // against OpenCode's filter, not against the live API.
        ProviderKind.OPENAI_RESPONSES -> declared.filterNot { it == EFFORT_MAX }

        // Anthropic ties the permitted set to the thinking mode the model
        // supports. Null means the model matches no known tier and the catalog
        // list stands on its own.
        ProviderKind.ANTHROPIC -> anthropicTier(modelId)?.let { tier ->
            declared.filter { it in tier }
        } ?: declared

        // Gemini 3 introduced `thinkingLevel`; 2.5 still takes the numeric
        // `thinkingBudget`, which Drosh does not send. Verified from Google's
        // thinking docs, so 2.5 gets no selector at all rather than a field the
        // endpoint would ignore.
        ProviderKind.GEMINI -> geminiLevels(modelId)?.let { levels ->
            declared.filter { it in levels }
        } ?: emptyList()
    }

    /**
     * The effort values Anthropic accepts for this model, or null when it matches
     * no known tier.
     *
     * Ported from `anthropicAdaptiveEfforts()` in OpenCode's
     * packages/opencode/src/provider/transform.ts (line 669), which is where
     * OpenCode gets the same information.
     */
    private fun anthropicTier(modelId: String): List<String>? {
        val id = modelId.lowercase()
        if (usesModernAdaptiveThinking(id)) return MODERN_ANTHROPIC_EFFORTS
        if (ANTHROPIC_46.any { id.contains(it) }) return ANTHROPIC_46_EFFORTS
        return null
    }

    /**
     * True for Claude 4.7 and later, which use adaptive thinking.
     *
     * Ported from `anthropicUsesModernAdaptiveThinking()` (transform.ts, line
     * 654). Family-first (`claude-opus-4.7`) and version-first
     * (`claude-4.7-opus`) ids both match; a dated id such as
     * `claude-opus-4-20250514` does not, because minors are limited to two
     * digits.
     */
    private fun usesModernAdaptiveThinking(modelId: String): Boolean {
        val id = modelId.lowercase()
        if (!id.contains("claude-")) return false
        val match = CLAUDE_VERSION_RE.find(id) ?: return true
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major > 4 || (major == 4 && minor >= 7)
    }

    /**
     * The `thinkingLevel` values this model accepts, or null when it predates the
     * parameter.
     *
     * Verified from Google's Gemini thinking docs: `thinkingLevel` replaced the
     * numeric `thinkingBudget` with the Gemini 3 line, and the accepted subset
     * varies by model — notably `minimal` is rejected by Gemini 3.1 Pro and by
     * 3.8 Flash. Gemini 2.5 returns null because it wants a budget instead.
     */
    private fun geminiLevels(modelId: String): List<String>? {
        val id = modelId.lowercase()
        if (id.contains("gemini-2.5")) return null
        return when {
            id.contains("gemini-3.1-pro") -> listOf("low", "medium", "high")
            id.contains("gemini-3-pro") -> listOf("low", "high")
            // Both reject `minimal`, which they would otherwise inherit.
            id.contains("gemini-3.8-flash") || id.contains("gemini-3.7-flash") ->
                listOf("low", "medium", "high")

            id.contains("gemini-3.1-flash-lite-image") -> listOf("minimal", "high")
            else -> ALL_GEMINI_LEVELS
        }
    }

    private const val EFFORT_MAX = "max"

    private val MODERN_ANTHROPIC_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

    private val ANTHROPIC_46_EFFORTS = listOf("low", "medium", "high", "max")

    private val ANTHROPIC_46 = listOf(
        "opus-4-6", "opus-4.6", "4-6-opus", "4.6-opus",
        "sonnet-4-6", "sonnet-4.6", "4-6-sonnet", "4.6-sonnet",
    )

    private val ALL_GEMINI_LEVELS = listOf("minimal", "low", "medium", "high")

    /** Family- or version-first, minor capped at two digits. */
    private val CLAUDE_VERSION_RE = Regex("""claude-(?:[a-z]+-)?(\d+)(?:[.-](\d{1,2}))?(?:[.@-]|$)""")
}
