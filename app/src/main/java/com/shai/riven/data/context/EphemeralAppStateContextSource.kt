package com.shai.riven.data.context

class EphemeralAppStateContextSource(
    private val store: EphemeralAppStateStore,
) : RivenContextSource {
    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
        provenanceClass = RivenContextProvenanceClass.DYNAMIC_APP_STATE,
        criticality = RivenContextSourceCriticality.OPTIONAL,
        orderWithinLayer = 0,
        maxFragments = MAX_FRAGMENTS,
        maxCharsPerFragment = MAX_CHARS_PER_FRAGMENT,
        maxAggregateChars = MAX_AGGREGATE_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult =
        RivenContextSourceResult.Success(
            store.snapshot().entries
                .asSequence()
                .filter { entry -> entry.exposure == EphemeralAppStateExposure.RIVEN_CONTEXT }
                .filterNot { entry ->
                    entry.validUntil?.let { validUntil -> validUntil <= request.now } == true
                }
                .sortedWith(
                    compareByDescending<EphemeralAppStateEntry>(EphemeralAppStateEntry::priority)
                        .thenBy(EphemeralAppStateEntry::stateId),
                )
                .map { entry ->
                    RivenContextPayload(
                        fragmentId = entry.stateId,
                        content = entry.content,
                        revision = entry.revision,
                        observedAt = entry.observedAt,
                        validUntil = entry.validUntil,
                    )
                }
                .toList(),
        )

    companion object {
        const val SOURCE_ID = "EPHEMERAL_APP_STATE"
        const val MAX_FRAGMENTS = 32
        const val MAX_CHARS_PER_FRAGMENT = 4_096
        const val MAX_AGGREGATE_CHARS = 32_768
    }
}
