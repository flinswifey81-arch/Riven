package com.shai.riven.data.context

data class ConversationalContextAssemblyInput(
    val now: Long,
    val conversation: RivenConversationContextRequest,
    val budget: RivenContextCollectionBudget = DEFAULT_CONVERSATIONAL_CONTEXT_BUDGET,
)

val DEFAULT_CONVERSATIONAL_CONTEXT_BUDGET = RivenContextCollectionBudget(
    maxFragments = 48,
    maxAggregateChars = 32_768,
)

class ConversationalContextAssembler(
    private val registry: RivenContextSourceRegistry,
) {
    suspend fun assemble(input: ConversationalContextAssemblyInput): RivenContextCollectionResult =
        registry.collect(
            RivenContextReadRequest(
                now = input.now,
                conversation = input.conversation,
                budget = input.budget,
            ),
        )
}
