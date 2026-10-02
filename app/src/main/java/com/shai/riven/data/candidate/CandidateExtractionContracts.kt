package com.shai.riven.data.candidate

import com.shai.riven.data.attention.AttentionAntiSignal
import com.shai.riven.data.attention.AttentionContextMessage
import com.shai.riven.data.attention.AttentionEntityLink
import com.shai.riven.data.attention.AttentionSourceMessage
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel

const val MAX_CANDIDATES_PER_EXTRACTION = 8

data class CandidateExtractionSnapshot(
    val experienceId: String,
    val experienceType: ExperienceType,
    val actor: ExperienceActor,
    val sourceContent: String?,
    val occurredAt: Long,
    val sensitivity: SensitivityLevel,
    val sourceMessage: AttentionSourceMessage?,
    val precedingActiveContext: List<AttentionContextMessage>,
    val followingActiveContext: List<AttentionContextMessage>,
    val groundedEntityLinks: List<AttentionEntityLink>,
    val attentionRevision: Long,
    val attentionOutcome: AttentionOutcome,
    val positiveSignals: Set<PositiveAttentionSignal>,
    val antiSignals: Set<AttentionAntiSignal>,
)

data class CandidateMemoryProposal(
    val proposedMeaning: String,
    val proposedKind: MemoryKind,
    val proposedScope: MemoryScope,
    val proposedEpistemicBasis: EpistemicBasis,
    val proposedCertainty: MemoryCertainty,
    val proposedState: CandidateMemoryState,
    val proposedSensitivity: SensitivityLevel,
)

data class CandidateExtractionProposal(
    val candidates: List<CandidateMemoryProposal>,
)

fun interface CandidateMemoryExtractor {
    suspend fun extract(snapshot: CandidateExtractionSnapshot): CandidateExtractionProposal
}

fun interface CandidateIdGenerator {
    fun nextId(): String
}

data class ExtractCandidateMemoriesInput(
    val experienceId: String,
    val extractedAt: Long,
)

sealed interface CandidateExtractionResult {
    data class Extracted(
        val experienceId: String,
        val attentionRevision: Long,
        val createdCandidateIds: List<String>,
        val existingCandidateIds: List<String>,
        val suppressedLineageCount: Int,
    ) : CandidateExtractionResult

    data class NoCandidates(
        val experienceId: String,
        val attentionRevision: Long,
    ) : CandidateExtractionResult

    data class Failure(val error: CandidateExtractionError) : CandidateExtractionResult
}

data class CandidateMemorySnapshot(
    val candidateId: String,
    val proposedKind: MemoryKind,
    val proposedScope: MemoryScope,
    val proposedMeaning: String,
    val proposedEpistemicBasis: EpistemicBasis,
    val proposedCertainty: MemoryCertainty,
    val state: CandidateMemoryState,
    val sensitivity: SensitivityLevel,
    val createdAt: Long,
    val updatedAt: Long,
    val lineageKey: String,
)

sealed interface ReadCandidatesForExperienceResult {
    data class Candidates(
        val experienceId: String,
        val candidates: List<CandidateMemorySnapshot>,
    ) : ReadCandidatesForExperienceResult

    data class Failure(val error: CandidateExtractionError) : ReadCandidatesForExperienceResult
}

enum class InvalidCandidateProposalReason {
    BLANK_MEANING,
    MEANING_TOO_LONG,
    DISALLOWED_STATE,
}

enum class InvalidEpistemicBasisReason {
    SOURCE_ACTOR_MISMATCH,
    CORRECTION_SIGNAL_REQUIRED,
    CONSOLIDATION_NOT_ALLOWED,
}

enum class CandidateLineageConflictReason {
    MULTIPLE_CANDIDATES,
    PROPOSAL_MISMATCH,
    ACCEPTED_RESIDUE,
    MISSING_CANDIDATE,
}

enum class CandidateIdGenerationFailureReason {
    BLANK_ID,
    ID_TOO_LONG,
    GENERATOR_EXCEPTION,
}

enum class StaleCandidateExtractionReason {
    ATTENTION_REVISION_CHANGED,
    TIMELINE_CHANGED,
}

enum class CandidateExtractionOperation {
    READ_ACTIONABLE_ATTENTION,
    PERSIST_CANDIDATES,
    READ_CANDIDATES,
}

sealed interface CandidateExtractionError {
    data class MissingExperience(val experienceId: String) : CandidateExtractionError
    data class AttentionNotFound(val experienceId: String) : CandidateExtractionError
    data class AttentionNotForward(
        val experienceId: String,
        val outcome: AttentionOutcome,
    ) : CandidateExtractionError

    data class StaleCandidateExtractionContext(
        val experienceId: String,
        val reason: StaleCandidateExtractionReason,
        val expectedRevision: Long? = null,
        val actualRevision: Long? = null,
    ) : CandidateExtractionError

    data class InactiveConversationSource(
        val experienceId: String,
        val messageId: String,
    ) : CandidateExtractionError

    data class MissingConversationSource(
        val experienceId: String,
        val messageId: String,
    ) : CandidateExtractionError

    data class InvalidConversationSourceProvenance(val experienceId: String) : CandidateExtractionError
    data class ExperienceUnavailable(val experienceId: String) : CandidateExtractionError
    data class AlreadyConsumedManualMemoryIntent(val experienceId: String) : CandidateExtractionError
    data class InvalidCandidateProposal(
        val proposalIndex: Int,
        val reason: InvalidCandidateProposalReason,
    ) : CandidateExtractionError

    data class TooManyCandidates(val maximum: Int, val actual: Int) : CandidateExtractionError
    data class DuplicateCandidateProposal(
        val firstIndex: Int,
        val duplicateIndex: Int,
    ) : CandidateExtractionError

    data class InvalidEpistemicBasis(
        val proposalIndex: Int,
        val basis: EpistemicBasis,
        val reason: InvalidEpistemicBasisReason,
    ) : CandidateExtractionError

    data class CandidateIdCollision(val candidateId: String) : CandidateExtractionError
    data class CandidateIdGenerationFailure(
        val reason: CandidateIdGenerationFailureReason,
        val causeType: String? = null,
    ) : CandidateExtractionError

    data class CandidateLineageConflict(
        val lineageKey: String,
        val reason: CandidateLineageConflictReason,
    ) : CandidateExtractionError

    data class ExtractorFailure(val causeType: String) : CandidateExtractionError
    data class StorageFailure(
        val operation: CandidateExtractionOperation,
        val causeType: String,
    ) : CandidateExtractionError
}
