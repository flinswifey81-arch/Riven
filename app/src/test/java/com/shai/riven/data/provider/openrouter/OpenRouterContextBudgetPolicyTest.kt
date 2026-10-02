package com.shai.riven.data.provider.openrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterContextBudgetPolicyTest {
    @Test
    fun usesExplicitConservativeFallbackThenRecordedModelMetadata() {
        val policy = OpenRouterContextBudgetPolicy()
        val fallback = policy.budgetFor("manual/unknown")

        assertEquals(
            (OpenRouterContextBudgetPolicy.UNKNOWN_MODEL_CONTEXT_TOKENS -
                OpenRouterContextBudgetPolicy.RESERVED_COMPLETION_TOKENS -
                OpenRouterContextBudgetPolicy.RESERVED_PROTOCOL_TOKENS) *
                OpenRouterContextBudgetPolicy.CONSERVATIVE_CHARS_PER_TOKEN,
            fallback.maxAggregateChars,
        )

        policy.record(listOf(OpenRouterModel("tiny/model", "Tiny", contextLength = 4_096)))
        val known = policy.budgetFor("tiny/model")

        assertTrue(known.maxAggregateChars < fallback.maxAggregateChars)
        assertEquals(4_608, known.maxAggregateChars)
    }
}
