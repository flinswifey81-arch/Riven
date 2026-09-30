package com.shai.riven.data.validation

import com.shai.riven.data.attention.ImmediateAttentionAbort
import com.shai.riven.data.attention.ImmediateAttentionError
import com.shai.riven.data.attention.ImmediateAttentionGrounding
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.MemoryEntityLinkInput
import com.shai.riven.data.memory.sourceLineageHash
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState

internal class CandidateValidationGrounding(
    private val database: RivenDatabase,
) {
    private val memoryDao = database.memoryDao()
    private val maintenanceDao = database.maintenanceDao()
    private val attentionGrounding = ImmediateAttentionGrounding(database)

    fun readCandidateInCurrentTransaction(candidateId: String): GroundedCandidateValidationContext {
        val candidate = memoryDao.candidateMemory(candidateId)
            ?: abort(CandidateValidationError.MissingCandidate(candidateId))
        if (candidate.state != CandidateMemoryState.READY_FOR_VALIDATION) {
            abort(CandidateValidationError.InvalidCandidateState(candidateId, candidate.state))
        }
        val evidence = memoryDao.candidateEvidence(candidateId)
        if (
            evidence.isEmpty() ||
            evidence.count { it.role == CandidateEvidenceRole.SEED } != 1 ||
            evidence.map { it.evidenceOrder }.distinct().size != evidence.size ||
            evidence.map { it.experienceId }.distinct().size != evidence.size ||
            evidence.any { it.lineageKey.isBlank() } ||
            evidence.map { it.lineageKey }.distinct().size != evidence.size
        ) {
            abort(CandidateValidationError.MalformedCandidateEvidence(candidateId))
        }

        val seed = evidence.single { it.role == CandidateEvidenceRole.SEED }
        val actionable = try {
            attentionGrounding.readActionableForwardInCurrentTransaction(seed.experienceId)
        } catch (failure: ImmediateAttentionAbort) {
            abort(failure.error.toValidationError(candidateId))
        }
        val groundedEvidence = evidence.map { row ->
            val grounding = if (row.experienceId == seed.experienceId) {
                actionable.snapshot
            } else {
                try {
                    attentionGrounding.readSnapshotInCurrentTransaction(row.experienceId)
                } catch (failure: ImmediateAttentionAbort) {
                    abort(failure.error.toValidationError(candidateId))
                }
            }
            ValidationCandidateEvidenceSnapshot(
                experienceId = row.experienceId,
                role = row.role,
                evidenceOrder = row.evidenceOrder,
                lineageKey = row.lineageKey,
                createdAt = row.createdAt,
                grounding = grounding,
            )
        }
        return GroundedCandidateValidationContext(
            candidate = ValidationCandidateSnapshot(
                candidateId = candidate.id,
                proposedKind = candidate.proposedKind,
                proposedScope = candidate.proposedScope,
                proposedMeaning = candidate.proposedMeaning,
                proposedEpistemicBasis = candidate.proposedEpistemicBasis,
                proposedCertainty = candidate.proposedCertainty,
                state = candidate.state,
                sensitivity = candidate.sensitivity,
                createdAt = candidate.createdAt,
                updatedAt = candidate.updatedAt,
            ),
            evidence = groundedEvidence,
            seedAttention = ValidationAttentionSignals(
                outcome = actionable.assessment.outcome,
                revision = actionable.assessment.revision,
                positiveSignals = actionable.assessment.positiveSignals,
                antiSignals = actionable.assessment.antiSignals,
            ),
        )
    }

    fun hydrateMemoriesInCurrentTransaction(memoryIds: List<String>): List<ValidationMemorySnapshot> =
        memoryIds.map { memoryId ->
            val memory = memoryDao.memory(memoryId)
                ?: abort(CandidateValidationError.MissingRelatedMemory(memoryId))
            ValidationMemorySnapshot(
                memoryId = memory.id,
                kind = memory.kind,
                scope = memory.scope,
                meaning = memory.meaning,
                epistemicBasis = memory.epistemicBasis,
                certainty = memory.certainty,
                truthState = memory.truthState,
                retentionState = memory.retentionState,
                lifecycleState = memory.lifecycleState,
                temporalState = memory.temporalState,
                learnedAt = memory.learnedAt,
                validFrom = memory.validFrom,
                validUntil = memory.validUntil,
                lastConfirmedAt = memory.lastConfirmedAt,
                significance = IntrinsicSignificanceInput(
                    autobiographical = memory.autobiographicalSignificance,
                    relationship = memory.relationshipSignificance,
                    emotional = memory.emotionalSignificance,
                    practical = memory.practicalSignificance,
                    identity = memory.identitySignificance,
                ),
                sensitivity = memory.sensitivity,
                createdAt = memory.createdAt,
                updatedAt = memory.updatedAt,
                evidence = memoryDao.evidenceForMemory(memoryId).map { source ->
                    ValidationMemoryEvidenceSnapshot(
                        experienceId = source.experienceId,
                        role = source.role,
                        epistemicBasis = source.epistemicBasis,
                        sourceCertainty = source.sourceCertainty,
                        lineageKey = source.lineageKey,
                    )
                },
                entityLinks = memoryDao.entityLinksForMemory(memoryId).map { link ->
                    MemoryEntityLinkInput(link.entityId, link.role)
                },
                directRelationships = memoryDao.directRelationshipsForMemory(memoryId).map { relationship ->
                    ValidationMemoryRelationshipSnapshot(
                        sourceMemoryId = relationship.sourceMemoryId,
                        targetMemoryId = relationship.targetMemoryId,
                        relationshipType = relationship.relationshipType,
                        createdByExperienceId = relationship.createdByExperienceId,
                        createdAt = relationship.createdAt,
                    )
                },
            )
        }

    fun hasActiveSuppressionInCurrentTransaction(
        context: GroundedCandidateValidationContext,
    ): Boolean = context.evidence.any { evidence ->
        maintenanceDao.suppressionTombstone(
            sourceLineageHash(evidence.experienceId, evidence.lineageKey),
        )?.isActive == true
    }

    private fun ImmediateAttentionError.toValidationError(candidateId: String): CandidateValidationError = when (this) {
        is ImmediateAttentionError.MissingExperience -> CandidateValidationError.MissingExperience(experienceId)
        is ImmediateAttentionError.ExperienceUnavailable -> CandidateValidationError.ExperienceUnavailable(experienceId)
        is ImmediateAttentionError.InactiveConversationSource ->
            CandidateValidationError.InactiveConversationEvidence(experienceId)
        is ImmediateAttentionError.MissingConversationSource,
        is ImmediateAttentionError.InvalidConversationSourceProvenance,
        is ImmediateAttentionError.StaleAttentionContext,
        -> CandidateValidationError.InvalidConversationProvenance(
            when (this) {
                is ImmediateAttentionError.MissingConversationSource -> experienceId
                is ImmediateAttentionError.InvalidConversationSourceProvenance -> experienceId
                is ImmediateAttentionError.StaleAttentionContext -> experienceId
                else -> candidateId
            },
        )
        is ImmediateAttentionError.MissingAssessment,
        is ImmediateAttentionError.AssessmentNotForward,
        -> CandidateValidationError.AttentionNotForward(
            when (this) {
                is ImmediateAttentionError.MissingAssessment -> experienceId
                is ImmediateAttentionError.AssessmentNotForward -> experienceId
                else -> candidateId
            },
        )
        is ImmediateAttentionError.AlreadyConsumedManualMemoryIntent ->
            CandidateValidationError.MalformedCandidateEvidence(candidateId)
        is ImmediateAttentionError.StorageFailure -> CandidateValidationError.StorageFailure(
            CandidateValidationOperation.READ_GROUNDED_CANDIDATE,
            causeType,
        )
        is ImmediateAttentionError.StaleAttentionRevision,
        is ImmediateAttentionError.AttentionRevisionOverflow,
        is ImmediateAttentionError.InvalidAnalyzerProposal,
        is ImmediateAttentionError.AnalyzerFailure,
        -> CandidateValidationError.StorageFailure(
            CandidateValidationOperation.READ_GROUNDED_CANDIDATE,
            this::class.java.simpleName,
        )
    }

    private fun abort(error: CandidateValidationError): Nothing = throw CandidateValidationAbort(error)
}

internal class CandidateValidationAbort(val error: CandidateValidationError) : RuntimeException()
