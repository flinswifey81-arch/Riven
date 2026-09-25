package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.entity.OpenLoopAuditHistoryEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.MemoryRelationshipType

@Dao
interface SafeDeleteDao {
    @Query("SELECT * FROM conversations WHERE conversation_id = :conversationId")
    fun conversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversation_timeline_heads WHERE conversation_id = :conversationId")
    fun timelineHead(conversationId: String): ConversationTimelineHeadEntity?

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    fun message(messageId: String): MessageEntity?

    @Query("SELECT * FROM message_parent_edges WHERE child_message_id = :childMessageId")
    fun parentEdge(childMessageId: String): MessageParentEdgeEntity?

    @Query("SELECT COUNT(*) FROM message_parent_edges WHERE parent_message_id = :messageId")
    fun childMessageCount(messageId: String): Int

    @Update
    fun updateConversation(conversation: ConversationEntity)

    @Update
    fun updateTimelineHead(head: ConversationTimelineHeadEntity)

    @Query("DELETE FROM messages WHERE message_id = :messageId")
    fun deleteMessage(messageId: String): Int

    @Query("SELECT * FROM experience_message_sources WHERE message_id = :messageId ORDER BY experience_id")
    fun messageSourcesForMessage(messageId: String): List<ExperienceMessageSourceEntity>

    @Query("SELECT * FROM experience_message_sources WHERE experience_id = :experienceId ORDER BY source_order")
    fun messageSourcesForExperience(experienceId: String): List<ExperienceMessageSourceEntity>

    @Query("SELECT * FROM experiences WHERE experience_id = :experienceId")
    fun experience(experienceId: String): ExperienceEntity?

    @Update
    fun updateExperience(experience: ExperienceEntity)

    @Query("DELETE FROM experience_message_sources WHERE experience_id = :experienceId AND message_id = :messageId")
    fun deleteExperienceMessageSource(experienceId: String, messageId: String): Int

    @Query("DELETE FROM experiences WHERE experience_id = :experienceId")
    fun deleteExperience(experienceId: String): Int

    @Query("SELECT * FROM candidate_memory_evidence WHERE experience_id = :experienceId")
    fun candidateEvidenceForExperience(experienceId: String): List<CandidateMemoryEvidenceEntity>

    @Query("SELECT * FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId ORDER BY evidence_order")
    fun candidateEvidenceForCandidate(candidateMemoryId: String): List<CandidateMemoryEvidenceEntity>

    @Query("SELECT * FROM candidate_memories WHERE candidate_memory_id = :candidateMemoryId")
    fun candidateMemory(candidateMemoryId: String): CandidateMemoryEntity?

    @Update
    fun updateCandidateMemory(candidateMemory: CandidateMemoryEntity)

    @Query("DELETE FROM candidate_memory_evidence WHERE candidate_memory_id = :candidateMemoryId AND experience_id = :experienceId")
    fun deleteCandidateEvidence(candidateMemoryId: String, experienceId: String): Int

    @Query("DELETE FROM candidate_memories WHERE candidate_memory_id = :candidateMemoryId")
    fun deleteCandidateMemory(candidateMemoryId: String): Int

    @Query("SELECT * FROM memory_evidence WHERE experience_id = :experienceId")
    fun memoryEvidenceForExperience(experienceId: String): List<MemoryEvidenceEntity>

    @Query("SELECT * FROM memory_evidence WHERE memory_id = :memoryId ORDER BY created_at, experience_id")
    fun evidenceForMemory(memoryId: String): List<MemoryEvidenceEntity>

    @Query("DELETE FROM memory_evidence WHERE memory_id = :memoryId AND experience_id = :experienceId")
    fun deleteMemoryEvidence(memoryId: String, experienceId: String): Int

    @Query("SELECT * FROM memories WHERE memory_id = :memoryId")
    fun memory(memoryId: String): MemoryEntity?

    @Query("DELETE FROM memories WHERE memory_id = :memoryId")
    fun deleteMemory(memoryId: String): Int

    @Query(
        """
        SELECT * FROM memory_relationships
        WHERE source_memory_id = :memoryId OR target_memory_id = :memoryId
        """,
    )
    fun relationshipsForMemory(memoryId: String): List<MemoryRelationshipEntity>

    @Query("SELECT * FROM memory_relationships WHERE created_by_experience_id = :experienceId")
    fun relationshipsForExperience(experienceId: String): List<MemoryRelationshipEntity>

    @Delete
    fun deleteMemoryRelationship(relationship: MemoryRelationshipEntity)

    @Query("SELECT * FROM memory_audit_history WHERE memory_id = :memoryId")
    fun memoryAuditsForMemory(memoryId: String): List<MemoryAuditHistoryEntity>

    @Query("SELECT * FROM memory_audit_history WHERE triggering_experience_id = :experienceId")
    fun memoryAuditsForExperience(experienceId: String): List<MemoryAuditHistoryEntity>

    @Query("DELETE FROM memory_audit_history WHERE memory_id = :memoryId")
    fun deleteMemoryAuditsForMemory(memoryId: String): Int

    @Query("DELETE FROM memory_audit_history WHERE triggering_experience_id = :experienceId")
    fun deleteMemoryAuditsForExperience(experienceId: String): Int

    @Query("SELECT * FROM open_loops WHERE related_memory_id = :memoryId")
    fun openLoopsForMemory(memoryId: String): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE creation_experience_id = :experienceId")
    fun openLoopsCreatedByExperience(experienceId: String): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE resolution_experience_id = :experienceId")
    fun openLoopsResolvedByExperience(experienceId: String): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE open_loop_id = :openLoopId")
    fun openLoop(openLoopId: String): OpenLoopEntity?

    @Update
    fun updateOpenLoop(openLoop: OpenLoopEntity)

    @Query("DELETE FROM open_loops WHERE open_loop_id = :openLoopId")
    fun deleteOpenLoop(openLoopId: String): Int

    @Query("SELECT * FROM open_loop_audit_history WHERE open_loop_id = :openLoopId ORDER BY occurred_at, open_loop_audit_id")
    fun openLoopAudits(openLoopId: String): List<OpenLoopAuditHistoryEntity>

    @Query("SELECT * FROM open_loop_audit_history WHERE triggering_experience_id = :experienceId")
    fun openLoopAuditsForExperience(experienceId: String): List<OpenLoopAuditHistoryEntity>

    @Query(
        """
        SELECT * FROM open_loop_audit_history
        WHERE open_loop_id = :openLoopId AND triggering_experience_id = :experienceId
        ORDER BY occurred_at, open_loop_audit_id
        """,
    )
    fun openLoopAuditsForExperience(openLoopId: String, experienceId: String): List<OpenLoopAuditHistoryEntity>

    @Query("DELETE FROM open_loop_audit_history WHERE open_loop_id = :openLoopId")
    fun deleteOpenLoopAudits(openLoopId: String): Int

    @Query("DELETE FROM open_loop_audit_history WHERE triggering_experience_id = :experienceId")
    fun deleteOpenLoopAuditsForExperience(experienceId: String): Int

    @Query("SELECT derived_artifact_id FROM derived_artifact_memory_dependencies WHERE memory_id = :memoryId")
    fun derivedArtifactIdsForMemory(memoryId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_experience_dependencies WHERE experience_id = :experienceId")
    fun derivedArtifactIdsForExperience(experienceId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_message_dependencies WHERE message_id = :messageId")
    fun derivedArtifactIdsForMessage(messageId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_open_loop_dependencies WHERE open_loop_id = :openLoopId")
    fun derivedArtifactIdsForOpenLoop(openLoopId: String): List<String>

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state, invalidated_at = :invalidatedAt
        WHERE derived_artifact_id IN (:artifactIds)
        """,
    )
    fun updateDerivedArtifactStates(
        artifactIds: List<String>,
        state: DerivedArtifactState,
        invalidatedAt: Long,
    ): Int

    @Query("SELECT * FROM derived_artifacts WHERE derived_artifact_id = :artifactId")
    fun derivedArtifact(artifactId: String): DerivedArtifactEntity?

    @Query("DELETE FROM derived_artifact_memory_dependencies WHERE memory_id = :memoryId")
    fun deleteDerivedMemoryDependencies(memoryId: String): Int

    @Query("DELETE FROM derived_artifact_experience_dependencies WHERE experience_id = :experienceId")
    fun deleteDerivedExperienceDependencies(experienceId: String): Int

    @Query("DELETE FROM derived_artifact_message_dependencies WHERE message_id = :messageId")
    fun deleteDerivedMessageDependencies(messageId: String): Int

    @Query("DELETE FROM derived_artifact_open_loop_dependencies WHERE open_loop_id = :openLoopId")
    fun deleteDerivedOpenLoopDependencies(openLoopId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSuppressionTombstone(tombstone: SuppressionTombstoneEntity)

    @Update
    fun updateSuppressionTombstone(tombstone: SuppressionTombstoneEntity)

    @Query("SELECT * FROM suppression_tombstones WHERE source_lineage_hash = :sourceLineageHash LIMIT 1")
    fun suppressionTombstone(sourceLineageHash: String): SuppressionTombstoneEntity?

    @Query("SELECT * FROM suppression_tombstones ORDER BY created_at, tombstone_id")
    fun suppressionTombstones(): List<SuppressionTombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRepairJob(job: RepairJobEntity)

    @Query("SELECT * FROM repair_jobs WHERE target_type = :targetType AND target_id = :targetId ORDER BY created_at, repair_job_id")
    fun repairJobs(targetType: String, targetId: String): List<RepairJobEntity>

    @Query("SELECT COUNT(*) FROM candidate_memory_evidence WHERE experience_id = :experienceId")
    fun candidateEvidenceCountForExperience(experienceId: String): Int

    @Query("SELECT COUNT(*) FROM experience_entity_links WHERE experience_id = :experienceId")
    fun experienceEntityLinkCountForExperience(experienceId: String): Int

    @Query("SELECT COUNT(*) FROM memory_evidence WHERE experience_id = :experienceId")
    fun memoryEvidenceCountForExperience(experienceId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM open_loops
        WHERE creation_experience_id = :experienceId OR resolution_experience_id = :experienceId
        """,
    )
    fun openLoopDependencyCountForExperience(experienceId: String): Int

    @Query("SELECT COUNT(*) FROM memory_relationships WHERE created_by_experience_id = :experienceId")
    fun relationshipCountForExperience(experienceId: String): Int

    @Query("SELECT COUNT(*) FROM memory_audit_history WHERE triggering_experience_id = :experienceId")
    fun memoryAuditCountForExperience(experienceId: String): Int

    @Query("SELECT COUNT(*) FROM open_loop_audit_history WHERE triggering_experience_id = :experienceId")
    fun openLoopAuditCountForExperience(experienceId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM memory_relationships
        WHERE source_memory_id = :sourceMemoryId
          AND target_memory_id = :targetMemoryId
          AND relationship_type = :relationshipType
        """,
    )
    fun memoryRelationshipCount(
        sourceMemoryId: String,
        targetMemoryId: String,
        relationshipType: MemoryRelationshipType,
    ): Int
}
