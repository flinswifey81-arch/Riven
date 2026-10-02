package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ConsolidationCheckpointEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactAttachmentDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactPayloadEntity
import com.shai.riven.data.persistence.entity.MemoryAccessibilityEntity
import com.shai.riven.data.persistence.entity.MemoryAgingSweepCheckpointEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.entity.OpenLoopPassCheckpointEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.OpenLoopState

@Dao
interface MemoryLifecycleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAccessibility(value: MemoryAccessibilityEntity)

    @Query("SELECT * FROM memory_accessibility WHERE memory_id = :memoryId")
    fun accessibility(memoryId: String): MemoryAccessibilityEntity?

    @Query("DELETE FROM memory_accessibility WHERE memory_id = :memoryId")
    fun deleteAccessibility(memoryId: String): Int

    @Query(
        """
        SELECT DISTINCT memories.* FROM memories
        INNER JOIN memory_evidence ON memory_evidence.memory_id = memories.memory_id
        INNER JOIN experiences ON experiences.experience_id = memory_evidence.experience_id
        WHERE memories.truth_state = 'SUPPORTED'
          AND memories.retention_state != 'FORGOTTEN'
          AND memories.lifecycle_state = 'VALIDATED'
          AND memories.temporal_state IN ('CURRENT', 'ATEMPORAL', 'UNKNOWN')
          AND memories.epistemic_basis != 'CONSOLIDATION'
          AND memory_evidence.role = 'SUPPORTS'
          AND experiences.availability = 'AVAILABLE'
        ORDER BY memories.updated_at DESC, memories.memory_id
        LIMIT :limit
        """,
    )
    fun eligibleSourceMemories(limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT memory_evidence.* FROM memory_evidence
        INNER JOIN experiences ON experiences.experience_id = memory_evidence.experience_id
        WHERE memory_evidence.memory_id IN (:memoryIds)
          AND experiences.availability = 'AVAILABLE'
        ORDER BY memory_evidence.memory_id, memory_evidence.experience_id
        LIMIT :limit
        """,
    )
    fun availableEvidence(memoryIds: List<String>, limit: Int): List<MemoryEvidenceEntity>

    @Query(
        """
        SELECT DISTINCT memories.memory_id FROM memories
        INNER JOIN memory_evidence ON memory_evidence.memory_id = memories.memory_id
        INNER JOIN experiences ON experiences.experience_id = memory_evidence.experience_id
        WHERE memories.memory_id > :afterMemoryId
          AND memories.truth_state = 'SUPPORTED'
          AND memories.retention_state != 'FORGOTTEN'
          AND memories.lifecycle_state = 'VALIDATED'
          AND experiences.availability = 'AVAILABLE'
        ORDER BY memories.memory_id
        LIMIT :limit
        """,
    )
    fun agingMemoryIds(afterMemoryId: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAgingSweepCheckpoint(value: MemoryAgingSweepCheckpointEntity): Long

    @Query("SELECT * FROM memory_aging_sweep_checkpoints WHERE checkpoint_id = :checkpointId")
    fun agingSweepCheckpoint(checkpointId: String): MemoryAgingSweepCheckpointEntity?

    @Query(
        """
        UPDATE memory_aging_sweep_checkpoints
        SET after_memory_id = :nextAfterMemoryId, updated_at = :updatedAt
        WHERE checkpoint_id = :checkpointId
          AND sweep_started_at = :sweepStartedAt
          AND after_memory_id = :expectedAfterMemoryId
        """,
    )
    fun advanceAgingSweepCheckpoint(
        checkpointId: String,
        sweepStartedAt: Long,
        expectedAfterMemoryId: String,
        nextAfterMemoryId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        DELETE FROM memory_aging_sweep_checkpoints
        WHERE checkpoint_id = :checkpointId
          AND sweep_started_at = :sweepStartedAt
          AND after_memory_id = :expectedAfterMemoryId
        """,
    )
    fun finishAgingSweepCheckpoint(
        checkpointId: String,
        sweepStartedAt: Long,
        expectedAfterMemoryId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertConsolidationCheckpoint(value: ConsolidationCheckpointEntity)

    @Query(
        "SELECT * FROM consolidation_checkpoints " +
            "WHERE corpus_fingerprint = :fingerprint AND source_set_hash IS NULL LIMIT 1",
    )
    fun consolidationCompletionForCorpus(fingerprint: String): ConsolidationCheckpointEntity?

    @Query(
        "SELECT * FROM consolidation_checkpoints " +
            "WHERE corpus_fingerprint = :fingerprint AND source_set_hash IS NOT NULL " +
            "ORDER BY created_at, checkpoint_id",
    )
    fun consolidationCheckpointsForCorpus(fingerprint: String): List<ConsolidationCheckpointEntity>

    @Query(
        "SELECT * FROM consolidation_checkpoints WHERE source_set_hash = :sourceSetHash " +
            "ORDER BY created_at, checkpoint_id LIMIT 1",
    )
    fun consolidationCheckpointForSourceSet(sourceSetHash: String): ConsolidationCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertOpenLoopCheckpoint(value: OpenLoopPassCheckpointEntity)

    @Query("SELECT * FROM open_loop_pass_checkpoints WHERE experience_id = :experienceId")
    fun openLoopCheckpoint(experienceId: String): OpenLoopPassCheckpointEntity?

    @Query(
        """
        SELECT open_loops.* FROM open_loops
        INNER JOIN experiences ON experiences.experience_id = open_loops.creation_experience_id
        WHERE open_loops.state IN (:states)
          AND experiences.availability = 'AVAILABLE'
        ORDER BY open_loops.updated_at DESC, open_loops.open_loop_id
        LIMIT :limit
        """,
    )
    fun unresolvedOpenLoops(states: List<OpenLoopState>, limit: Int): List<OpenLoopEntity>

    @Query(
        """
        SELECT open_loops.* FROM open_loops
        INNER JOIN experiences ON experiences.experience_id = open_loops.creation_experience_id
        WHERE open_loops.state IN (:states)
          AND open_loops.open_loop_id > :afterOpenLoopId
          AND experiences.availability = 'AVAILABLE'
        ORDER BY open_loops.open_loop_id
        LIMIT :limit
        """,
    )
    fun unresolvedOpenLoopPage(
        states: List<OpenLoopState>,
        afterOpenLoopId: String,
        limit: Int,
    ): List<OpenLoopEntity>

    @Query("SELECT * FROM open_loops WHERE open_loop_id = :openLoopId")
    fun openLoop(openLoopId: String): OpenLoopEntity?

    @Update
    fun updateOpenLoop(openLoop: OpenLoopEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDerivedPayload(payload: DerivedArtifactPayloadEntity)

    @Query("SELECT * FROM derived_artifact_payloads WHERE derived_artifact_id = :artifactId")
    fun derivedPayload(artifactId: String): DerivedArtifactPayloadEntity?

    @Query("DELETE FROM derived_artifact_payloads WHERE derived_artifact_id IN (:artifactIds)")
    fun deleteDerivedPayloads(artifactIds: List<String>): Int

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state,
            source_revision = :sourceRevision,
            artifact_hash = :artifactHash,
            invalidated_at = NULL
        WHERE derived_artifact_id = :artifactId
        """,
    )
    fun finishDerivedRebuild(
        artifactId: String,
        state: DerivedArtifactState,
        sourceRevision: Long,
        artifactHash: String,
    ): Int

    @Query("SELECT * FROM derived_artifact_memory_dependencies WHERE derived_artifact_id = :artifactId ORDER BY memory_id")
    fun artifactMemoryDependencies(artifactId: String): List<DerivedArtifactMemoryDependencyEntity>

    @Query("SELECT * FROM derived_artifact_experience_dependencies WHERE derived_artifact_id = :artifactId ORDER BY experience_id")
    fun artifactExperienceDependencies(artifactId: String): List<DerivedArtifactExperienceDependencyEntity>

    @Query("SELECT * FROM derived_artifact_open_loop_dependencies WHERE derived_artifact_id = :artifactId ORDER BY open_loop_id")
    fun artifactOpenLoopDependencies(artifactId: String): List<DerivedArtifactOpenLoopDependencyEntity>

    @Query("SELECT * FROM derived_artifact_message_dependencies WHERE derived_artifact_id = :artifactId ORDER BY message_id")
    fun artifactMessageDependencies(artifactId: String): List<DerivedArtifactMessageDependencyEntity>

    @Query("SELECT * FROM derived_artifact_attachment_dependencies WHERE derived_artifact_id = :artifactId ORDER BY attachment_id")
    fun artifactAttachmentDependencies(artifactId: String): List<DerivedArtifactAttachmentDependencyEntity>

    @Query("SELECT derived_artifact_id FROM derived_artifact_memory_dependencies WHERE memory_id = :targetId ORDER BY derived_artifact_id")
    fun artifactIdsForMemory(targetId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_experience_dependencies WHERE experience_id = :targetId ORDER BY derived_artifact_id")
    fun artifactIdsForExperience(targetId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_message_dependencies WHERE message_id = :targetId ORDER BY derived_artifact_id")
    fun artifactIdsForMessage(targetId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_open_loop_dependencies WHERE open_loop_id = :targetId ORDER BY derived_artifact_id")
    fun artifactIdsForOpenLoop(targetId: String): List<String>

    @Query("SELECT derived_artifact_id FROM derived_artifact_attachment_dependencies WHERE attachment_id = :targetId ORDER BY derived_artifact_id")
    fun artifactIdsForAttachment(targetId: String): List<String>

    @Query("DELETE FROM derived_artifact_payloads WHERE derived_artifact_id IN (SELECT derived_artifact_id FROM derived_artifact_memory_dependencies WHERE memory_id IN (:memoryIds))")
    fun deleteDerivedPayloadsForMemories(memoryIds: List<String>): Int

    @Query("DELETE FROM derived_artifact_payloads WHERE derived_artifact_id IN (SELECT derived_artifact_id FROM derived_artifact_experience_dependencies WHERE experience_id IN (:experienceIds))")
    fun deleteDerivedPayloadsForExperiences(experienceIds: List<String>): Int

    @Query("DELETE FROM derived_artifact_payloads WHERE derived_artifact_id IN (SELECT derived_artifact_id FROM derived_artifact_message_dependencies WHERE message_id IN (:messageIds))")
    fun deleteDerivedPayloadsForMessages(messageIds: List<String>): Int

    @Query("DELETE FROM derived_artifact_payloads WHERE derived_artifact_id IN (SELECT derived_artifact_id FROM derived_artifact_open_loop_dependencies WHERE open_loop_id IN (:openLoopIds))")
    fun deleteDerivedPayloadsForOpenLoops(openLoopIds: List<String>): Int

    @Query(
        """
        SELECT messages.* FROM messages
        WHERE messages.message_id = :messageId
          AND EXISTS (
              SELECT 1 FROM experience_message_sources
              INNER JOIN experiences
                  ON experiences.experience_id = experience_message_sources.experience_id
              WHERE experience_message_sources.message_id = messages.message_id
                AND experiences.availability = 'AVAILABLE'
          )
        """,
    )
    fun eligibleArtifactMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM attachments WHERE attachment_id = :attachmentId AND state = 'AVAILABLE'")
    fun eligibleArtifactAttachment(attachmentId: String): AttachmentEntity?
}
