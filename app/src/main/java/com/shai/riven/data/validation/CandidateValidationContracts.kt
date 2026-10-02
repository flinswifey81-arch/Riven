package com.shai.riven.data.validation

import com.shai.riven.data.attention.AttentionAntiSignal
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.MemoryEntityLinkInput
import com.shai.riven.data.memory.RefinementDisposition
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState

const val MAX_VALIDATION_RELATED_MEMORIES = 24
const val MAX_VALIDATED_MEMORY_ID_CHARS = 128
const val TARGETED_VALIDATION_RECALL_ALGORITHM_VERSION = "targeted-validation-recall-lexical-structural-v1"

data class ValidateCandidateInput(
    val candidateId: String,
    val validatedAt: Long,
)

data class ValidationAttentionSignals(
    val outcome: AttentionOutcome,
    val revision: Long,
    val positiveSignals: Set<PositiveAttentionSignal>,
    val antiSignals: Set<AttentionAntiSignal>,
)

data class ValidationMemoryQuery(
    val candidateId: String,
    val proposedMeaning: String,
    val proposedKind: MemoryKind,
    val proposedScope: MemoryScope,
    val proposedEpistemicBasis: EpistemicBasis,
    val proposedCertainty: MemoryCertainty,
    val sensitivity: SensitivityLevel,
    val groundedEntityIds: Set<String>,
    val sourceExperienceIds: List<String>,
    val seedAttention: ValidationAttentionSignals,
)

enum class ValidationRecallReadiness {
    READY,
    NOT_READY,
    STALE,
    FAILED,
    CAPACITY_EXCEEDED,
    BUDGET_EXCEEDED,
    QUERY_UNSELECTIVE,
}

/**
 * A nonsemantic fence proving which process/database corpus a retrieval result represents. This is
 * deliberately not evidence, certainty, or a claim that lexical recall found every paraphrase.
 */
data class ValidationRecallGeneration(
    val databaseSessionId: String,
    val corpusGeneration: Long,
    val algorithmVersion: String,
)

data class ValidationRecallBudgetUsage(
    val indexedMemories: Int = 0,
    val indexedTokens: Int = 0,
    val postingVisits: Int = 0,
    val structuralRowsVisited: Int = 0,
    val graphSeedsVisited: Int = 0,
    val relationshipRowsVisited: Int = 0,
    val candidatePoolSize: Int = 0,
)

data class ValidationMemoryRetrieval(
    val memoryIds: List<String> = emptyList(),
    val readiness: ValidationRecallReadiness = ValidationRecallReadiness.NOT_READY,
    val generation: ValidationRecallGeneration? = null,
    val budgetUsage: ValidationRecallBudgetUsage = ValidationRecallBudgetUsage(),
)

fun interface ValidationMemoryRetriever {
    suspend fun retrieve(query: ValidationMemoryQuery): ValidationMemoryRetrieval
}

fun interface CandidateValidationDecider {
    suspend fun decide(snapshot: CandidateValidationSnapshot): CandidateValidationDecision
}

fun interface ValidatedMemoryIdGenerator {
    fun nextId(): String
}

enum class CandidateValidationOutcome {
    ACCEPT_NEW,
    REINFORCE_EXISTING,
    MERGE,
    REFINE_EXISTING,
    SUPERSEDE_EXISTING,
    CORRECT_EXISTING,
    DISPUTE_EXISTING,
    DEFER,
    REJECT,
}

data class ValidationAdmissionMetadata(
    val temporalState: TemporalState,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val significance: IntrinsicSignificanceInput = IntrinsicSignificanceInput(),
    val sensitivity: SensitivityLevel? = null,
    val entityLinks: List<MemoryEntityLinkInput> = emptyList(),
)

enum class ClassificationChangeReason {
    IMPRECISE_CLASSIFICATION,
    INCORRECT_CLASSIFICATION,
}

enum class CandidateDeferReason {
    NEEDS_CONTEXT,
    NEEDS_MORE_EVIDENCE,
    AMBIGUOUS_SUBJECT,
    TEMPORAL_AMBIGUITY,
    SENSITIVE_THRESHOLD,
    BROADER_PATTERN_REQUIRES_CONSOLIDATION,
}

enum class CandidateRejectReason {
    UNSUPPORTED,
    OVERREACH,
    TRANSIENT_DETAIL,
    MODEL_ASSUMPTION,
    ROLEPLAY_OR_HYPOTHETICAL,
    DUPLICATE_NO_NEW_EVIDENCE,
    LOW_VALUE,
    MISATTRIBUTED,
    TEMPORARY_STATE_AS_IDENTITY,
}

data class CandidateValidationDecision(
    val outcome: CandidateValidationOutcome,
    val targetMemoryIds: List<String> = emptyList(),
    val admission: ValidationAdmissionMetadata? = null,
    val refinementDisposition: RefinementDisposition? = null,
    val deferState: CandidateMemoryState? = null,
    val deferReason: CandidateDeferReason? = null,
    val rejectReason: CandidateRejectReason? = null,
    val classificationChangeReason: ClassificationChangeReason? = null,
)

sealed interface CandidateValidationResult {
    data class AcceptedNew(val candidateId: String, val memoryId: String) : CandidateValidationResult
    data class ReinforcedExisting(val candidateId: String, val memoryId: String) : CandidateValidationResult
    data class Refined(
        val candidateId: String,
        val oldMemoryId: String,
        val newMemoryId: String,
    ) : CandidateValidationResult

    data class Superseded(
        val candidateId: String,
        val oldMemoryId: String,
        val newMemoryId: String,
    ) : CandidateValidationResult

    data class Corrected(
        val candidateId: String,
        val oldMemoryId: String,
        val newMemoryId: String,
    ) : CandidateValidationResult

    data class Disputed(
        val candidateId: String,
        val existingMemoryId: String,
        val competingMemoryId: String,
    ) : CandidateValidationResult

    data class Deferred(
        val candidateId: String,
        val nextState: CandidateMemoryState,
        val reason: CandidateDeferReason,
    ) : CandidateValidationResult

    data class Rejected(
        val candidateId: String,
        val reason: CandidateRejectReason,
    ) : CandidateValidationResult

    data class MergeDeferredForConsolidation(
        val candidateId: String,
        val relatedMemoryIds: List<String>,
    ) : CandidateValidationResult

    data class SuppressedCandidateDiscarded(val candidateId: String) : CandidateValidationResult
    data class AlreadyAdmittedCandidateDiscarded(
        val candidateId: String,
        val memoryIds: List<String>,
    ) : CandidateValidationResult
    data class Failure(val error: CandidateValidationError) : CandidateValidationResult
}

enum class InvalidRetrieverResultReason {
    BLANK_MEMORY_ID,
    DUPLICATE_MEMORY_ID,
}

enum class InvalidValidationDecisionReason {
    INVALID_TARGET_COUNT,
    DUPLICATE_TARGET,
    UNEXPECTED_ADMISSION_METADATA,
    MISSING_ADMISSION_METADATA,
    INVALID_TEMPORAL_METADATA,
    INVALID_DEFER_STATE,
    MISSING_DEFER_REASON,
    MISSING_REJECT_REASON,
    UNEXPECTED_REASON,
    UNEXPECTED_REFINEMENT_DISPOSITION,
    MISSING_REFINEMENT_DISPOSITION,
    INVALID_CLASSIFICATION_CHANGE,
    UNGROUNDED_ENTITY,
    DUPLICATE_ENTITY_LINK,
    SENSITIVITY_DOWNGRADE,
    UNSUPPORTED_SELF_ASSERTION,
}

enum class MemoryIdGenerationFailureReason {
    BLANK,
    TOO_LONG,
    INVALID_FORMAT,
}

enum class CandidateValidationOperation {
    READ_GROUNDED_CANDIDATE,
    HYDRATE_RELATED_MEMORIES,
    APPLY_DECISION,
}

sealed interface CandidateValidationError {
    data class MissingCandidate(val candidateId: String) : CandidateValidationError
    data class InvalidCandidateState(
        val candidateId: String,
        val actualState: CandidateMemoryState,
    ) : CandidateValidationError

    data class MalformedCandidateEvidence(val candidateId: String) : CandidateValidationError
    data class MissingExperience(val experienceId: String) : CandidateValidationError
    data class ExperienceUnavailable(val experienceId: String) : CandidateValidationError
    data class InactiveConversationEvidence(val experienceId: String) : CandidateValidationError
    data class InvalidConversationProvenance(val experienceId: String) : CandidateValidationError
    data class AttentionNotForward(val experienceId: String) : CandidateValidationError
    data class SuppressedCandidate(val candidateId: String) : CandidateValidationError
    data class InvalidRetrieverResult(val reason: InvalidRetrieverResultReason) : CandidateValidationError
    data class TooManyRelatedMemories(val actualCount: Int) : CandidateValidationError
    data class MissingRelatedMemory(val memoryId: String) : CandidateValidationError
    data class InvalidValidationDecision(val reason: InvalidValidationDecisionReason) : CandidateValidationError
    data class InvalidTargetMemory(val memoryId: String) : CandidateValidationError
    data class TargetNotRetrieved(val memoryId: String) : CandidateValidationError
    data class NoIndependentEvidence(val memoryId: String) : CandidateValidationError
    data class ValidationRecallUnavailable(
        val readiness: ValidationRecallReadiness,
    ) : CandidateValidationError
    data class StaleValidationRecall(val candidateId: String) : CandidateValidationError
    data class StaleValidationContext(val candidateId: String) : CandidateValidationError
    data class MemoryIdGenerationFailure(val reason: MemoryIdGenerationFailureReason) : CandidateValidationError
    data class TransactionFailure(val causeType: String) : CandidateValidationError
    data class RetrieverFailure(val causeType: String) : CandidateValidationError
    data class ValidatorFailure(val causeType: String) : CandidateValidationError
    data class StorageFailure(
        val operation: CandidateValidationOperation,
        val causeType: String,
    ) : CandidateValidationError
}
