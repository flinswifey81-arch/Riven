package com.shai.riven.data.provider.openrouter

import com.shai.riven.data.context.RivenContextCollectionBudget
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts OpenRouter's model context metadata into a conservative character budget.
 * Unknown manually-entered models use an explicit 16K-token fallback; exact locked canon and the
 * current user turn are never truncated by the downstream collector.
 */
class OpenRouterContextBudgetPolicy {
    fun record(models: Collection<OpenRouterModel>) {
        models.forEach { model ->
            model.contextLength?.takeIf { it > 0 }?.let { contextTokensByModel[model.id] = it }
        }
    }

    fun budgetFor(modelId: String): RivenContextCollectionBudget {
        val contextTokens = contextTokensByModel[modelId] ?: UNKNOWN_MODEL_CONTEXT_TOKENS
        val inputTokens = (contextTokens.toLong() - RESERVED_COMPLETION_TOKENS - RESERVED_PROTOCOL_TOKENS)
            .coerceAtLeast(1L)
        val aggregateChars = (inputTokens * CONSERVATIVE_CHARS_PER_TOKEN)
            .coerceAtMost(MAX_INPUT_CHARS.toLong())
            .toInt()
        return RivenContextCollectionBudget(
            maxFragments = MAX_INPUT_FRAGMENTS,
            maxAggregateChars = aggregateChars,
        )
    }

    companion object {
        private val contextTokensByModel = ConcurrentHashMap<String, Int>()

        const val UNKNOWN_MODEL_CONTEXT_TOKENS = 16_384
        const val RESERVED_COMPLETION_TOKENS = OpenRouterConversationAdapter.DEFAULT_MAX_COMPLETION_TOKENS
        const val RESERVED_PROTOCOL_TOKENS = 512
        const val CONSERVATIVE_CHARS_PER_TOKEN = 3
        const val MAX_INPUT_CHARS = 262_144
        const val MAX_INPUT_FRAGMENTS = 48
    }
}
