package com.shai.riven.data.memory

import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState

enum class MemoryWriteOperation {
    ADD_CANDIDATE_EVIDENCE,
    ADMIT_CANDIDATE,
    REINFORCE,
    CORRECT,
    SUPERSEDE,
    REFINE,
    DISPUTE,
    MOVE_DORMANT,
    REACTIVATE,
    FORGET,
}

sealed interface MemoryWriteResult {
    data class Success(
        val operation: MemoryWriteOperation,
        val affectedMemoryIds: Set<String> = emptySet(),
        val candidateId: String? = null,
    ) : MemoryWriteResult

    data class Failure(val error: MemoryWriteError) : MemoryWriteResult
}

sealed interface MemoryWriteError {
    data class CandidateNotFound(val candidateId: String) : MemoryWriteError
    data class InvalidCandidateState(
        val candidateId: String,
        val actualState: CandidateMemoryState,
    ) : MemoryWriteError

    data class CandidateHasNoEvidence(val candidateId: String) : MemoryWriteError
    data class CandidateHasMultipleSeedEvidence(val candidateId: String) : MemoryWriteError
    data class CandidateSeedAlreadyExists(val candidateId: String) : MemoryWriteError
    data class CandidateEvidenceAlreadyExists(
        val candidateId: String,
        val experienceId: String,
    ) : MemoryWriteError

    data class MemoryNotFound(val memoryId: String) : MemoryWriteError
    data class MemoryAlreadyExists(val memoryId: String) : MemoryWriteError
    data class ExperienceNotFound(val experienceId: String) : MemoryWriteError
    data class EntityNotFound(val entityId: String) : MemoryWriteError
    data class RelatedMemoryNotFound(val memoryId: String) : MemoryWriteError
    data class ZeroEvidence(val memoryId: String) : MemoryWriteError
    data class DuplicateEvidence(
        val memoryId: String,
        val experienceId: String,
    ) : MemoryWriteError
    data class DuplicateEvidenceLineage(
        val memoryId: String,
        val lineageKey: String,
    ) : MemoryWriteError

    data class IllegalStateTransition(
        val memoryId: String,
        val operation: MemoryWriteOperation,
        val currentState: String,
    ) : MemoryWriteError

    data class InvalidTarget(
        val operation: MemoryWriteOperation,
        val targetId: String,
        val reason: String,
    ) : MemoryWriteError

    data class StorageFailure(
        val operation: MemoryWriteOperation,
        val causeType: String,
    ) : MemoryWriteError
}

data class CandidateEvidenceWriteInput(
    val candidateId: String,
    val experienceId: String,
    val evidenceOrder: Int,
    val role: CandidateEvidenceRole,
    val lineageKey: String,
    val createdAt: Long,
)

data class IntrinsicSignificanceInput(
    val autobiographical: SignificanceLevel? = null,
    val relationship: SignificanceLevel? = null,
    val emotional: SignificanceLevel? = null,
    val practical: SignificanceLevel? = null,
    val identity: SignificanceLevel? = null,
)

data class MemoryEvidenceInput(
    val experienceId: String,
    val role: EvidenceRole,
    val epistemicBasis: EpistemicBasis,
    val sourceCertainty: MemoryCertainty,
    val lineageKey: String,
)

data class ReinforcementEvidenceInput(
    val experienceId: String,
    val epistemicBasis: EpistemicBasis,
    val sourceCertainty: MemoryCertainty,
    val lineageKey: String,
)

data class MemoryEntityLinkInput(
    val entityId: String,
    val role: EntityLinkRole,
)

data class MemoryRelationshipInput(
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
    val createdByExperienceId: String? = null,
)

data class ValidatedMemoryInput(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    val epistemicBasis: EpistemicBasis,
    val certainty: MemoryCertainty,
    val learnedAt: Long,
    val sensitivity: SensitivityLevel,
    val evidence: List<MemoryEvidenceInput>,
    val temporalState: TemporalState = TemporalState.CURRENT,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val lastConfirmedAt: Long? = null,
    val significance: IntrinsicSignificanceInput = IntrinsicSignificanceInput(),
    val entityLinks: List<MemoryEntityLinkInput> = emptyList(),
    val relationships: List<MemoryRelationshipInput> = emptyList(),
)

data class AdmitCandidateMemoryInput(
    val candidateId: String,
    val memoryId: String,
    val learnedAt: Long,
    val occurredAt: Long,
    val temporalState: TemporalState = TemporalState.CURRENT,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val significance: IntrinsicSignificanceInput = IntrinsicSignificanceInput(),
    val entityLinks: List<MemoryEntityLinkInput> = emptyList(),
    val relationships: List<MemoryRelationshipInput> = emptyList(),
)

data class ReinforceMemoryInput(
    val memoryId: String,
    val evidence: ReinforcementEvidenceInput,
    val confirmedAt: Long,
    val occurredAt: Long,
)

data class CorrectMemoryInput(
    val inaccurateMemoryId: String,
    val replacement: ValidatedMemoryInput,
    val occurredAt: Long,
    val triggeringExperienceId: String? = null,
)

data class SupersedeMemoryInput(
    val historicalMemoryId: String,
    val replacement: ValidatedMemoryInput,
    val occurredAt: Long,
    val triggeringExperienceId: String? = null,
)

enum class RefinementDisposition {
    KEEP_BROADER_CURRENT,
    SUPERSEDE_BROADER,
}

data class RefineMemoryInput(
    val broaderMemoryId: String,
    val refinement: ValidatedMemoryInput,
    val disposition: RefinementDisposition,
    val occurredAt: Long,
    val triggeringExperienceId: String? = null,
)

data class DisputeMemoryInput(
    val memoryId: String,
    val occurredAt: Long,
    val competingMemoryId: String? = null,
    val triggeringExperienceId: String? = null,
)

data class MemoryStateTransitionInput(
    val memoryId: String,
    val occurredAt: Long,
    val triggeringExperienceId: String? = null,
)

fun interface MemoryWriteIdGenerator {
    fun nextId(): String
}
