package dev.drosh.agent.provider

import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates a reasoning-effort choice into the fields its protocol expects.
 *
 * Four protocols cover 222 of the catalog's 225 providers, and every one spells
 * effort differently:
 *
 * | protocol | wire |
 * |---|---|
 * | OpenAI-compatible | `reasoning_effort: "high"` |
 * | OpenRouter | `reasoning: { effort: "high" }` |
 * | OpenAI Responses | `reasoning: { effort, summary: "auto" }` |
 * | Anthropic | `thinking: { type: "adaptive" }` + `output_config: { effort }` |
 * | Gemini | `generationConfig.thinkingConfig.thinkingLevel` |
 *
 * Inspired by: github.com/anomalyco/opencode — packages/opencode/src/provider/transform.ts
 * `reasoningVariants()` (line 1691) and `reasoningEffort()` (line 1775).
 *
 * ## Why OpenRouter is a branch of its own
 *
 * It shares `OPENAI_COMPAT` with every other gateway and still wants a different
 * body: `reasoning_effort` is rejected, `reasoning.effort` is not. OpenCode
 * splits them at the npm package for the same reason, which is why [LlmProvider.npm]
 * is carried alongside the kind — the kind alone cannot tell them apart.
 *
 * ## Where the list of values comes from
 *
 * The catalog, not this file. Each model declares
 * `reasoning_options: [{ type: "effort", values: [...] }]`, and that list is
 * per-model and maintained. This file only removes values a protocol cannot
 * express, which is a different question.
 *
 * OpenCode additionally maintains a hand-written GPT-5 effort-tier table
 * (`openaiCompatibleReasoningEfforts`, transform.ts line 646) keyed off model
 * name and release date. It is deliberately not ported: the catalog's per-model
 * declaration is fresher, already encodes those tiers, and Drosh does not carry
 * the release dates the table needs. Re-deriving it here would be a guess.
 */
@Singleton
class EffortMapper @Inject constructor() {

    /**
     * Values the user may pick for [modelId], in catalog order.
     *
     * @param declared the model's catalog list; the source of truth for order and content
     * @return the subset [provider]'s protocol can actually send
     */
    fun allowedEfforts(
        provider: LlmProvider,
        modelId: String,
        declared: List<String>,
    ): List<String> = when (provider.kind) {
        // Every gateway in this bucket takes the same flat spelling. Whether a
        // given upstream honours all seven values is a different question, and
        // the catalog's per-model list is the answer to it.
        ProviderKind.OPENAI_COMPAT -> declared

        // OpenCode filters `max` out of the Responses union
        // (llm/src/protocols/utils/openai-options.ts line 5). The catalog still
        // advertises it for OpenAI models, so this is a real narrowing.
        //
        // UNTESTED — OpenAI may accept `max` on Responses now; verified only
        // against OpenCode's own filter, not against the live API.
        ProviderKind.OPENAI_RESPONSES -> declared.filterNot { it == EFFORT_MAX }

        // Anthropic ties the permitted set to the thinking mode the model
        // supports, which is what OpenCode's `anthropicAdaptiveEfforts()`
        // encodes. Returns null for models outside every known tier, where the
        // catalog list is left to speak for itself.
        ProviderKind.ANTHROPIC -> {
            val tier = anthropicEffortTier(modelId)
            if (tier == null) declared else declared.filter { it in tier }
        }

        // Gemini 3 introduced `thinkingLevel`; 2.5 still takes the numeric
        // `thinkingBudget` this adapter does not send. Verified from Google's
        // thinking docs, so the 2.5 family gets no selector rather than a field
        // the endpoint ignores.
        ProviderKind.GEMINI -> {
            val tier = geminiThinkingLevels(modelId)
            if (tier == null) emptyList() else declared.filter { it in tier }
        }
    }

    /**
     * Top-level body fields carrying [effort], for the three protocols that put
     * it at the top level.
     *
     * Gemini is absent by design: its effort nests inside `generationConfig`,
     * which the adapter owns. Use [geminiThinkingConfig] there.
     */
    fun bodyFields(
        provider: LlmProvider,
        modelId: String,
        effort: String,
        outputTokenLimit: Int?,
    ): Map<String, JsonElement> = when (provider.kind) {

        ProviderKind.OPENAI_COMPAT ->
            if (provider.npm == NPM_OPENROUTER) {
                mapOf("reasoning" to buildJsonObject { put("effort", effort) })
            } else {
                mapOf("reasoning_effort" to JsonPrimitive(effort))
            }

        // OpenCode also sends `summary: "auto"` and asks for the encrypted
        // reasoning payload, which the next turn needs to replay by id.
        ProviderKind.OPENAI_RESPONSES -> mapOf(
            "reasoning" to buildJsonObject {
                put("effort", effort)
                put("summary", "auto")
            },
            "include" to buildJsonArray { add("reasoning.encrypted_content") },
        )

        ProviderKind.ANTHROPIC -> anthropicFields(provider, modelId, effort, outputTokenLimit)

        ProviderKind.GEMINI -> emptyMap()
    }

    /**
     * Gemini's `thinkingConfig`, or null when the model predates `thinkingLevel`.
     *
     * `includeThoughts` is always sent: without it the thought parts arrive as
     * ordinary text and the UI cannot tell reasoning from an answer.
     */
    fun geminiThinkingConfig(modelId: String, effort: String): JsonObject? {
        if (geminiThinkingLevels(modelId) == null) return null
        return buildJsonObject {
            put("includeThoughts", true)
            put("thinkingLevel", effort)
        }
    }

    // ── Anthropic ──────────────────────────────────────────────────────────

    /**
     * `thinking` plus `output_config`, the two fields Anthropic documents for
     * effort.
     *
     * Verified against platform.claude.com: effort lives at
     * `output_config.effort` and *not* inside `thinking`, with
     * `thinking.type: "adaptive"` alongside it. Opus 4.5 is the exception — it
     * predates adaptive thinking, so it keeps
     * `thinking.type: "enabled"` with a token budget that effort composes with.
     */
    private fun anthropicFields(
        provider: LlmProvider,
        modelId: String,
        effort: String,
        outputTokenLimit: Int?,
    ): Map<String, JsonElement> {
        // Kimi on a generic Anthropic-compatible endpoint still asks for
        // summarized thinking text; OpenCode forces `display` for the family
        // rather than trusting the version regex (transform.ts line 29).
        val summarized = usesModernAdaptiveThinking(modelId) || isKimiFamily(provider, modelId)

        val thinking = if (isOpus45(modelId) && outputTokenLimit != null) {
            buildJsonObject {
                put("type", "enabled")
                // Half the ceiling, capped at 16k. The budget must stay under
                // max_tokens, which is why the limit is a parameter and not a
                // constant here.
                put("budget_tokens", opus45Budget(outputTokenLimit))
            }
        } else {
            buildJsonObject {
                put("type", "adaptive")
                if (summarized) put("display", "summarized")
            }
        }

        // An Opus 4.5 model with no declared ceiling cannot be given a valid
        // budget — the API requires budget_tokens >= 1024 and < max_tokens, and
        // inventing either number would be a guess. Effort alone at least does
        // what it says.
        //
        // UNTESTED — `output_config.effort` without `thinking` on an
        // extended-thinking-only model.
        val withThinking = outputTokenLimit != null || !isOpus45(modelId)
        return buildMap {
            if (withThinking) put("thinking", thinking)
            put("output_config", buildJsonObject { put("effort", effort) })
        }
    }

    /**
     * Half the output ceiling, capped at 16k.
     *
     * Ported from `anthropicOpus45Effort()` (transform.ts line 1853).
     */
    private fun opus45Budget(outputTokenLimit: Int): Int =
        minOf(OPUS45_BUDGET_CAP, outputTokenLimit / 2 - 1)

    /**
     * The effort values Anthropic accepts for this model, or null when the
     * model matches no known tier and the catalog list stands.
     *
     * Ported from `anthropicAdaptiveEfforts()` (transform.ts line 669), which
     * is where OpenCode gets the same information.
     */
    private fun anthropicEffortTier(modelId: String): List<String>? {
        val id = modelId.lowercase()
        if (usesModernAdaptiveThinking(id)) return MODERN_ANTHROPIC_EFFORTS
        if (listOf("opus-4-6", "opus-4.6", "4-6-opus", "4.6-opus", "sonnet-4-6", "sonnet-4.6", "4-6-sonnet", "4.6-sonnet")
                .any { id.contains(it) }
        ) {
            return ANTHROPIC_46_EFFORTS
        }
        return null
    }

    /**
     * True for Claude 4.7 and later, which use adaptive thinking.
     *
     * Ported from `anthropicUsesModernAdaptiveThinking()` (transform.ts line
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

    /** Opus 4.5 is the last Anthropic generation without adaptive thinking. */
    private fun isOpus45(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("opus-4-5") || id.contains("opus-4.5")
    }

    /**
     * True when the model is a Kimi/Moonshot model reached over an
     * Anthropic-shaped endpoint — several gateways list one and Moonshot wants
     * its thinking text summarized.
     *
     * Ported from `isKimiFamily()` (transform.ts line 29).
     */
    private fun isKimiFamily(provider: LlmProvider, modelId: String): Boolean {
        val ids = listOf(provider.id, modelId).map { it.lowercase() }
        if (ids.any { it.contains("kimi") || it.contains("moonshot") }) return true
        val host = provider.baseUrl.lowercase()
        return KIMI_HOSTS.any { host.contains(it) }
    }

    // ── Gemini ─────────────────────────────────────────────────────────────

    /**
     * `thinkingLevel` values this model accepts, or null when it predates the
     * parameter.
     *
     * Verified from Google's Gemini thinking docs: `thinkingLevel` replaced the
     * numeric `thinkingBudget` with the Gemini 3 line, and the accepted subset
     * varies by model — notably `minimal` is rejected by Gemini 3.1 Pro and by
     * 3.8 Flash. Gemini 2.5 returns null here because it wants a budget, which
     * this adapter does not send.
     */
    private fun geminiThinkingLevels(modelId: String): List<String>? {
        val id = modelId.lowercase()
        if (id.contains("gemini-2.5")) return null
        return when {
            id.contains("gemini-3.1-pro") -> listOf("low", "medium", "high")
            id.contains("gemini-3-pro") -> listOf("low", "high")
            id.contains("gemini-3.8-flash") || id.contains("gemini-3.7-flash") ->
                listOf("low", "medium", "high")
            id.contains("gemini-3.1-flash-lite-image") -> listOf("minimal", "high")
            else -> ALL_GEMINI_LEVELS
        }
    }

    private companion object {
        const val NPM_OPENROUTER = "@openrouter/ai-sdk-provider"
        const val EFFORT_MAX = "max"

        /** Ported from `OUTPUT_TOKEN_MAX` (transform.ts line 18). */
        const val OPUS45_BUDGET_CAP = 16_000

        /** `anthropicAdaptiveEfforts()` for Claude 4.7+. */
        val MODERN_ANTHROPIC_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        /** The same, for the 4.6 generation. */
        val ANTHROPIC_46_EFFORTS = listOf("low", "medium", "high", "max")

        val ALL_GEMINI_LEVELS = listOf("minimal", "low", "medium", "high")

        val KIMI_HOSTS = listOf("api.kimi.com", "api.moonshot.ai", "api.moonshot.cn", "api.moonshotai.cn")

        /** Family- or version-first, minor capped at two digits. */
        val CLAUDE_VERSION_RE = Regex("""claude-(?:[a-z]+-)?(\d+)(?:[.-](\d{1,2}))?(?:[.@-]|$)""")
    }
}
