package com.shai.riven.data.deletion

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.OpenLoopAuditAction
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType
import com.shai.riven.data.persistence.model.SuppressionKind
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

class SafeDeleteService(
    private val database: RivenDatabase,
    private val idGenerator: SafeDeleteIdGenerator = SafeDeleteIdGenerator { UUID.randomUUID().toString() },
    private val afterDependencyMutation: (SafeDeleteOperation) -> Unit = {},
) {
    private val dao = database.safeDeleteDao()

    suspend fun deleteMemory(input: DeleteMemoryInput): MemoryDeleteResult =
        executeMemoryDelete {
            val accumulator = DeleteAccumulator(input.occurredAt)
            hardDeleteMemory(
                memoryId = input.memoryId,
                sourceExperienceIdsBeingDeleted = emptySet(),
                accumulator = accumulator,
                invokeFailureHook = true,
            )
            MemoryDeleteResult.Deleted(
                deletedMemoryId = input.memoryId,
                neighboringMemoryIdsRequiringReassessment = accumulator.reassessedMemoryIds.toSortedSet(),
                invalidatedDerivedArtifactIds = accumulator.invalidatedArtifactIds.toSortedSet(),
                suppressionTombstones = accumulator.tombstones
                    .sortedBy { it.sourceLineageHash }
                    .toSet(),
            )
        }

    suspend fun deleteLeafMessage(input: DeleteTimelineMessageInput): TimelineDeleteResult =
        executeTimelineDelete {
            val plan = buildTimelineDeletePlan(input)
            val accumulator = DeleteAccumulator(input.occurredAt)

            invalidateMessageDependencies(plan.messageId, accumulator)

            plan.changedExperienceIds.forEach { experienceId ->
                invalidateArtifacts(
                    dao.derivedArtifactIdsForExperience(experienceId),
                    accumulator,
                )
                dao.deleteExperienceMessageSource(experienceId, plan.messageId)
                val experience = requireExperience(experienceId)
                if (experience.sourceContent != null) {
                    dao.updateExperience(experience.copy(sourceContent = null))
                }
                accumulator.queueRepair(
                    RepairJobType.REASSESS_PROVENANCE,
                    TARGET_EXPERIENCE,
                    experienceId,
                )
            }

            val affectedCandidateIds = linkedSetOf<String>()
            val affectedMemoryIds = linkedSetOf<String>()
            plan.deletedExperienceIds.forEach { experienceId ->
                dao.candidateEvidenceForExperience(experienceId).forEach { evidence ->
                    affectedCandidateIds += evidence.candidateMemoryId
                    dao.deleteCandidateEvidence(evidence.candidateMemoryId, experienceId)
                }
                dao.memoryEvidenceForExperience(experienceId).forEach { evidence ->
                    affectedMemoryIds += evidence.memoryId
                    dao.deleteMemoryEvidence(evidence.memoryId, experienceId)
                }
            }

            affectedCandidateIds.forEach { candidateId ->
                val candidate = dao.candidateMemory(candidateId) ?: return@forEach
                if (dao.candidateEvidenceForCandidate(candidateId).isEmpty()) {
                    dao.deleteCandidateMemory(candidateId)
                    accumulator.deletedCandidateIds += candidateId
                } else {
                    dao.updateCandidateMemory(
                        candidate.copy(
                            state = CandidateMemoryState.TENTATIVE,
                            updatedAt = input.occurredAt,
                        ),
                    )
                    accumulator.queueRepair(
                        RepairJobType.REASSESS_PROVENANCE,
                        TARGET_CANDIDATE_MEMORY,
                        candidateId,
                    )
                }
            }

            val memoriesToDelete = affectedMemoryIds.filterTo(linkedSetOf()) { memoryId ->
                dao.evidenceForMemory(memoryId).isEmpty()
            }
            val survivingAffectedMemories = affectedMemoryIds - memoriesToDelete

            plan.deletedExperienceIds.forEach { experienceId ->
                dao.relationshipsForExperience(experienceId).forEach { relationship ->
                    dao.deleteMemoryRelationship(relationship)
                    sequenceOf(relationship.sourceMemoryId, relationship.targetMemoryId)
                        .filter { it !in memoriesToDelete && dao.memory(it) != null }
                        .forEach { accumulator.reassessedMemoryIds += it }
                }
            }

            memoriesToDelete.forEach { memoryId ->
                hardDeleteMemory(
                    memoryId = memoryId,
                    sourceExperienceIdsBeingDeleted = plan.deletedExperienceIds,
                    accumulator = accumulator,
                    invokeFailureHook = false,
                )
            }

            survivingAffectedMemories.forEach { memoryId ->
                invalidateArtifacts(dao.derivedArtifactIdsForMemory(memoryId), accumulator)
                accumulator.reassessedMemoryIds += memoryId
            }
            accumulator.reassessedMemoryIds
                .filter { it !in accumulator.deletedMemoryIds && dao.memory(it) != null }
                .forEach { memoryId ->
                    invalidateArtifacts(dao.derivedArtifactIdsForMemory(memoryId), accumulator)
                    accumulator.queueRepair(
                        RepairJobType.REASSESS_PROVENANCE,
                        TARGET_MEMORY,
                        memoryId,
                    )
                }

            plan.openLoopIdsToDelete.forEach { openLoopId ->
                hardDeleteOpenLoop(openLoopId, accumulator)
            }
            plan.openLoopRollbacks.forEach { rollback ->
                val openLoop = dao.openLoop(rollback.openLoopId) ?: return@forEach
                dao.updateOpenLoop(
                    openLoop.copy(
                        resolutionExperienceId = null,
                        state = rollback.priorState,
                        closedAt = null,
                        updatedAt = input.occurredAt,
                    ),
                )
                invalidateArtifacts(dao.derivedArtifactIdsForOpenLoop(openLoop.id), accumulator)
                accumulator.queueRepair(
                    RepairJobType.REASSESS_PROVENANCE,
                    TARGET_OPEN_LOOP,
                    openLoop.id,
                )
            }

            plan.deletedExperienceIds.forEach { experienceId ->
                dao.deleteMemoryAuditsForExperience(experienceId)
                dao.deleteOpenLoopAuditsForExperience(experienceId)
                invalidateArtifacts(dao.derivedArtifactIdsForExperience(experienceId), accumulator)
                dao.deleteDerivedExperienceDependencies(experienceId)
                dao.deleteExperience(experienceId)
                accumulator.deletedExperienceIds += experienceId
                accumulator.queueRepair(
                    RepairJobType.PROPAGATE_DELETION,
                    TARGET_EXPERIENCE,
                    experienceId,
                )
            }

            afterDependencyMutation(SafeDeleteOperation.DELETE_TIMELINE_MESSAGE)

            dao.updateTimelineHead(
                plan.head.copy(
                    activeHeadMessageId = plan.resultingHeadMessageId,
                    timelineRevision = plan.nextRevision,
                    updatedAt = input.occurredAt,
                ),
            )
            dao.updateConversation(plan.conversation.copy(updatedAt = input.occurredAt))
            dao.deleteMessage(plan.messageId)
            accumulator.queueRepair(
                RepairJobType.PROPAGATE_DELETION,
                TARGET_MESSAGE,
                plan.messageId,
            )

            TimelineDeleteResult.Deleted(
                conversationId = input.conversationId,
                deletedMessageId = input.messageId,
                activeHeadMessageId = plan.resultingHeadMessageId,
                timelineRevision = plan.nextRevision,
                deletedExperienceIds = accumulator.deletedExperienceIds.toSortedSet(),
                deletedCandidateMemoryIds = accumulator.deletedCandidateIds.toSortedSet(),
                deletedMemoryIds = accumulator.deletedMemoryIds.toSortedSet(),
                deletedOpenLoopIds = accumulator.deletedOpenLoopIds.toSortedSet(),
                invalidatedDerivedArtifactIds = accumulator.invalidatedArtifactIds.toSortedSet(),
            )
        }

    private fun buildTimelineDeletePlan(input: DeleteTimelineMessageInput): TimelineDeletePlan {
        val conversation = dao.conversation(input.conversationId)
            ?: abort(SafeDeleteError.MissingConversation(input.conversationId))
        val head = dao.timelineHead(input.conversationId)
            ?: abort(SafeDeleteError.MissingTimelineHeadRecord(input.conversationId))
        if (head.timelineRevision < 0) {
            abort(SafeDeleteError.InvalidTimelineRevision(input.conversationId, head.timelineRevision))
        }
        if (head.timelineRevision != input.expectedTimelineRevision) {
            abort(SafeDeleteError.StaleTimelineRevision(input.expectedTimelineRevision, head.timelineRevision))
        }
        val message = dao.message(input.messageId)
            ?: abort(SafeDeleteError.MissingMessage(input.messageId))
        if (message.conversationId != input.conversationId) {
            abort(
                SafeDeleteError.MessageBelongsToDifferentConversation(
                    messageId = message.id,
                    expectedConversationId = input.conversationId,
                    actualConversationId = message.conversationId,
                ),
            )
        }
        head.activeHeadMessageId?.let { headMessageId ->
            val headMessage = dao.message(headMessageId)
                ?: abort(SafeDeleteError.MissingMessage(headMessageId))
            if (headMessage.conversationId != input.conversationId) {
                abort(
                    SafeDeleteError.TimelineHeadBelongsToDifferentConversation(
                        conversationId = input.conversationId,
                        headMessageId = headMessageId,
                        actualConversationId = headMessage.conversationId,
                    ),
                )
            }
        }
        val childCount = dao.childMessageCount(message.id)
        if (childCount != 0) {
            abort(SafeDeleteError.MessageHasChildren(message.id, childCount))
        }
        if (head.timelineRevision == Long.MAX_VALUE) {
            abort(SafeDeleteError.TimelineRevisionOverflow(input.conversationId))
        }

        val deletedExperienceIds = linkedSetOf<String>()
        val changedExperienceIds = linkedSetOf<String>()
        dao.messageSourcesForMessage(message.id).forEach { source ->
            requireExperience(source.experienceId)
            val sourceCount = dao.messageSourcesForExperience(source.experienceId).size
            if (sourceCount == 1) {
                deletedExperienceIds += source.experienceId
            } else {
                if (hasSemanticDependents(source.experienceId)) {
                    abort(
                        SafeDeleteError.AmbiguousProvenanceForDeletion(
                            messageId = message.id,
                            experienceId = source.experienceId,
                        ),
                    )
                }
                changedExperienceIds += source.experienceId
            }
        }

        val openLoopIdsToDelete = deletedExperienceIds
            .flatMapTo(linkedSetOf()) { experienceId ->
                dao.openLoopsCreatedByExperience(experienceId).map { it.id }
            }
        val openLoopRollbacks = mutableListOf<OpenLoopRollback>()
        deletedExperienceIds.forEach { experienceId ->
            dao.openLoopsResolvedByExperience(experienceId)
                .filter { it.id !in openLoopIdsToDelete }
                .forEach { openLoop ->
                    val stateAudits = dao.openLoopAuditsForExperience(openLoop.id, experienceId)
                        .filter { audit ->
                            audit.action == OpenLoopAuditAction.STATE_CHANGED &&
                                audit.fromState != null &&
                                audit.toState == openLoop.state
                        }
                    val priorState = stateAudits.singleOrNull()?.fromState
                    if (priorState == null || priorState !in UNRESOLVED_OPEN_LOOP_STATES) {
                        abort(SafeDeleteError.AmbiguousOpenLoopRollback(openLoop.id, experienceId))
                    }
                    openLoopRollbacks += OpenLoopRollback(openLoop.id, experienceId, priorState)
                }

            val allowedAuditOwners = openLoopIdsToDelete +
                openLoopRollbacks.filter { it.experienceId == experienceId }.map { it.openLoopId }
            dao.openLoopAuditsForExperience(experienceId)
                .firstOrNull { it.openLoopId !in allowedAuditOwners }
                ?.let { audit ->
                    abort(SafeDeleteError.AmbiguousOpenLoopRollback(audit.openLoopId, experienceId))
                }
        }

        val parentId = dao.parentEdge(message.id)?.parentMessageId
        return TimelineDeletePlan(
            conversation = conversation,
            head = head,
            messageId = message.id,
            resultingHeadMessageId = if (head.activeHeadMessageId == message.id) {
                parentId
            } else {
                head.activeHeadMessageId
            },
            nextRevision = head.timelineRevision + 1,
            deletedExperienceIds = deletedExperienceIds,
            changedExperienceIds = changedExperienceIds,
            openLoopIdsToDelete = openLoopIdsToDelete,
            openLoopRollbacks = openLoopRollbacks,
        )
    }

    private fun hasSemanticDependents(experienceId: String): Boolean =
        dao.candidateEvidenceCountForExperience(experienceId) != 0 ||
            dao.experienceEntityLinkCountForExperience(experienceId) != 0 ||
            dao.memoryEvidenceCountForExperience(experienceId) != 0 ||
            dao.openLoopDependencyCountForExperience(experienceId) != 0 ||
            dao.relationshipCountForExperience(experienceId) != 0 ||
            dao.memoryAuditCountForExperience(experienceId) != 0 ||
            dao.openLoopAuditCountForExperience(experienceId) != 0

    private fun hardDeleteMemory(
        memoryId: String,
        sourceExperienceIdsBeingDeleted: Set<String>,
        accumulator: DeleteAccumulator,
        invokeFailureHook: Boolean,
    ) {
        dao.memory(memoryId) ?: abort(SafeDeleteError.MissingMemory(memoryId))
        val retainedEvidence = dao.evidenceForMemory(memoryId)
            .filterNot { it.experienceId in sourceExperienceIdsBeingDeleted }
        retainedEvidence.forEach { evidence ->
            accumulator.tombstones += ensureDeleteTombstone(evidence, accumulator.occurredAt)
        }

        invalidateArtifacts(dao.derivedArtifactIdsForMemory(memoryId), accumulator)
        dao.deleteDerivedMemoryDependencies(memoryId)

        dao.relationshipsForMemory(memoryId).forEach { relationship ->
            val neighborId = if (relationship.sourceMemoryId == memoryId) {
                relationship.targetMemoryId
            } else {
                relationship.sourceMemoryId
            }
            dao.deleteMemoryRelationship(relationship)
            if (neighborId != memoryId && dao.memory(neighborId) != null) {
                accumulator.reassessedMemoryIds += neighborId
            }
        }

        dao.openLoopsForMemory(memoryId).forEach { openLoop ->
            dao.updateOpenLoop(
                openLoop.copy(
                    relatedMemoryId = null,
                    updatedAt = accumulator.occurredAt,
                ),
            )
            invalidateArtifacts(dao.derivedArtifactIdsForOpenLoop(openLoop.id), accumulator)
            accumulator.queueRepair(
                RepairJobType.REASSESS_PROVENANCE,
                TARGET_OPEN_LOOP,
                openLoop.id,
            )
        }

        dao.deleteMemoryAuditsForMemory(memoryId)
        if (invokeFailureHook) afterDependencyMutation(SafeDeleteOperation.DELETE_MEMORY)
        dao.deleteMemory(memoryId)
        accumulator.deletedMemoryIds += memoryId
        accumulator.queueRepair(
            RepairJobType.PROPAGATE_DELETION,
            TARGET_MEMORY,
            memoryId,
        )

        accumulator.reassessedMemoryIds
            .filter { it !in accumulator.deletedMemoryIds && dao.memory(it) != null }
            .forEach { neighborId ->
                invalidateArtifacts(dao.derivedArtifactIdsForMemory(neighborId), accumulator)
                accumulator.queueRepair(
                    RepairJobType.REASSESS_PROVENANCE,
                    TARGET_MEMORY,
                    neighborId,
                )
            }
    }

    private fun hardDeleteOpenLoop(openLoopId: String, accumulator: DeleteAccumulator) {
        dao.openLoop(openLoopId) ?: return
        invalidateArtifacts(dao.derivedArtifactIdsForOpenLoop(openLoopId), accumulator)
        dao.deleteDerivedOpenLoopDependencies(openLoopId)
        dao.deleteOpenLoopAudits(openLoopId)
        dao.deleteOpenLoop(openLoopId)
        accumulator.deletedOpenLoopIds += openLoopId
        accumulator.queueRepair(
            RepairJobType.PROPAGATE_DELETION,
            TARGET_OPEN_LOOP,
            openLoopId,
        )
    }

    private fun invalidateMessageDependencies(messageId: String, accumulator: DeleteAccumulator) {
        invalidateArtifacts(dao.derivedArtifactIdsForMessage(messageId), accumulator)
        dao.deleteDerivedMessageDependencies(messageId)
    }

    private fun invalidateArtifacts(artifactIds: List<String>, accumulator: DeleteAccumulator) {
        val newArtifactIds = artifactIds.filterNot { it in accumulator.invalidatedArtifactIds }
        if (newArtifactIds.isEmpty()) return
        dao.updateDerivedArtifactStates(
            artifactIds = newArtifactIds,
            state = DerivedArtifactState.INVALIDATED,
            invalidatedAt = accumulator.occurredAt,
        )
        newArtifactIds.forEach { artifactId ->
            accumulator.invalidatedArtifactIds += artifactId
            accumulator.queueRepair(
                RepairJobType.INVALIDATE_DERIVED,
                TARGET_DERIVED_ARTIFACT,
                artifactId,
            )
        }
    }

    private fun ensureDeleteTombstone(
        evidence: MemoryEvidenceEntity,
        occurredAt: Long,
    ): SuppressionTombstoneReference {
        val sourceLineageHash = evidence.lineageHash()
        val existing = dao.suppressionTombstone(sourceLineageHash)
        val tombstone = if (existing == null) {
            SuppressionTombstoneEntity(
                id = idGenerator.nextId(),
                kind = SuppressionKind.DELETE,
                sourceLineageHash = sourceLineageHash,
                isActive = true,
                createdAt = occurredAt,
                expiresAt = null,
                formatVersion = TOMBSTONE_FORMAT_VERSION,
            ).also(dao::insertSuppressionTombstone)
        } else {
            existing.copy(
                kind = SuppressionKind.DELETE,
                isActive = true,
                expiresAt = null,
            ).also { updated ->
                if (updated != existing) dao.updateSuppressionTombstone(updated)
            }
        }
        return SuppressionTombstoneReference(tombstone.id, tombstone.sourceLineageHash)
    }

    private fun MemoryEvidenceEntity.lineageHash(): String {
        val canonicalLineage = "$experienceId:$lineageKey"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalLineage.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun requireExperience(experienceId: String) =
        dao.experience(experienceId) ?: abort(SafeDeleteError.MissingExperience(experienceId))

    private suspend fun executeMemoryDelete(
        block: suspend () -> MemoryDeleteResult.Deleted,
    ): MemoryDeleteResult = try {
        database.withTransaction { block() }
    } catch (abort: SafeDeleteAbort) {
        MemoryDeleteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        MemoryDeleteResult.Failure(
            SafeDeleteError.StorageFailure(
                SafeDeleteOperation.DELETE_MEMORY,
                constraint::class.java.simpleName,
            ),
        )
    } catch (failure: Exception) {
        MemoryDeleteResult.Failure(
            SafeDeleteError.StorageFailure(
                SafeDeleteOperation.DELETE_MEMORY,
                failure::class.java.simpleName,
            ),
        )
    }

    private suspend fun executeTimelineDelete(
        block: suspend () -> TimelineDeleteResult.Deleted,
    ): TimelineDeleteResult = try {
        database.withTransaction { block() }
    } catch (abort: SafeDeleteAbort) {
        TimelineDeleteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        TimelineDeleteResult.Failure(
            SafeDeleteError.StorageFailure(
                SafeDeleteOperation.DELETE_TIMELINE_MESSAGE,
                constraint::class.java.simpleName,
            ),
        )
    } catch (failure: Exception) {
        TimelineDeleteResult.Failure(
            SafeDeleteError.StorageFailure(
                SafeDeleteOperation.DELETE_TIMELINE_MESSAGE,
                failure::class.java.simpleName,
            ),
        )
    }

    private fun DeleteAccumulator.queueRepair(
        jobType: RepairJobType,
        targetType: String,
        targetId: String,
    ) {
        val key = RepairKey(jobType, targetType, targetId)
        if (!repairKeys.add(key)) return
        dao.insertRepairJob(
            RepairJobEntity(
                id = idGenerator.nextId(),
                jobType = jobType,
                state = RepairJobState.PENDING,
                targetType = targetType,
                targetId = targetId,
                attemptCount = 0,
                createdAt = occurredAt,
                updatedAt = occurredAt,
            ),
        )
    }

    private fun abort(error: SafeDeleteError): Nothing = throw SafeDeleteAbort(error)

    private class SafeDeleteAbort(val error: SafeDeleteError) : RuntimeException()

    private data class TimelineDeletePlan(
        val conversation: ConversationEntity,
        val head: ConversationTimelineHeadEntity,
        val messageId: String,
        val resultingHeadMessageId: String?,
        val nextRevision: Long,
        val deletedExperienceIds: Set<String>,
        val changedExperienceIds: Set<String>,
        val openLoopIdsToDelete: Set<String>,
        val openLoopRollbacks: List<OpenLoopRollback>,
    )

    private data class OpenLoopRollback(
        val openLoopId: String,
        val experienceId: String,
        val priorState: OpenLoopState,
    )

    private data class RepairKey(
        val jobType: RepairJobType,
        val targetType: String,
        val targetId: String,
    )

    private class DeleteAccumulator(val occurredAt: Long) {
        val invalidatedArtifactIds = linkedSetOf<String>()
        val deletedExperienceIds = linkedSetOf<String>()
        val deletedCandidateIds = linkedSetOf<String>()
        val deletedMemoryIds = linkedSetOf<String>()
        val deletedOpenLoopIds = linkedSetOf<String>()
        val reassessedMemoryIds = linkedSetOf<String>()
        val tombstones = linkedSetOf<SuppressionTombstoneReference>()
        val repairKeys = linkedSetOf<RepairKey>()
    }

    private companion object {
        const val TOMBSTONE_FORMAT_VERSION = 1
        const val TARGET_MESSAGE = "MESSAGE"
        const val TARGET_EXPERIENCE = "EXPERIENCE"
        const val TARGET_CANDIDATE_MEMORY = "CANDIDATE_MEMORY"
        const val TARGET_MEMORY = "MEMORY"
        const val TARGET_OPEN_LOOP = "OPEN_LOOP"
        const val TARGET_DERIVED_ARTIFACT = "DERIVED_ARTIFACT"

        val UNRESOLVED_OPEN_LOOP_STATES = setOf(
            OpenLoopState.PLANNED,
            OpenLoopState.ACTIVE,
            OpenLoopState.WAITING,
            OpenLoopState.BLOCKED,
        )
    }
}
