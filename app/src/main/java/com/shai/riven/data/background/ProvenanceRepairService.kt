package com.shai.riven.data.background

import androidx.room.withTransaction
import com.shai.riven.data.memory.isEvidenceSuppressedInCurrentTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.MemoryAccessibilityEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryAuditAction
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Concrete repair for provenance jobs; it never invents evidence or relationships. */
class ProvenanceRepairService(
    private val database: RivenDatabase,
    private val clock: RivenBackgroundClock = SystemRivenBackgroundClock,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val memoryDao = database.memoryDao()
    private val lifecycleDao = database.memoryLifecycleDao()
    private val maintenanceDao = database.maintenanceDao()
    private val recallFence = database.validationRecallCorpusFence()

    suspend fun repair(targetType: String, targetId: String): RepairJobHandlerResult = try {
        when (targetType) {
            TARGET_MEMORY -> repairMemory(targetId)
            TARGET_CANDIDATE -> database.withTransaction { repairCandidate(targetId) }
            TARGET_OPEN_LOOP -> database.withTransaction { repairOpenLoop(targetId) }
            TARGET_EXPERIENCE -> database.withTransaction {
                // Existence/availability is the canonical result for an Experience reassessment.
                memoryDao.experience(targetId)
                RepairJobHandlerResult.Success
            }
            else -> RepairJobHandlerResult.PermanentFailure("UNSUPPORTED_PROVENANCE_TARGET")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        RepairJobHandlerResult.RetryableFailure(
            "PROVENANCE_${failure::class.java.simpleName.safeCodeFragment()}",
        )
    }

    private suspend fun repairMemory(memoryId: String): RepairJobHandlerResult =
        recallFence.withCanonicalMutation(
            change = { mutation -> ValidationRecallCorpusChange.memoryIds(mutation.affectedMemoryIds) },
        ) {
            database.withTransaction { reassessMemoryInCurrentTransaction(memoryId) }
        }.result

    private fun reassessMemoryInCurrentTransaction(memoryId: String): ProvenanceMutation {
        val memory = memoryDao.memory(memoryId)
            ?: return ProvenanceMutation(RepairJobHandlerResult.Success)
        return if (memory.epistemicBasis == EpistemicBasis.CONSOLIDATION) {
            reassessConsolidation(memory)
        } else {
            val validSupport = safeSupportingEvidence(memory).isNotEmpty()
            if (!validSupport && memory.truthState != MemoryTruthState.UNSUPPORTED) {
                finishMemoryState(memory, supported = false)
            } else {
                ProvenanceMutation(RepairJobHandlerResult.Success)
            }
        }
    }

    private fun reassessConsolidation(memory: MemoryEntity): ProvenanceMutation {
        val relationships = memoryDao.directRelationshipsForMemory(memory.id)
            .filter {
                it.sourceMemoryId == memory.id &&
                    it.relationshipType == MemoryRelationshipType.DERIVED_FROM
            }
        val validSources = mutableListOf<MemoryEntity>()
        val validEvidenceIds = linkedSetOf<String>()
        relationships.forEach { relationship ->
            val source = memoryDao.memory(relationship.targetMemoryId)
            val safeEvidence = source?.let(::safeSupportingEvidence).orEmpty()
            val valid = source != null && source.id != memory.id &&
                source.epistemicBasis != EpistemicBasis.CONSOLIDATION &&
                source.truthState == MemoryTruthState.SUPPORTED &&
                source.retentionState != MemoryRetentionState.FORGOTTEN &&
                source.lifecycleState == MemoryLifecycleState.VALIDATED &&
                source.temporalState in CURRENT_SOURCE_TEMPORAL_STATES &&
                safeEvidence.isNotEmpty()
            if (valid) {
                validSources += requireNotNull(source)
                safeEvidence.mapTo(validEvidenceIds) { it.experienceId }
            } else {
                memoryDao.deleteMemoryRelationship(
                    relationship.sourceMemoryId,
                    relationship.targetMemoryId,
                    relationship.relationshipType,
                )
            }
        }
        memoryDao.evidenceForMemory(memory.id)
            .filter { it.experienceId !in validEvidenceIds }
            .forEach { memoryDao.deleteMemoryEvidence(memory.id, it.experienceId) }

        val required = if (memory.kind == MemoryKind.SELF_DEVELOPMENT || memory.scope == MemoryScope.RIVEN) 3 else 2
        val remainsSupported = validSources.size >= required && validEvidenceIds.size >= required
        return finishMemoryState(memory, remainsSupported, validSources)
    }

    private fun finishMemoryState(
        memory: MemoryEntity,
        supported: Boolean,
        sources: List<MemoryEntity> = emptyList(),
    ): ProvenanceMutation {
        val now = clock.now()
        val next = if (supported) {
            val weakestCertainty = sources.maxBy(MemoryEntity::certainty).certainty
            val sensitivityFloor = sources.maxBy(MemoryEntity::sensitivity).sensitivity
            memory.copy(
                truthState = MemoryTruthState.SUPPORTED,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                certainty = if (memory.certainty.ordinal < weakestCertainty.ordinal) {
                    weakestCertainty
                } else {
                    memory.certainty
                },
                sensitivity = if (memory.sensitivity.ordinal < sensitivityFloor.ordinal) {
                    sensitivityFloor
                } else {
                    memory.sensitivity
                },
                updatedAt = now,
            )
        } else {
            memory.copy(
                truthState = MemoryTruthState.UNSUPPORTED,
                lifecycleState = MemoryLifecycleState.RESOLVED,
                updatedAt = now,
            )
        }
        memoryDao.updateMemory(next)
        if (supported) {
            lifecycleDao.upsertAccessibility(
                MemoryAccessibilityEntity(
                    memoryId = memory.id,
                    band = if (next.retentionState == MemoryRetentionState.DORMANT) {
                        MemoryAccessibilityBand.DORMANT
                    } else {
                        MemoryAccessibilityBand.ORDINARY
                    },
                    reasonCode = "PROVENANCE_REASSESSED",
                    evaluatedAt = now,
                    sourceUpdatedAt = now,
                ),
            )
        } else {
            lifecycleDao.deleteAccessibility(memory.id)
        }
        maintenanceDao.insertMemoryAuditHistory(
            MemoryAuditHistoryEntity(
                id = idGenerator(),
                memoryId = memory.id,
                action = MemoryAuditAction.STATE_CHANGED,
                fromTruthState = memory.truthState,
                toTruthState = next.truthState,
                fromCertainty = memory.certainty,
                toCertainty = next.certainty,
                fromLifecycleState = memory.lifecycleState,
                toLifecycleState = next.lifecycleState,
                occurredAt = now,
            ),
        )
        maintenanceDao.markMemoryDerivedArtifacts(
            listOf(memory.id),
            DerivedArtifactState.STALE,
            now,
        )
        lifecycleDao.deleteDerivedPayloadsForMemories(listOf(memory.id))
        return ProvenanceMutation(
            result = RepairJobHandlerResult.Success,
            affectedMemoryIds = setOf(memory.id),
        )
    }

    private fun safeSupportingEvidence(memory: MemoryEntity) = memoryDao.evidenceForMemory(memory.id)
        .filter { it.role == EvidenceRole.SUPPORTS }
        .filter { evidence ->
            memoryDao.experience(evidence.experienceId)?.availability == ExperienceAvailability.AVAILABLE &&
                !isEvidenceSuppressedInCurrentTransaction(
                    maintenanceDao,
                    evidence.experienceId,
                    evidence.lineageKey,
                )
        }

    private fun repairCandidate(candidateId: String): RepairJobHandlerResult {
        val candidate = memoryDao.candidateMemory(candidateId) ?: return RepairJobHandlerResult.Success
        val evidence = memoryDao.candidateEvidence(candidateId)
        val eligible = evidence.filter { row ->
            memoryDao.experience(row.experienceId)?.availability == ExperienceAvailability.AVAILABLE &&
                !isEvidenceSuppressedInCurrentTransaction(
                    maintenanceDao,
                    row.experienceId,
                    row.lineageKey,
                )
        }
        evidence.filter { it !in eligible }.forEach { row ->
            memoryDao.deleteCandidateEvidence(candidateId, row.experienceId)
        }
        val hasSeed = eligible.count { it.role == CandidateEvidenceRole.SEED } == 1
        if (!hasSeed) {
            memoryDao.updateCandidateMemory(
                candidate.copy(state = CandidateMemoryState.REJECTED, updatedAt = clock.now()),
            )
        } else if (candidate.state !in setOf(CandidateMemoryState.ACCEPTED, CandidateMemoryState.REJECTED)) {
            memoryDao.updateCandidateMemory(
                candidate.copy(state = CandidateMemoryState.TENTATIVE, updatedAt = clock.now()),
            )
        }
        return RepairJobHandlerResult.Success
    }

    private fun repairOpenLoop(openLoopId: String): RepairJobHandlerResult {
        val loop = lifecycleDao.openLoop(openLoopId) ?: return RepairJobHandlerResult.Success
        val creationAvailable = memoryDao.experience(loop.creationExperienceId)?.availability ==
            ExperienceAvailability.AVAILABLE
        if (!creationAvailable && loop.state !in TERMINAL_OPEN_LOOP_STATES) {
            lifecycleDao.updateOpenLoop(
                loop.copy(
                    state = OpenLoopState.ABANDONED,
                    relatedMemoryId = null,
                    closedAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
        }
        return RepairJobHandlerResult.Success
    }

    private fun String.safeCodeFragment(): String =
        filter { it.isLetterOrDigit() || it == '_' }.ifBlank { "Exception" }.take(40)

    private data class ProvenanceMutation(
        val result: RepairJobHandlerResult,
        val affectedMemoryIds: Set<String> = emptySet(),
    )

    private companion object {
        const val TARGET_MEMORY = "MEMORY"
        const val TARGET_CANDIDATE = "CANDIDATE_MEMORY"
        const val TARGET_OPEN_LOOP = "OPEN_LOOP"
        const val TARGET_EXPERIENCE = "EXPERIENCE"
        val CURRENT_SOURCE_TEMPORAL_STATES = setOf(
            TemporalState.CURRENT,
            TemporalState.ATEMPORAL,
            TemporalState.UNKNOWN,
        )
        val TERMINAL_OPEN_LOOP_STATES = setOf(
            OpenLoopState.COMPLETED,
            OpenLoopState.ABANDONED,
            OpenLoopState.EXPIRED,
        )
    }
}

class ProvenanceRepairHandler(
    private val service: ProvenanceRepairService,
) : RepairJobHandler {
    override val supportedType = RepairJobType.REASSESS_PROVENANCE

    override suspend fun execute(targetType: String, targetId: String): RepairJobHandlerResult =
        service.repair(targetType, targetId)
}
