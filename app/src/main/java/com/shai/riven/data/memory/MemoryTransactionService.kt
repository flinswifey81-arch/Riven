package com.shai.riven.data.memory

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.MemoryAuditAction
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class MemoryTransactionService(
    private val database: RivenDatabase,
    private val idGenerator: MemoryWriteIdGenerator = MemoryWriteIdGenerator { UUID.randomUUID().toString() },
) {
    private val memoryDao = database.memoryDao()
    private val maintenanceDao = database.maintenanceDao()

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

    suspend fun admitCandidate(input: AdmitCandidateMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.ADMIT_CANDIDATE) {
            val candidate = memoryDao.candidateMemory(input.candidateId)
                ?: abort(MemoryWriteError.CandidateNotFound(input.candidateId))
            if (candidate.state != CandidateMemoryState.READY_FOR_VALIDATION) {
                abort(MemoryWriteError.InvalidCandidateState(candidate.id, candidate.state))
            }
            val candidateEvidence = memoryDao.candidateEvidence(candidate.id)
            if (candidateEvidence.isEmpty()) {
                abort(MemoryWriteError.CandidateHasNoEvidence(candidate.id))
            }
            if (candidateEvidence.count { it.role == CandidateEvidenceRole.SEED } > 1) {
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
                sensitivity = candidate.sensitivity,
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
                significance = input.significance,
                entityLinks = input.entityLinks,
                relationships = input.relationships,
            )
            insertValidatedMemory(validated, input.occurredAt)
            memoryDao.deleteCandidateMemory(candidate.id)

            MemoryWriteResult.Success(
                operation = MemoryWriteOperation.ADMIT_CANDIDATE,
                affectedMemoryIds = setOf(input.memoryId),
                candidateId = input.candidateId,
            )
        }

    suspend fun reinforce(input: ReinforceMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.REINFORCE) {
            val memory = requireMemory(input.memoryId)
            requireMutableUnderstanding(memory, MemoryWriteOperation.REINFORCE)
            requireExperience(input.evidence.experienceId)
            if (memoryDao.memoryEvidenceExists(memory.id, input.evidence.experienceId) != 0) {
                abort(MemoryWriteError.DuplicateEvidence(memory.id, input.evidence.experienceId))
            }
            if (memoryDao.memoryEvidenceLineageExists(memory.id, input.evidence.lineageKey) != 0) {
                abort(MemoryWriteError.DuplicateEvidenceLineage(memory.id, input.evidence.lineageKey))
            }

            memoryDao.insertMemoryEvidence(
                MemoryEvidenceEntity(
                    memoryId = memory.id,
                    experienceId = input.evidence.experienceId,
                    role = EvidenceRole.SUPPORTS,
                    epistemicBasis = input.evidence.epistemicBasis,
                    sourceCertainty = input.evidence.sourceCertainty,
                    lineageKey = input.evidence.lineageKey,
                    createdAt = input.occurredAt,
                ),
            )
            memoryDao.updateMemory(
                memory.copy(
                    lastConfirmedAt = input.confirmedAt,
                    updatedAt = input.occurredAt,
                ),
            )
            insertAudit(
                memoryId = memory.id,
                action = MemoryAuditAction.REINFORCED,
                triggeringExperienceId = input.evidence.experienceId,
                occurredAt = input.occurredAt,
            )
            MemoryWriteResult.Success(MemoryWriteOperation.REINFORCE, setOf(memory.id))
        }

    suspend fun correct(input: CorrectMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.CORRECT) {
            val old = requireMemory(input.inaccurateMemoryId)
            requireMutableUnderstanding(old, MemoryWriteOperation.CORRECT)
            requireTriggeringExperience(input.triggeringExperienceId)
            val replacement = input.replacement.copy(temporalState = TemporalState.CURRENT)
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
            MemoryWriteResult.Success(MemoryWriteOperation.CORRECT, setOf(old.id, replacement.memoryId))
        }

    suspend fun supersede(input: SupersedeMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.SUPERSEDE) {
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
            MemoryWriteResult.Success(MemoryWriteOperation.SUPERSEDE, setOf(old.id, replacement.memoryId))
        }

    suspend fun refine(input: RefineMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.REFINE) {
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
            MemoryWriteResult.Success(MemoryWriteOperation.REFINE, setOf(old.id, refinement.memoryId))
        }

    suspend fun dispute(input: DisputeMemoryInput): MemoryWriteResult =
        execute(MemoryWriteOperation.DISPUTE) {
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
            MemoryWriteResult.Success(MemoryWriteOperation.DISPUTE, affectedIds)
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
            evidence.forEach { source ->
                val sourceLineageHash = source.lineageHash()
                val existing = maintenanceDao.suppressionTombstone(sourceLineageHash)
                when {
                    existing == null -> maintenanceDao.insertSuppressionTombstone(
                        SuppressionTombstoneEntity(
                            id = idGenerator.nextId(),
                            kind = SuppressionKind.FORGET,
                            sourceLineageHash = sourceLineageHash,
                            isActive = true,
                            createdAt = input.occurredAt,
                            formatVersion = 1,
                        ),
                    )
                    !existing.isActive -> maintenanceDao.updateSuppressionTombstone(
                        existing.copy(
                            isActive = true,
                            expiresAt = null,
                        ),
                    )
                }
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
        if (experienceIds.isNotEmpty()) {
            maintenanceDao.markExperienceDerivedArtifacts(
                experienceIds = experienceIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
        }
        if (messageIds.isNotEmpty()) {
            maintenanceDao.markMessageDerivedArtifacts(
                messageIds = messageIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
        }
        if (openLoopIds.isNotEmpty()) {
            maintenanceDao.markOpenLoopDerivedArtifacts(
                openLoopIds = openLoopIds,
                state = DerivedArtifactState.STALE,
                invalidatedAt = occurredAt,
            )
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
    ): MemoryWriteResult = try {
        database.withTransaction { block() }
    } catch (abort: MemoryWriteAbort) {
        MemoryWriteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, constraint::class.java.simpleName))
    } catch (failure: Exception) {
        MemoryWriteResult.Failure(MemoryWriteError.StorageFailure(operation, failure::class.java.simpleName))
    }

    private fun MemoryEvidenceEntity.lineageHash(): String {
        val canonicalLineage = "$experienceId:$lineageKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalLineage.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
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
    }
}
