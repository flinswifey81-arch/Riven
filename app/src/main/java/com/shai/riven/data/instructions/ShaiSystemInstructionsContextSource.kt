package com.shai.riven.data.instructions

import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextPayload
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSource
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceDescriptor
import com.shai.riven.data.context.RivenContextSourceError
import com.shai.riven.data.context.RivenContextSourceResult

class ShaiSystemInstructionsContextSource(
    private val snapshotReader: suspend () -> ShaiSystemInstructionsReadResult,
) : RivenContextSource {
    constructor(service: ShaiSystemInstructionsService) : this(service::snapshot)

    override val descriptor = RivenContextSourceDescriptor(
        sourceId = SOURCE_ID,
        layer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
        provenanceClass = RivenContextProvenanceClass.SHAI_CONFIGURATION,
        criticality = RivenContextSourceCriticality.REQUIRED,
        orderWithinLayer = 0,
        maxFragments = 1,
        maxCharsPerFragment = MAX_CONTEXT_CHARS,
        maxAggregateChars = MAX_CONTEXT_CHARS,
        budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
    )

    override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult =
        when (val result = snapshotReader()) {
            is ShaiSystemInstructionsReadResult.Failure ->
                RivenContextSourceResult.Failure(result.error.toContextSourceError())

            is ShaiSystemInstructionsReadResult.Success -> {
                val snapshot = result.snapshot
                RivenContextSourceResult.Success(
                    if (!snapshot.isEnabled || snapshot.content.isBlank()) {
                        emptyList()
                    } else {
                        listOf(
                            RivenContextPayload(
                                fragmentId = PRIMARY_FRAGMENT_ID,
                                content = snapshot.content,
                                revision = snapshot.revision,
                                observedAt = snapshot.updatedAt,
                            ),
                        )
                    },
                )
            }
        }

    suspend fun currentFragment(): ShaiSystemInstructionsContextResult = when (val result = snapshotReader()) {
        is ShaiSystemInstructionsReadResult.Failure ->
            ShaiSystemInstructionsContextResult.Failure(result.error)

        is ShaiSystemInstructionsReadResult.Success -> {
            val snapshot = result.snapshot
            if (!snapshot.isEnabled || snapshot.content.isBlank()) {
                ShaiSystemInstructionsContextResult.NoFragment
            } else {
                ShaiSystemInstructionsContextResult.Fragment(
                    ShaiSystemInstructionsContextFragment(
                        layer = RivenContextLayer.SHAI_SYSTEM_INSTRUCTIONS,
                        content = snapshot.content,
                        revision = snapshot.revision,
                    ),
                )
            }
        }
    }

    private fun ShaiSystemInstructionsError.toContextSourceError() =
        RivenContextSourceError.ReadFailure(
            errorType = this::class.java.simpleName,
            causeType = (this as? ShaiSystemInstructionsError.StorageFailure)?.causeType,
        )

    companion object {
        const val SOURCE_ID = "SHAI_SYSTEM_INSTRUCTIONS"
        const val PRIMARY_FRAGMENT_ID = "PRIMARY"
        const val MAX_CONTEXT_CHARS = 262_144
    }
}
