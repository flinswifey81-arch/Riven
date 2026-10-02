package com.shai.riven.data.memory

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryAccessibilityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionSourceCoverageEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryAuditAction
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.validation.validationRecallCorpusFence
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.ValidationRecallMutationToken
import com.shai.riven.data.validation.requireTopLevelValidationRecallMutation
import java.util.UUID
import kotlinx.coroutines.CancellationException

class MemoryTransactionService(
    private val database: RivenDatabase,
    private val idGenerator: MemoryWriteIdGenerator = MemoryWriteIdGenerator { UUID.randomUUID().toString() },
) {
    private val memoryDao = database.memoryDao()
    private val maintenanceDao = database.maintenanceDao()
    private val lifecycleDao = database.memoryLifecycleDao()
    private val validationRecallFence = database.validationRecallCorpusFence()

    suspend fun createCandidate(input: CreateCandidateMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.CREATE_CANDIDATE) {
            createCandidateOrThrow(input)
        }

    /**
     * Canonical Candidate + initial SEED insertion while the caller owns the surrounding Room
     * transaction. Candidate extraction uses this so a multi-proposal extraction is all-or-none.
     */
    internal fun createCandidateInCurrentTransaction(
        input: CreateCandidateMemoryInput,
    ): MemoryWriteResult = executeInCurrentTransaction(MemoryWriteOperation.CREATE_CANDIDATE) {
        createCandidateOrThrow(input)
    }

    suspend fun addCandidateEvidence(input: CandidateEvidenceWriteInput): MemoryWriteResult =
        execute(MemoryWriteOperation.ADD_CANDIDATE_EVIDENCE) {
            val candidate = memoryDao.candidateMemory(input.candidateId)
                ?: abort(MemoryWriteError.CandidateNotFound(input.candidateId))
            if (candidate.state == CandidateMemoryState.ACCEPTED || candidate.state == CandidateMemoryState.REJECTED) {
                abort(MemoryWriteError.InvalidCandidateState(candidate.id, candidate.state))
            }
            requireExperience(input.experienceId)
            if (memoryDao.candidateEvidenceExists(input.candidateId, input.experienceId) != 0) {
                abort(MemoryWriteError.CandidateEvidenceAlreadyExists(input.candidateId, input.experienceId))
            }
            if (
                input.role == CandidateEvidenceRole.SEED &&
                memoryDao.candidateEvidenceRoleCount(input.candidateId, CandidateEvidenceRole.SEED) != 0
            ) {
                abort(MemoryWriteError.CandidateSeedAlreadyExists(input.candidateId))
            }

            memoryDao.insertCandidateMemoryEvidence(
                CandidateMemoryEvidenceEntity(
                    candidateMemoryId = input.candidateId,
                    experienceId = input.experienceId,
                    evidenceOrder = input.evidenceOrder,
                    role = input.role,
                    lineageKey = input.lineageKey,
                    createdAt = input.createdAt,
                ),
            )
            MemoryWriteResult.Success(
                operation = MemoryWriteOperation.ADD_CANDIDATE_EVIDENCE,
                candidateId = input.candidateId,
            )
        }

    private fun createCandidateOrThrow(
        input: CreateCandidateMemoryInput,
    ): MemoryWriteResult.Success {
        if (input.candidateId.isBlank()) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.BLANK_ID))
        }
        if (input.candidateId.length > MAX_CANDIDATE_ID_CHARS) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.ID_TOO_LONG))
        }
        if (memoryDao.candidateMemory(input.candidateId) != null) {
            abort(MemoryWriteError.CandidateAlreadyExists(input.candidateId))
        }
        if (input.proposedMeaning.isBlank()) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.BLANK_MEANING))
        }
        if (input.proposedMeaning.length > MAX_CANDIDATE_MEANING_CHARS) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.MEANING_TOO_LONG))
        }
        if (input.state !in ALLOWED_CANDIDATE_CREATION_STATES) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.DISALLOWED_STATE))
        }
        val seed = input.seedEvidence
        if (seed.candidateId != input.candidateId) {
            abort(
                MemoryWriteError.InvalidCandidateCreation(
                    InvalidCandidateCreationReason.SEED_CANDIDATE_ID_MISMATCH,
                ),
            )
        }
        if (seed.role != CandidateEvidenceRole.SEED) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.SEED_ROLE_MISMATCH))
        }
        if (seed.evidenceOrder != 0) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.SEED_ORDER_MISMATCH))
        }
        if (seed.lineageKey.isBlank()) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.BLANK_LINEAGE_KEY))
        }
        if (seed.lineageKey.length > MAX_CANDIDATE_LINEAGE_KEY_CHARS) {
            abort(MemoryWriteError.InvalidCandidateCreation(InvalidCandidateCreationReason.LINEAGE_KEY_TOO_LONG))
        }
        val experience = memoryDao.experience(seed.experienceId)
            ?: abort(MemoryWriteError.ExperienceNotFound(seed.experienceId))
        if (experience.availability != ExperienceAvailability.AVAILABLE) {
            abort(MemoryWriteError.ExperienceUnavailable(seed.experienceId))
        }
        if (memoryDao.candidateEvidenceExists(input.candidateId, seed.experienceId) != 0) {
            abort(MemoryWriteError.CandidateEvidenceAlreadyExists(input.candidateId, seed.experienceId))
        }

        memoryDao.insertCandidateMemory(
            CandidateMemoryEntity(
                id = input.candidateId,
                proposedKind = input.proposedKind,
                proposedScope = input.proposedScope,
                proposedMeaning = input.proposedMeaning,
                proposedEpistemicBasis = input.proposedEpistemicBasis,
                proposedCertainty = input.proposedCertainty,
                state = input.state,
                sensitivity = input.sensitivity,
                createdAt = input.occurredAt,
                updatedAt = input.occurredAt,
            ),
        )
        memoryDao.insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(
                candidateMemoryId = input.candidateId,
                experienceId = seed.experienceId,
                evidenceOrder = 0,
                role = CandidateEvidenceRole.SEED,
                lineageKey = seed.lineageKey,
                createdAt = input.occurredAt,
            ),
        )
        return MemoryWriteResult.Success(
            operation = MemoryWriteOperation.CREATE_CANDIDATE,
            candidateId = input.candidateId,
        )
    }

    suspend fun admitCandidate(input: AdmitCandidateMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.ADMIT_CANDIDATE) {
            admitCandidateOrThrow(input)
        }

    internal fun admitCandidateInCurrentTransaction(
        input: AdmitCandidateMemoryInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.ADMIT_CANDIDATE, mutation) {
        admitCandidateOrThrow(input)
    }

    suspend fun reinforce(input: ReinforceMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.REINFORCE) {
            reinforceOrThrow(
                ReinforceMemorySetInput(
                    memoryId = input.memoryId,
                    evidence = listOf(input.evidence),
                    confirmedAt = input.confirmedAt,
                    occurredAt = input.occurredAt,
                    triggeringExperienceId = input.evidence.experienceId,
                ),
            )
        }

    internal fun reinforceInCurrentTransaction(
        input: ReinforceMemorySetInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.REINFORCE, mutation) {
        reinforceOrThrow(input)
    }

    suspend fun correct(input: CorrectMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.CORRECT) { correctInCurrentTransactionOrThrow(input) }

    /**
     * Inserts an already-validated Memory while the caller owns the surrounding Room transaction.
     * This is the shared canonical insertion path used by explicit manual Remember; it deliberately
     * does not create or admit a Candidate Memory.
     */
    internal fun createValidatedInCurrentTransaction(
        input: ValidatedMemoryInput,
        occurredAt: Long,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.CREATE_VALIDATED, mutation) {
        insertValidatedMemory(input, occurredAt)
        MemoryWriteResult.Success(
            operation = MemoryWriteOperation.CREATE_VALIDATED,
            affectedMemoryIds = setOf(input.memoryId),
        )
    }

    /**
     * Runs canonical correction mutation rules inside a transaction already owned by the caller.
     * Manual Correct uses this so its provenance Experience and the complete correction either both
     * commit or both roll back.
     */
    internal fun correctInCurrentTransaction(
        input: CorrectMemoryInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult =
        executeCanonicalInCurrentTransaction(MemoryWriteOperation.CORRECT, mutation) {
            correctInCurrentTransactionOrThrow(input)
        }

    internal fun transitionCandidateInCurrentTransaction(
        candidateId: String,
        expectedState: CandidateMemoryState,
        nextState: CandidateMemoryState,
        updatedAt: Long,
    ): MemoryWriteResult = executeInCurrentTransaction(MemoryWriteOperation.ADMIT_CANDIDATE) {
        val candidate = memoryDao.candidateMemory(candidateId)
            ?: abort(MemoryWriteError.CandidateNotFound(candidateId))
        if (candidate.state != expectedState) {
            abort(MemoryWriteError.InvalidCandidateState(candidateId, candidate.state))
        }
        memoryDao.updateCandidateMemory(candidate.copy(state = nextState, updatedAt = updatedAt))
        MemoryWriteResult.Success(
            operation = MemoryWriteOperation.ADMIT_CANDIDATE,
            candidateId = candidateId,
        )
    }

    internal fun refreshCandidateInCurrentTransaction(
        candidateId: String,
        expectedState: CandidateMemoryState,
        nextState: CandidateMemoryState,
        proposedCertainty: MemoryCertainty,
        sensitivity: SensitivityLevel,
        updatedAt: Long,
    ): MemoryWriteResult = executeInCurrentTransaction(MemoryWriteOperation.REFRESH_CANDIDATE) {
        val candidate = memoryDao.candidateMemory(candidateId)
            ?: abort(MemoryWriteError.CandidateNotFound(candidateId))
        if (candidate.state != expectedState) {
            abort(MemoryWriteError.InvalidCandidateState(candidateId, candidate.state))
        }
        memoryDao.updateCandidateMemory(
            candidate.copy(
                proposedCertainty = proposedCertainty,
                state = nextState,
                sensitivity = sensitivity,
                updatedAt = updatedAt,
            ),
        )
        MemoryWriteResult.Success(
            operation = MemoryWriteOperation.REFRESH_CANDIDATE,
            candidateId = candidateId,
        )
    }

    internal fun consumeCandidateInCurrentTransaction(
        candidateId: String,
    ): MemoryWriteResult = executeInCurrentTransaction(MemoryWriteOperation.ADMIT_CANDIDATE) {
        val candidate = memoryDao.candidateMemory(candidateId)
            ?: abort(MemoryWriteError.CandidateNotFound(candidateId))
        if (candidate.state != CandidateMemoryState.READY_FOR_VALIDATION) {
            abort(MemoryWriteError.InvalidCandidateState(candidateId, candidate.state))
        }
        memoryDao.deleteCandidateMemory(candidateId)
        MemoryWriteResult.Success(
            operation = MemoryWriteOperation.ADMIT_CANDIDATE,
            candidateId = candidateId,
        )
    }

    suspend fun supersede(input: SupersedeMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.SUPERSEDE) {
            supersedeOrThrow(input)
        }

    internal fun supersedeInCurrentTransaction(
        input: SupersedeMemoryInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.SUPERSEDE, mutation) {
        supersedeOrThrow(input)
    }

    suspend fun refine(input: RefineMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.REFINE) {
            refineOrThrow(input)
        }

    internal fun refineInCurrentTransaction(
        input: RefineMemoryInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.REFINE, mutation) {
        refineOrThrow(input)
    }

    suspend fun dispute(input: DisputeMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.DISPUTE) {
            disputeOrThrow(input)
        }

    internal fun disputeInCurrentTransaction(
        input: DisputeMemoryInput,
        mutation: ValidationRecallMutationToken,
    ): MemoryWriteResult = executeCanonicalInCurrentTransaction(MemoryWriteOperation.DISPUTE, mutation) {
        disputeOrThrow(input)
    }

    suspend fun moveDormant(input: MemoryStateTransitionInput): MemoryWriteResult =
        changeRetention(
            input = input,
            operation = MemoryWriteOperation.MOVE_DORMANT,
            requiredState = MemoryRetentionState.ACTIVE,
            targetState = MemoryRetentionState.DORMANT,
            auditAction = MemoryAuditAction.DORMANT,
        )

    suspend fun reactivate(input: MemoryStateTransitionInput): MemoryWriteResult =
        changeRetention(
            input = input,
            operation = MemoryWriteOperation.REACTIVATE,
            requiredState = MemoryRetentionState.DORMANT,
            targetState = MemoryRetentionState.ACTIVE,
            auditAction = MemoryAuditAction.REACTIVATED,
        )

    suspend fun forget(input: MemoryStateTransitionInput): MemoryWriteResult =
        execute(MemoryWriteOperation.FORGET) {
            val memory = requireMemory(input.memoryId)
            if (memory.retentionState == MemoryRetentionState.FORGOTTEN) {
                abort(
                    MemoryWriteError.IllegalStateTransition(
                        memory.id,
                        MemoryWriteOperation.FORGET,
                        memory.retentionState.name,
                    ),
                )
            }
            requireTriggeringExperience(input.triggeringExperienceId)
            val evidence = memoryDao.evidenceForMemory(memory.id)
            if (evidence.isEmpty()) abort(MemoryWriteError.ZeroEvidence(memory.id))

            val forgotten = memory.copy(
                retentionState = MemoryRetentionState.FORGOTTEN,
                updatedAt = input.occurredAt,
            )
            memoryDao.updateMemory(forgotten)
            lifecycleDao.deleteAccessibility(memory.id)
            evidence.forEach { source ->
                ensureForgetTombstone(
                    sourceClaimHash = sourceClaimSuppressionHash(source.experienceId, source.lineageKey),
                    legacyLineageHash = sourceLineageHash(source.experienceId, source.lineageKey),
                    coverage = sourceSuppressionCoverage(source.experienceId, source.lineageKey),
                    occurredAt = input.occurredAt,
                )
            }
            insertAudit(
                memoryId = memory.id,
                action = MemoryAuditAction.FORGOTTEN,
                triggeringExperienceId = input.triggeringExperienceId,
                fromRetentionState = memory.retentionState,
                toRetentionState = forgotten.retentionState,
                occurredAt = input.occurredAt,
            )
            invalidateDerived(setOf(memory.id), input.occurredAt, RepairJobType.INVALIDATE_DERIVED)
            MemoryWriteResult.Success(MemoryWriteOperation.FORGET, setOf(memory.id))
        }

    private suspend fun changeRetention(
        input: MemoryStateTransitionInput,
        operation: MemoryWriteOperation,
        requiredState: MemoryRetentionState,
        targetState: MemoryRetentionState,
        auditAction: MemoryAuditAction,
    ): MemoryWriteResult = execute(operation) {
        val memory = requireMemory(input.memoryId)
        requireTriggeringExperience(input.triggeringExperienceId)
        if (memory.retentionState != requiredState) {
            abort(
                MemoryWriteError.IllegalStateTransition(
                    memory.id,
                    operation,
                    "${memory.truthState}/${memory.retentionState}/${memory.lifecycleState}",
                ),
            )
        }
        val changed = memory.copy(retentionState = targetState, updatedAt = input.occurredAt)
        memoryDao.updateMemory(changed)
        lifecycleDao.upsertAccessibility(
            MemoryAccessibilityEntity(
                memoryId = memory.id,
                band = if (targetState == MemoryRetentionState.DORMANT) {
                    MemoryAccessibilityBand.DORMANT
                } else {
                    MemoryAccessibilityBand.ORDINARY
                },
                reasonCode = if (targetState == MemoryRetentionState.DORMANT) {
                    "RETENTION_DORMANT"
                } else {
                    "EXPLICIT_REACTIVATION"
                },
                evaluatedAt = input.occurredAt,
                sourceUpdatedAt = input.occurredAt,
            ),
        )
        insertAudit(
            memoryId = memory.id,
            action = auditAction,
            triggeringExperienceId = input.triggeringExperienceId,
            fromRetentionState = memory.retentionState,
            toRetentionState = changed.retentionState,
            occurredAt = input.occurredAt,
        )
        MemoryWriteResult.Success(operation, setOf(memory.id))
    }

    private fun admitCandidateOrThrow(input: AdmitCandidateMemoryInput): MemoryWriteResult.Success {
        val candidate = memoryDao.candidateMemory(input.candidateId)
            ?: abort(MemoryWriteError.CandidateNotFound(input.candidateId))
        if (candidate.state != CandidateMemoryState.READY_FOR_VALIDATION) {
            abort(MemoryWriteError.InvalidCandidateState(candidate.id, candidate.state))
        }
        val candidateEvidence = memoryDao.candidateEvidence(candidate.id)
        if (candidateEvidence.isEmpty()) {
            abort(MemoryWriteError.CandidateHasNoEvidence(candidate.id))
        }
        if (candidateEvidence.count { it.role == CandidateEvidenceRole.SEED } != 1) {
            abort(MemoryWriteError.CandidateHasMultipleSeedEvidence(candidate.id))
        }

        val validated = ValidatedMemoryInput(
            memoryId = input.memoryId,
            kind = candidate.proposedKind,
            scope = candidate.proposedScope,
            meaning = candidate.proposedMeaning,
            epistemicBasis = candidate.proposedEpistemicBasis,
            certainty = candidate.proposedCertainty,
            learnedAt = input.learnedAt,
            sensitivity = input.sensitivity ?: candidate.sensitivity,
            evidence = candidateEvidence.map { evidence ->
                MemoryEvidenceInput(
                    experienceId = evidence.experienceId,
                    role = evidence.role.toMemoryEvidenceRole(),
                    epistemicBasis = candidate.proposedEpistemicBasis,
                    sourceCertainty = candidate.proposedCertainty,
                    lineageKey = evidence.lineageKey,
                )
            },
            temporalState = input.temporalState,
            validFrom = input.validFrom,
            validUntil = input.validUntil,
            lastConfirmedAt = input.lastConfirmedAt,
            significance = input.significance,
            entityLinks = input.entityLinks,
            relationships = input.relationships,
        )
        insertValidatedMemory(validated, input.occurredAt)
        memoryDao.deleteCandidateMemory(candidate.id)

        return MemoryWriteResult.Success(
            operation = MemoryWriteOperation.ADMIT_CANDIDATE,
            affectedMemoryIds = setOf(input.memoryId),
            candidateId = input.candidateId,
        )
    }

    private fun reinforceOrThrow(input: ReinforceMemorySetInput): MemoryWriteResult.Success {
        val memory = requireMemory(input.memoryId)
        requireMutableUnderstanding(memory, MemoryWriteOperation.REINFORCE)
        if (input.evidence.isEmpty()) abort(MemoryWriteError.ZeroEvidence(memory.id))
        val duplicateExperience = input.evidence.groupingBy { it.experienceId }.eachCount()
            .entries.firstOrNull { it.value > 1 }?.key
        if (duplicateExperience != null) {
            abort(MemoryWriteError.DuplicateEvidence(memory.id, duplicateExperience))
        }
        val duplicateLineage = input.evidence.groupingBy { it.lineageKey }.eachCount()
            .entries.firstOrNull { it.value > 1 }?.key
        if (duplicateLineage != null) {
            abort(MemoryWriteError.DuplicateEvidenceLineage(memory.id, duplicateLineage))
        }
        input.evidence.forEach { evidence ->
            requireExperience(evidence.experienceId)
            if (memoryDao.memoryEvidenceExists(memory.id, evidence.experienceId) != 0) {
                abort(MemoryWriteError.DuplicateEvidence(memory.id, evidence.experienceId))
            }
            if (memoryDao.memoryEvidenceLineageExists(memory.id, evidence.lineageKey) != 0) {
                abort(MemoryWriteError.DuplicateEvidenceLineage(memory.id, evidence.lineageKey))
            }
        }
        input.evidence.forEach { evidence ->
            memoryDao.insertMemoryEvidence(
                MemoryEvidenceEntity(
                    memoryId = memory.id,
                    experienceId = evidence.experienceId,
                    role = EvidenceRole.SUPPORTS,
                    epistemicBasis = evidence.epistemicBasis,
                    sourceCertainty = evidence.sourceCertainty,
                    lineageKey = evidence.lineageKey,
                    createdAt = input.occurredAt,
                ),
            )
        }
        memoryDao.updateMemory(
            memory.copy(
                lastConfirmedAt = input.confirmedAt,
                retentionState = MemoryRetentionState.ACTIVE,
                updatedAt = input.occurredAt,
            ),
        )
        lifecycleDao.upsertAccessibility(
            MemoryAccessibilityEntity(
                memoryId = memory.id,
                band = MemoryAccessibilityBand.ORDINARY,
                reasonCode = "REINFORCED",
                evaluatedAt = input.occurredAt,
                sourceUpdatedAt = input.occurredAt,
            ),
        )
        insertAudit(
            memoryId = memory.id,
            action = MemoryAuditAction.REINFORCED,
            triggeringExperienceId = input.triggeringExperienceId,
            occurredAt = input.occurredAt,
        )
        if (memory.retentionState == MemoryRetentionState.DORMANT) {
            insertAudit(
                memoryId = memory.id,
                action = MemoryAuditAction.REACTIVATED,
                triggeringExperienceId = input.triggeringExperienceId,
                fromRetentionState = MemoryRetentionState.DORMANT,
                toRetentionState = MemoryRetentionState.ACTIVE,
                occurredAt = input.occurredAt,
            )
        }
        return MemoryWriteResult.Success(MemoryWriteOperation.REINFORCE, setOf(memory.id))
    }

    private fun supersedeOrThrow(input: SupersedeMemoryInput): MemoryWriteResult.Success {
        val old = requireMemory(input.historicalMemoryId)
        requireCurrentSupportedMemory(old, MemoryWriteOperation.SUPERSEDE)
        requireTriggeringExperience(input.triggeringExperienceId)
        val replacement = input.replacement.copy(temporalState = TemporalState.CURRENT)
        insertValidatedMemory(replacement, input.occurredAt)

        val historical = old.copy(
            lifecycleState = MemoryLifecycleState.SUPERSEDED,
            temporalState = TemporalState.HISTORICAL,
            validUntil = old.validUntil ?: input.occurredAt,
            updatedAt = input.occurredAt,
        )
        memoryDao.updateMemory(historical)
        memoryDao.insertMemoryRelationship(
            MemoryRelationshipEntity(
                sourceMemoryId = replacement.memoryId,
                targetMemoryId = old.id,
                relationshipType = MemoryRelationshipType.SUPERSEDES,
                createdByExperienceId = input.triggeringExperienceId,
                createdAt = input.occurredAt,
            ),
        )
        insertAudit(
            memoryId = old.id,
            action = MemoryAuditAction.SUPERSEDED,
            triggeringExperienceId = input.triggeringExperienceId,
            fromTruthState = old.truthState,
            toTruthState = historical.truthState,
            fromLifecycleState = old.lifecycleState,
            toLifecycleState = historical.lifecycleState,
            occurredAt = input.occurredAt,
        )
        invalidateDerived(setOf(old.id), input.occurredAt, RepairJobType.INVALIDATE_DERIVED)
        return MemoryWriteResult.Success(MemoryWriteOperation.SUPERSEDE, setOf(old.id, replacement.memoryId))
    }

    private fun refineOrThrow(input: RefineMemoryInput): MemoryWriteResult.Success {
        val old = requireMemory(input.broaderMemoryId)
        requireCurrentSupportedMemory(old, MemoryWriteOperation.REFINE)
        requireTriggeringExperience(input.triggeringExperienceId)
        val refinement = input.refinement.copy(temporalState = TemporalState.CURRENT)
        insertValidatedMemory(refinement, input.occurredAt)

        val broaderAfterRefinement = when (input.disposition) {
            RefinementDisposition.KEEP_BROADER_CURRENT -> old
            RefinementDisposition.SUPERSEDE_BROADER -> old.copy(
                lifecycleState = MemoryLifecycleState.SUPERSEDED,
                temporalState = TemporalState.HISTORICAL,
                validUntil = old.validUntil ?: input.occurredAt,
                updatedAt = input.occurredAt,
            ).also { memoryDao.updateMemory(it) }
        }
        memoryDao.insertMemoryRelationship(
            MemoryRelationshipEntity(
                sourceMemoryId = refinement.memoryId,
                targetMemoryId = old.id,
                relationshipType = MemoryRelationshipType.REFINES,
                createdByExperienceId = input.triggeringExperienceId,
                createdAt = input.occurredAt,
            ),
        )
        insertAudit(
            memoryId = old.id,
            action = MemoryAuditAction.REFINED,
            triggeringExperienceId = input.triggeringExperienceId,
            fromTruthState = old.truthState,
            toTruthState = broaderAfterRefinement.truthState,
            fromLifecycleState = old.lifecycleState,
            toLifecycleState = broaderAfterRefinement.lifecycleState,
            occurredAt = input.occurredAt,
        )
        invalidateDerived(setOf(old.id), input.occurredAt, RepairJobType.INVALIDATE_DERIVED)
        return MemoryWriteResult.Success(MemoryWriteOperation.REFINE, setOf(old.id, refinement.memoryId))
    }

    private fun disputeOrThrow(input: DisputeMemoryInput): MemoryWriteResult.Success {
        val primary = requireMemory(input.memoryId)
        requireMutableUnderstanding(primary, MemoryWriteOperation.DISPUTE)
        if (primary.truthState == MemoryTruthState.DISPUTED && primary.certainty == MemoryCertainty.DISPUTED) {
            abort(
                MemoryWriteError.IllegalStateTransition(
                    primary.id,
                    MemoryWriteOperation.DISPUTE,
                    "DISPUTED",
                ),
            )
        }
        requireTriggeringExperience(input.triggeringExperienceId)
        val memories = buildList {
            add(primary)
            input.competingMemoryId?.let { competingId ->
                if (competingId == primary.id) {
                    abort(MemoryWriteError.InvalidTarget(MemoryWriteOperation.DISPUTE, competingId, "self-contradiction"))
                }
                val competing = requireMemory(competingId)
                requireMutableUnderstanding(competing, MemoryWriteOperation.DISPUTE)
                add(competing)
            }
        }

        memories.forEach { memory ->
            val disputed = memory.copy(
                truthState = MemoryTruthState.DISPUTED,
                certainty = MemoryCertainty.DISPUTED,
                updatedAt = input.occurredAt,
            )
            memoryDao.updateMemory(disputed)
            insertAudit(
                memoryId = memory.id,
                action = MemoryAuditAction.DISPUTED,
                triggeringExperienceId = input.triggeringExperienceId,
                fromTruthState = memory.truthState,
                toTruthState = disputed.truthState,
                fromCertainty = memory.certainty,
                toCertainty = disputed.certainty,
                occurredAt = input.occurredAt,
            )
        }
        input.competingMemoryId?.let { competingId ->
            memoryDao.insertMemoryRelationship(
                MemoryRelationshipEntity(
                    sourceMemoryId = primary.id,
                    targetMemoryId = competingId,
                    relationshipType = MemoryRelationshipType.CONTRADICTS,
                    createdByExperienceId = input.triggeringExperienceId,
                    createdAt = input.occurredAt,
                ),
            )
        }
        val affectedIds = memories.mapTo(linkedSetOf()) { it.id }
        invalidateDerived(affectedIds, input.occurredAt, RepairJobType.INVALIDATE_DERIVED)
        return MemoryWriteResult.Success(MemoryWriteOperation.DISPUTE, affectedIds)
    }

    private fun insertValidatedMemory(
        input: ValidatedMemoryInput,
        occurredAt: Long,
    ) {
        if (memoryDao.memory(input.memoryId) != null) {
            abort(MemoryWriteError.MemoryAlreadyExists(input.memoryId))
        }
        if (input.evidence.isEmpty()) abort(MemoryWriteError.ZeroEvidence(input.memoryId))
        val duplicateExperience = input.evidence
            .groupingBy { it.experienceId }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateExperience != null) {
            abort(MemoryWriteError.DuplicateEvidence(input.memoryId, duplicateExperience))
        }
        input.evidence.forEach { requireExperience(it.experienceId) }
        input.entityLinks.forEach { link ->
            if (memoryDao.entityCount(link.entityId) == 0) abort(MemoryWriteError.EntityNotFound(link.entityId))
        }
        input.relationships.forEach { relationship ->
            if (memoryDao.memory(relationship.targetMemoryId) == null) {
                abort(MemoryWriteError.RelatedMemoryNotFound(relationship.targetMemoryId))
            }
            requireTriggeringExperience(relationship.createdByExperienceId)
        }

        val memory = MemoryEntity(
            id = input.memoryId,
            kind = input.kind,
            scope = input.scope,
            meaning = input.meaning,
            epistemicBasis = input.epistemicBasis,
            certainty = input.certainty,
            truthState = MemoryTruthState.SUPPORTED,
            retentionState = MemoryRetentionState.ACTIVE,
            lifecycleState = MemoryLifecycleState.VALIDATED,
            temporalState = input.temporalState,
            learnedAt = input.learnedAt,
            validFrom = input.validFrom,
            validUntil = input.validUntil,
            lastConfirmedAt = input.lastConfirmedAt,
            autobiographicalSignificance = input.significance.autobiographical,
            relationshipSignificance = input.significance.relationship,
            emotionalSignificance = input.significance.emotional,
            practicalSignificance = input.significance.practical,
            identitySignificance = input.significance.identity,
            sensitivity = input.sensitivity,
            createdAt = occurredAt,
            updatedAt = occurredAt,
        )
        memoryDao.insertMemory(memory)
        input.evidence.forEach { evidence ->
            memoryDao.insertMemoryEvidence(
                MemoryEvidenceEntity(
                    memoryId = memory.id,
                    experienceId = evidence.experienceId,
                    role = evidence.role,
                    epistemicBasis = evidence.epistemicBasis,
                    sourceCertainty = evidence.sourceCertainty,
                    lineageKey = evidence.lineageKey,
                    createdAt = occurredAt,
                ),
            )
        }
        input.entityLinks.forEach { link ->
            memoryDao.insertMemoryEntityLink(
                MemoryEntityLinkEntity(
                    memoryId = memory.id,
                    entityId = link.entityId,
                    role = link.role,
                    createdAt = occurredAt,
                ),
            )
        }
        input.relationships.forEach { relationship ->
            memoryDao.insertMemoryRelationship(
                MemoryRelationshipEntity(
                    sourceMemoryId = memory.id,
                    targetMemoryId = relationship.targetMemoryId,
                    relationshipType = relationship.relationshipType,
                    createdByExperienceId = relationship.createdByExperienceId,
                    createdAt = occurredAt,
                ),
            )
        }
        insertAudit(
            memoryId = memory.id,
            action = MemoryAuditAction.CREATED,
            triggeringExperienceId = input.evidence.first().experienceId,
            toTruthState = memory.truthState,
            toRetentionState = memory.retentionState,
            toCertainty = memory.certainty,
            toLifecycleState = memory.lifecycleState,
            occurredAt = occurredAt,
        )
    }

    private fun correctInCurrentTransactionOrThrow(input: CorrectMemoryInput): MemoryWriteResult.Success {
        val old = requireMemory(input.inaccurateMemoryId)
        requireMutableUnderstanding(old, MemoryWriteOperation.CORRECT)
        requireTriggeringExperience(input.triggeringExperienceId)
        val replacement = input.replacement
        insertValidatedMemory(replacement, input.occurredAt)

        val corrected = old.copy(
            truthState = MemoryTruthState.CORRECTED_FALSE,
            updatedAt = input.occurredAt,
        )
        memoryDao.updateMemory(corrected)
        memoryDao.insertMemoryRelationship(
            MemoryRelationshipEntity(
                sourceMemoryId = replacement.memoryId,
                targetMemoryId = old.id,
                relationshipType = MemoryRelationshipType.CORRECTS,
                createdByExperienceId = input.triggeringExperienceId,
                createdAt = input.occurredAt,
            ),
        )
        insertAudit(
            memoryId = old.id,
            action = MemoryAuditAction.CORRECTED,
            triggeringExperienceId = input.triggeringExperienceId,
            fromTruthState = old.truthState,
            toTruthState = corrected.truthState,
            occurredAt = input.occurredAt,
        )
        invalidateDerived(setOf(old.id), input.occurredAt, RepairJobType.PROPAGATE_CORRECTION)
        return MemoryWriteResult.Success(
            MemoryWriteOperation.CORRECT,
            setOf(old.id, replacement.memoryId),
        )
    }

    private fun insertAudit(
        memoryId: String,
        action: MemoryAuditAction,
        triggeringExperienceId: String?,
        occurredAt: Long,
        fromTruthState: MemoryTruthState? = null,
        toTruthState: MemoryTruthState? = null,
        fromRetentionState: MemoryRetentionState? = null,
        toRetentionState: MemoryRetentionState? = null,
        fromCertainty: MemoryCertainty? = null,
        toCertainty: MemoryCertainty? = null,
        fromLifecycleState: MemoryLifecycleState? = null,
        toLifecycleState: MemoryLifecycleState? = null,
    ) {
        maintenanceDao.insertMemoryAuditHistory(
            MemoryAuditHistoryEntity(
                id = idGenerator.nextId(),
                memoryId = memoryId,
                action = action,
                triggeringExperienceId = triggeringExperienceId,
                fromTruthState = fromTruthState,
                toTruthState = toTruthState,
                fromRetentionState = fromRetentionState,
                toRetentionState = toRetentionState,
                fromCertainty = fromCertainty,
                toCertainty = toCertainty,
                fromLifecycleState = fromLifecycleState,
                toLifecycleState = toLifecycleState,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun invalidateDerived(
        memoryIds: Set<String>,
        occurredAt: Long,
        repairJobType: RepairJobType,
    ) {
        val canonicalMemoryIds = memoryIds.toList()
        val experienceIds = memoryDao.experienceIdsForMemories(canonicalMemoryIds)
        val messageIds = if (experienceIds.isEmpty()) {
            emptyList()
        } else {
            memoryDao.messageIdsForExperiences(experienceIds)
        }
        val openLoopIds = memoryDao.openLoopIdsForMemories(canonicalMemoryIds)
        maintenanceDao.markMemoryDerivedArtifacts(
            memoryIds = canonicalMemoryIds,
            state = DerivedArtifactState.STALE,
            invalidatedAt = occurredAt,
        )
        lifecycleDao.deleteDerivedPayloadsForMemories(canonicalMemoryIds)
        if (experienceIds.isNotEmpty()) {
            maintenanceDao.markExperienceDerivedArtifacts(
                experienceIds = experienceIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
            lifecycleDao.deleteDerivedPayloadsForExperiences(experienceIds)
        }
        if (messageIds.isNotEmpty()) {
            maintenanceDao.markMessageDerivedArtifacts(
                messageIds = messageIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
            lifecycleDao.deleteDerivedPayloadsForMessages(messageIds)
        }
        if (openLoopIds.isNotEmpty()) {
            maintenanceDao.markOpenLoopDerivedArtifacts(
                openLoopIds = openLoopIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
            lifecycleDao.deleteDerivedPayloadsForOpenLoops(openLoopIds)
        }
        memoryIds.forEach { memoryId ->
            maintenanceDao.insertRepairJob(
                RepairJobEntity(
                    id = idGenerator.nextId(),
                    jobType = repairJobType,
                    state = RepairJobState.PENDING,
                    targetType = REPAIR_TARGET_MEMORY,
                    targetId = memoryId,
                    attemptCount = 0,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
            )
        }
    }

    private fun requireMemory(memoryId: String): MemoryEntity =
        memoryDao.memory(memoryId) ?: abort(MemoryWriteError.MemoryNotFound(memoryId))

    private fun requireExperience(experienceId: String) {
        if (memoryDao.experienceCount(experienceId) == 0) {
            abort(MemoryWriteError.ExperienceNotFound(experienceId))
        }
    }

    private fun requireTriggeringExperience(experienceId: String?) {
        if (experienceId != null) requireExperience(experienceId)
    }

    private fun requireMutableUnderstanding(memory: MemoryEntity, operation: MemoryWriteOperation) {
        if (
            memory.truthState == MemoryTruthState.CORRECTED_FALSE ||
            memory.retentionState == MemoryRetentionState.FORGOTTEN ||
            memory.lifecycleState == MemoryLifecycleState.SUPERSEDED
        ) {
            abort(
                MemoryWriteError.InvalidTarget(
                    operation,
                    memory.id,
                    "${memory.truthState}/${memory.retentionState}/${memory.lifecycleState}",
                ),
            )
        }
    }

    private fun requireCurrentSupportedMemory(memory: MemoryEntity, operation: MemoryWriteOperation) {
        requireMutableUnderstanding(memory, operation)
        if (memory.truthState != MemoryTruthState.SUPPORTED || memory.temporalState != TemporalState.CURRENT) {
            abort(
                MemoryWriteError.InvalidTarget(
                    operation,
                    memory.id,
                    "${memory.truthState}/${memory.temporalState}",
                ),
            )
        }
    }

    private suspend fun execute(
        operation: MemoryWriteOperation,
        block: suspend () -> MemoryWriteResult.Success,
    ): MemoryWriteResult {
        if (operation in VALIDATION_RECALL_MUTATIONS) {
            database.requireTopLevelValidationRecallMutation()
        }
        return try {
            if (operation in VALIDATION_RECALL_MUTATIONS) {
                validationRecallFence.withCanonicalMutation(
                    change = { result -> ValidationRecallCorpusChange.memoryIds(result.affectedMemoryIds) },
                ) {
                    database.withTransaction { block() }
                }
            } else {
                database.withTransaction { block() }
            }
        } catch (abort: MemoryWriteAbort) {
            MemoryWriteResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (constraint: SQLiteConstraintException) {
            MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, constraint::class.java.simpleName))
        } catch (failure: Exception) {
            MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, failure::class.java.simpleName))
        }
    }

    private fun executeInCurrentTransaction(
        operation: MemoryWriteOperation,
        block: () -> MemoryWriteResult.Success,
    ): MemoryWriteResult = try {
        block()
    } catch (abort: MemoryWriteAbort) {
        MemoryWriteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, constraint::class.java.simpleName))
    } catch (failure: Exception) {
        MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, failure::class.java.simpleName))
    }

    private fun ensureForgetTombstone(
        sourceClaimHash: String,
        legacyLineageHash: String,
        coverage: SourceSuppressionCoverage?,
        occurredAt: Long,
    ) {
        val existing = maintenanceDao.suppressionTombstone(sourceClaimHash)
            ?: maintenanceDao.suppressionTombstone(legacyLineageHash)
        val tombstone = when {
            existing == null -> SuppressionTombstoneEntity(
                id = idGenerator.nextId(),
                kind = SuppressionKind.FORGET,
                sourceLineageHash = sourceClaimHash,
                isActive = true,
                createdAt = occurredAt,
                formatVersion = 1,
            ).also(maintenanceDao::insertSuppressionTombstone)
            existing.sourceLineageHash != sourceClaimHash ||
                !existing.isActive || existing.kind != SuppressionKind.FORGET -> existing.copy(
                kind = if (existing.kind == SuppressionKind.DELETE) {
                    SuppressionKind.DELETE
                } else {
                    SuppressionKind.FORGET
                },
                sourceLineageHash = sourceClaimHash,
                isActive = true,
                expiresAt = null,
            ).also(maintenanceDao::updateSuppressionTombstone)
            else -> existing
        }
        coverage?.let { value ->
            maintenanceDao.upsertSuppressionSourceCoverage(
                SuppressionSourceCoverageEntity(
                    id = tombstone.id,
                    tombstoneId = tombstone.id,
                    sourceIdentityHash = value.sourceIdentityHash,
                    startOffset = value.startOffset,
                    endOffsetExclusive = value.endOffsetExclusive,
                ),
            )
        }
    }

    private fun CandidateEvidenceRole.toMemoryEvidenceRole(): EvidenceRole = when (this) {
        CandidateEvidenceRole.SEED,
        CandidateEvidenceRole.SUPPORTING,
        CandidateEvidenceRole.CONTEXT,
        -> EvidenceRole.SUPPORTS
        CandidateEvidenceRole.CONTRADICTING -> EvidenceRole.CONTRADICTS
        CandidateEvidenceRole.CORRECTING -> EvidenceRole.CORRECTS
    }

    private fun abort(error: MemoryWriteError): Nothing = throw MemoryWriteAbort(error)

    private class MemoryWriteAbort(val error: MemoryWriteError) : RuntimeException()

    private companion object {
        const val REPAIR_TARGET_MEMORY = "MEMORY"
        val ALLOWED_CANDIDATE_CREATION_STATES = setOf(
            CandidateMemoryState.PENDING_CONTEXT,
            CandidateMemoryState.TENTATIVE,
            CandidateMemoryState.READY_FOR_VALIDATION,
        )
        val VALIDATION_RECALL_MUTATIONS = setOf(
            MemoryWriteOperation.ADMIT_CANDIDATE,
            MemoryWriteOperation.CREATE_VALIDATED,
            MemoryWriteOperation.REINFORCE,
            MemoryWriteOperation.CORRECT,
            MemoryWriteOperation.SUPERSEDE,
            MemoryWriteOperation.REFINE,
            MemoryWriteOperation.DISPUTE,
            MemoryWriteOperation.MOVE_DORMANT,
            MemoryWriteOperation.REACTIVATE,
            MemoryWriteOperation.FORGET,
        )
    }

    private fun executeCanonicalInCurrentTransaction(
        operation: MemoryWriteOperation,
        mutation: ValidationRecallMutationToken,
        block: () -> MemoryWriteResult.Success,
    ): MemoryWriteResult {
        check(validationRecallFence.ownsActiveMutation(mutation)) {
            "Canonical recall mutation must be owned by the surrounding transaction"
        }
        return executeInCurrentTransaction(operation, block)
    }
}
