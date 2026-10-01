package com.shai.riven.data.recall

import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextPayload
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSource
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceDescriptor
import com.shai.riven.data.context.RivenContextSourceError
import com.shai.riven.data.context.RivenContextSourceResult

class ConversationalMemoryContextSource(
    private val retriever: ConversationalMemoryRetriever,
) : RivenContextSource {
    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
        provenanceClass = RivenContextProvenanceClass.RETRIEVED_MEMORY,
        criticality = RivenContextSourceCriticality.OPTIONAL,
        orderWithinLayer = 10,
        maxFragments = MAX_FRAGMENTS,
        maxCharsPerFragment = MAX_CHARS_PER_FRAGMENT,
        maxAggregateChars = MAX_AGGREGATE_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
        contentAuthority = RivenContextContentAuthority.UNTRUSTED_DATA,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
        val conversation = request.conversation ?: return failure("MissingConversationRequest")
        val retrieval = retriever.retrieve(
            ConversationalMemoryQuery(
                currentInteraction = conversation.currentInteraction.content,
                now = request.now,
                cues = conversation.recallCues,
            ),
        )
        return when (retrieval.readiness) {
            ConversationalRecallReadiness.READY -> RivenContextSourceResult.Success(
                retrieval.memories.map { memory ->
                    RivenContextPayload(
                        fragmentId = memory.memoryId,
                        content = memory.toContextContent(),
                        revision = retrieval.generation?.corpusGeneration,
                        observedAt = request.now,
                    )
                },
            )
            ConversationalRecallReadiness.QUERY_UNSELECTIVE -> RivenContextSourceResult.Success(emptyList())
            ConversationalRecallReadiness.NOT_READY,
            ConversationalRecallReadiness.STALE,
            ConversationalRecallReadiness.FAILED,
            ConversationalRecallReadiness.CAPACITY_EXCEEDED,
            ConversationalRecallReadiness.BUDGET_EXCEEDED,
            -> failure("ConversationalRecall.${retrieval.readiness.name}")
        }
    }

    private fun ConversationalMemoryItem.toContextContent(): String = buildString {
        append("type=MEMORY\n")
        append("certainty=").append(certainty.name).append('\n')
        append("truth=").append(truthState.name).append('\n')
        append("temporal=").append(temporalState.name).append('\n')
        append("lifecycle=").append(lifecycleState.name).append('\n')
        append("provenance=").append(epistemicBasis.name).append('\n')
        append("meaning=").append(meaning.replace('\u0000', ' '))
    }

    private fun failure(errorType: String) = RivenContextSourceResult.Failure(
        RivenContextSourceError.ReadFailure(errorType),
    )

    companion object {
        const val SOURCE_ID = "CONVERSATIONAL_MEMORY"
        const val MAX_FRAGMENTS = 8
        const val MAX_CHARS_PER_FRAGMENT = 4_608
        const val MAX_AGGREGATE_CHARS = 40_960
    }
}
