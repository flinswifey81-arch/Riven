package com.shai.riven.data.context

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
)

data class RivenContextReadRequest(
    val now: Long,
)

data class RivenContextPayload(
    val fragmentId: String,
    val content: String,
    val revision: Long? = null,
    val observedAt: Long? = null,
    val validUntil: Long? = null,
)

data class RivenContextFragment(
    val sourceId: String,
    val fragmentId: String,
    val content: String,
    val layer: RivenContextLayer,
    val provenanceClass: RivenContextProvenanceClass,
    val criticality: RivenContextSourceCriticality,
    val orderWithinLayer: Int,
    val budgetBehavior: RivenContextBudgetBehavior,
    val revision: Long?,
    val observedAt: Long?,
    val validUntil: Long?,
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
