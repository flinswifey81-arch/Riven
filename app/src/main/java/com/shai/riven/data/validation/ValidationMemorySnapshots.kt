package com.shai.riven.data.validation

import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.MemoryEntityLinkInput
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState

data class ValidationCandidateSnapshot(
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
)

data class ValidationCandidateEvidenceSnapshot(
    val experienceId: String,
    val role: CandidateEvidenceRole,
    val evidenceOrder: Int,
    val lineageKey: String,
    val createdAt: Long,
    val grounding: ImmediateAttentionSnapshot,
)

data class ValidationMemoryEvidenceSnapshot(
    val experienceId: String,
    val role: EvidenceRole,
    val epistemicBasis: EpistemicBasis,
    val sourceCertainty: MemoryCertainty,
    val lineageKey: String,
)

data class ValidationMemoryRelationshipSnapshot(
    val sourceMemoryId: String,
    val targetMemoryId: String,
    val relationshipType: MemoryRelationshipType,
    val createdByExperienceId: String?,
    val createdAt: Long,
)

data class ValidationMemorySnapshot(
    val memoryId: String,
    val kind: MemoryKind,
    val scope: MemoryScope,
    val meaning: String,
    val epistemicBasis: EpistemicBasis,
    val certainty: MemoryCertainty,
    val truthState: MemoryTruthState,
    val retentionState: MemoryRetentionState,
    val lifecycleState: MemoryLifecycleState,
    val temporalState: TemporalState,
    val learnedAt: Long,
    val validFrom: Long?,
    val validUntil: Long?,
    val lastConfirmedAt: Long?,
    val significance: IntrinsicSignificanceInput,
    val sensitivity: SensitivityLevel,
    val createdAt: Long,
    val updatedAt: Long,
    val evidence: List<ValidationMemoryEvidenceSnapshot>,
    val entityLinks: List<MemoryEntityLinkInput>,
    val directRelationships: List<ValidationMemoryRelationshipSnapshot>,
)

data class CandidateValidationSnapshot(
    val candidate: ValidationCandidateSnapshot,
    val evidence: List<ValidationCandidateEvidenceSnapshot>,
    val seedAttention: ValidationAttentionSignals,
    val relatedMemories: List<ValidationMemorySnapshot>,
)

internal data class GroundedCandidateValidationContext(
    val candidate: ValidationCandidateSnapshot,
    val evidence: List<ValidationCandidateEvidenceSnapshot>,
    val seedAttention: ValidationAttentionSignals,
) {
    val seedEvidence: ValidationCandidateEvidenceSnapshot
        get() = evidence.single { it.role == CandidateEvidenceRole.SEED }

    val sensitivityFloor: SensitivityLevel
        get() = (evidence.map { it.grounding.sensitivity } + candidate.sensitivity)
            .maxBy { it.sensitivityRank() }

    val groundedEntityIds: Set<String>
        get() = evidence.flatMap { it.grounding.groundedEntityLinks }.mapTo(linkedSetOf()) { it.entityId }
}

internal fun SensitivityLevel.sensitivityRank(): Int = when (this) {
    SensitivityLevel.STANDARD -> 0
    SensitivityLevel.SENSITIVE -> 1
    SensitivityLevel.HIGHLY_SENSITIVE -> 2
}
