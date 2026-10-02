package com.shai.riven.data.context

import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.provider.ProviderCapability

enum class RivenContextLayer {
    APP_INVARIANTS_SAFETY_AND_TOOL_TRUTH,
    LOCKED_RIVEN_PERSONALITY_AND_IDENTITY_CANON,
    SHAI_SYSTEM_INSTRUCTIONS,
    RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
    ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
}

enum class RivenContextProvenanceClass {
    APP_INVARIANT,
    LOCKED_CANON,
    SHAI_CONFIGURATION,
    DYNAMIC_APP_STATE,
    RETRIEVED_MEMORY,
    OPEN_LOOP,
    TOOL_OBSERVATION,
    ACTIVE_CONVERSATION,
    OTHER_GROUNDED,
}

enum class RivenContextSourceCriticality {
    REQUIRED,
    OPTIONAL,
}

enum class RivenContextBudgetBehavior {
    REQUIRED,
    DROP_IF_NEEDED,
    TRUNCATABLE,
}

/**
 * Whether a context fragment is allowed to carry instructions or must remain inert source data.
 * Dynamic state, memories, tools, and conversation text are data even when their text contains
 * instruction-like language.
 */
enum class RivenContextContentAuthority {
    INSTRUCTIONS,
    UNTRUSTED_DATA,
}

data class RivenContextSourceDescriptor(
    val sourceId: String,
    val layer: RivenContextLayer,
    val provenanceClass: RivenContextProvenanceClass,
    val criticality: RivenContextSourceCriticality,
    val orderWithinLayer: Int,
    val maxFragments: Int,
    val maxCharsPerFragment: Int,
    val maxAggregateChars: Int,
    val budgetBehavior: RivenContextBudgetBehavior,
    val contentAuthority: RivenContextContentAuthority = RivenContextContentAuthority.UNTRUSTED_DATA,
)

data class RivenContextReadRequest(
    val now: Long,
    val conversation: RivenConversationContextRequest? = null,
    val budget: RivenContextCollectionBudget? = null,
)

data class RivenContextCollectionBudget(
    val maxFragments: Int,
    val maxAggregateChars: Int,
) {
    init {
        require(maxFragments > 0)
        require(maxAggregateChars > 0)
    }
}

data class RivenCurrentInteraction(
    val messageId: String? = null,
    val content: String,
    val hasAttachments: Boolean = false,
)

/**
 * Explicit, already-grounded recall authorization. These ids must come from canonical application
 * state or an explicit user action; a lexical score must never manufacture one of these cues.
 */
data class RivenGroundedRecallCues(
    val groundedEntityIds: Set<String> = emptySet(),
    val directlyRelevantMemoryIds: Set<String> = emptySet(),
    val historicalMemoryIds: Set<String> = emptySet(),
    val dormantMemoryIds: Set<String> = emptySet(),
    val disputedMemoryIds: Set<String> = emptySet(),
    val timeBoundMemoryIds: Set<String> = emptySet(),
    val sensitiveMemoryIds: Set<String> = emptySet(),
    val activeOpenLoopMemoryIds: Set<String> = emptySet(),
    val directlyRelevantOpenLoopIds: Set<String> = emptySet(),
)

data class RivenConversationContextRequest(
    val conversationId: String,
    val expectedTimelineRevision: Long,
    val currentInteraction: RivenCurrentInteraction,
    val recallCues: RivenGroundedRecallCues = RivenGroundedRecallCues(),
    val contextHeadMessageId: String? = null,
)

data class RivenContextPayload(
    val fragmentId: String,
    val content: String,
    val revision: Long? = null,
    val observedAt: Long? = null,
    val validUntil: Long? = null,
    val conversationRole: MessageRole? = null,
    val budgetBehavior: RivenContextBudgetBehavior? = null,
)

sealed interface RivenContextFreshnessReceipt {
    data class ActiveConversation(
        val conversationId: String,
        val timelineRevision: Long,
    ) : RivenContextFreshnessReceipt

    data class ConversationalRecall(
        val databaseSessionId: String,
        val algorithmVersion: String,
        val corpusGeneration: Long,
    ) : RivenContextFreshnessReceipt

    data class ShaiSystemInstructions(
        val revision: Long,
        val isEnabled: Boolean,
    ) : RivenContextFreshnessReceipt

    data class EphemeralAppState(
        val storeSessionId: String,
        val generation: Long,
        val earliestValidUntil: Long?,
    ) : RivenContextFreshnessReceipt

    data class ProviderProfile(
        val profileId: String,
        val revision: Long,
        val adapterId: String,
        val endpointBaseUrl: String,
        val modelId: String,
        val capabilities: Set<ProviderCapability>,
    ) : RivenContextFreshnessReceipt

    data class RivenPresence(
        val actualRoomId: String,
        val semanticSpriteId: String,
        val presenceRevision: Long,
    ) : RivenContextFreshnessReceipt
}

data class RivenContextFragment(
    val sourceId: String,
    val fragmentId: String,
    val content: String,
    val layer: RivenContextLayer,
    val provenanceClass: RivenContextProvenanceClass,
    val criticality: RivenContextSourceCriticality,
    val orderWithinLayer: Int,
    val orderWithinSource: Int,
    val budgetBehavior: RivenContextBudgetBehavior,
    val revision: Long?,
    val observedAt: Long?,
    val validUntil: Long?,
    val contentAuthority: RivenContextContentAuthority,
    val conversationRole: MessageRole? = null,
)

sealed interface RivenContextSourceError {
    data class ReadFailure(
        val errorType: String,
        val causeType: String? = null,
    ) : RivenContextSourceError

    data class UnexpectedFailure(
        val causeType: String,
    ) : RivenContextSourceError
}

sealed interface RivenContextSourceResult {
    data class Success(
        val payloads: List<RivenContextPayload>,
        val freshnessReceipts: Set<RivenContextFreshnessReceipt> = emptySet(),
    ) : RivenContextSourceResult

    data class Failure(
        val error: RivenContextSourceError,
    ) : RivenContextSourceResult
}

sealed interface RivenContextContractViolation {
    data class MaximumFragmentsExceeded(
        val maximum: Int,
        val actual: Int,
    ) : RivenContextContractViolation

    data class BlankFragmentId(
        val payloadIndex: Int,
    ) : RivenContextContractViolation

    data class DuplicateFragmentId(
        val fragmentId: String,
    ) : RivenContextContractViolation

    data class BlankContent(
        val fragmentId: String,
    ) : RivenContextContractViolation

    data class FragmentTooLarge(
        val fragmentId: String,
        val maximumChars: Int,
        val actualChars: Int,
    ) : RivenContextContractViolation

    data class AggregateTooLarge(
        val maximumChars: Int,
        val actualChars: Long,
    ) : RivenContextContractViolation

    data class InvalidFreshnessWindow(
        val fragmentId: String,
        val observedAt: Long,
        val validUntil: Long,
    ) : RivenContextContractViolation

    data class CollectionBudgetExceeded(
        val maximumFragments: Int,
        val requiredFragments: Int,
        val maximumChars: Int,
        val requiredChars: Long,
    ) : RivenContextContractViolation
}

sealed interface RivenContextFailureCause {
    data class SourceFailure(
        val error: RivenContextSourceError,
    ) : RivenContextFailureCause

    data class ContractViolation(
        val violation: RivenContextContractViolation,
    ) : RivenContextFailureCause
}

data class RivenContextSourceFailure(
    val sourceId: String,
    val criticality: RivenContextSourceCriticality,
    val cause: RivenContextFailureCause,
)

data class RivenContextSnapshot(
    val fragments: List<RivenContextFragment>,
    val optionalFailures: List<RivenContextSourceFailure>,
    val budgetOmissions: List<RivenContextBudgetOmission> = emptyList(),
    val freshnessReceipts: Set<RivenContextFreshnessReceipt> = emptySet(),
)

sealed interface RivenContextFreshnessValidation {
    data object Current : RivenContextFreshnessValidation

    data class Stale(
        val receipts: Set<RivenContextFreshnessReceipt>,
    ) : RivenContextFreshnessValidation
}

data class RivenContextBudgetOmission(
    val sourceId: String,
    val fragmentId: String,
)

sealed interface RivenContextCollectionResult {
    data class Success(
        val snapshot: RivenContextSnapshot,
    ) : RivenContextCollectionResult

    data class Failure(
        val snapshot: RivenContextSnapshot,
        val requiredFailures: List<RivenContextSourceFailure>,
    ) : RivenContextCollectionResult
}

sealed interface RivenContextRegistryConfigurationError {
    data object BlankSourceId : RivenContextRegistryConfigurationError

    data class DuplicateSourceId(
        val sourceId: String,
    ) : RivenContextRegistryConfigurationError

    data class InvalidMaxFragments(
        val sourceId: String,
        val value: Int,
    ) : RivenContextRegistryConfigurationError

    data class InvalidMaxCharsPerFragment(
        val sourceId: String,
        val value: Int,
    ) : RivenContextRegistryConfigurationError

    data class InvalidMaxAggregateChars(
        val sourceId: String,
        val value: Int,
    ) : RivenContextRegistryConfigurationError
}

class RivenContextRegistryConfigurationException(
    val error: RivenContextRegistryConfigurationError,
) : IllegalArgumentException(error.toString())
