package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactAttachmentDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.MemoryAuditHistoryEntity
import com.shai.riven.data.persistence.entity.RepairJobEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.RepairJobType

@Dao
interface MaintenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSuppressionTombstone(tombstone: SuppressionTombstoneEntity)

    @Update
    fun updateSuppressionTombstone(tombstone: SuppressionTombstoneEntity)

    @Query("SELECT * FROM suppression_tombstones WHERE source_lineage_hash = :sourceLineageHash LIMIT 1")
    fun suppressionTombstone(sourceLineageHash: String): SuppressionTombstoneEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifact(artifact: DerivedArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactMemoryDependency(dependency: DerivedArtifactMemoryDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactExperienceDependency(dependency: DerivedArtifactExperienceDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactMessageDependency(dependency: DerivedArtifactMessageDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactOpenLoopDependency(dependency: DerivedArtifactOpenLoopDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDerivedArtifactAttachmentDependency(dependency: DerivedArtifactAttachmentDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRepairJob(job: RepairJobEntity)

    @Query("SELECT * FROM repair_jobs WHERE repair_job_id = :repairJobId")
    fun repairJob(repairJobId: String): RepairJobEntity?

    @Query(
        """
        SELECT *
        FROM repair_jobs
        WHERE state = :pendingState
          AND job_type IN (:supportedTypes)
        ORDER BY created_at, repair_job_id
        LIMIT :limit
        """,
    )
    fun pendingRepairJobsForTypes(
        pendingState: RepairJobState,
        supportedTypes: List<RepairJobType>,
        limit: Int,
    ): List<RepairJobEntity>

    @Query(
        """
        UPDATE repair_jobs
        SET state = :runningState,
            attempt_count = attempt_count + 1,
            updated_at = :claimedAt,
            last_error_code = NULL
        WHERE repair_job_id = :repairJobId
          AND state = :pendingState
          AND attempt_count = :expectedAttemptCount
        """,
    )
    // A successful claim clears the prior attempt's code; only a completed failure writes a new code.
    fun claimPendingRepairJob(
        repairJobId: String,
        pendingState: RepairJobState,
        runningState: RepairJobState,
        expectedAttemptCount: Int,
        claimedAt: Long,
    ): Int

    @Query(
        """
        UPDATE repair_jobs
        SET attempt_count = attempt_count + 1,
            updated_at = :claimedAt,
            last_error_code = NULL
        WHERE repair_job_id = :repairJobId
          AND state = :runningState
          AND attempt_count = :expectedAttemptCount
          AND updated_at = :expectedUpdatedAt
          AND updated_at <= :staleBefore
        """,
    )
    // Reclaim follows the same error-code policy as a fresh claim.
    fun reclaimStaleRunningRepairJob(
        repairJobId: String,
        runningState: RepairJobState,
        expectedAttemptCount: Int,
        expectedUpdatedAt: Long,
        staleBefore: Long,
        claimedAt: Long,
    ): Int

    @Query(
        """
        UPDATE repair_jobs
        SET state = :newState,
            updated_at = :updatedAt,
            last_error_code = :lastErrorCode
        WHERE repair_job_id = :repairJobId
          AND state = :runningState
          AND attempt_count = :expectedAttemptCount
          AND updated_at = :claimedAt
        """,
    )
    fun finishClaimedRepairJob(
        repairJobId: String,
        runningState: RepairJobState,
        newState: RepairJobState,
        expectedAttemptCount: Int,
        claimedAt: Long,
        updatedAt: Long,
        lastErrorCode: String?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMemoryAuditHistory(auditHistory: MemoryAuditHistoryEntity)

    @Query("SELECT COUNT(*) FROM derived_artifact_memory_dependencies WHERE derived_artifact_id = :artifactId")
    fun memoryDependencyCount(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM derived_artifact_experience_dependencies WHERE derived_artifact_id = :artifactId")
    fun experienceDependencyCount(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM derived_artifact_message_dependencies WHERE derived_artifact_id = :artifactId")
    fun messageDependencyCount(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM derived_artifact_attachment_dependencies WHERE derived_artifact_id = :artifactId")
    fun attachmentDependencyCount(artifactId: String): Int

    @Query("SELECT derived_artifact_id FROM derived_artifact_attachment_dependencies WHERE attachment_id = :attachmentId")
    fun derivedArtifactIdsForAttachment(attachmentId: String): List<String>

    @Query("DELETE FROM derived_artifact_attachment_dependencies WHERE attachment_id = :attachmentId")
    fun deleteDerivedArtifactAttachmentDependencies(attachmentId: String): Int

    @Query("SELECT * FROM derived_artifacts WHERE derived_artifact_id = :artifactId")
    fun derivedArtifact(artifactId: String): DerivedArtifactEntity?

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state, invalidated_at = :invalidatedAt
        WHERE derived_artifact_id IN (
            SELECT derived_artifact_id
            FROM derived_artifact_memory_dependencies
            WHERE memory_id IN (:memoryIds)
        )
        """,
    )
    fun markMemoryDerivedArtifacts(
        memoryIds: List<String>,
        state: DerivedArtifactState,
        invalidatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state, invalidated_at = :invalidatedAt
        WHERE derived_artifact_id IN (
            SELECT derived_artifact_id
            FROM derived_artifact_experience_dependencies
            WHERE experience_id IN (:experienceIds)
        )
        """,
    )
    fun markExperienceDerivedArtifacts(
        experienceIds: List<String>,
        state: DerivedArtifactState,
        invalidatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state, invalidated_at = :invalidatedAt
        WHERE derived_artifact_id IN (
            SELECT derived_artifact_id
            FROM derived_artifact_message_dependencies
            WHERE message_id IN (:messageIds)
        )
        """,
    )
    fun markMessageDerivedArtifacts(
        messageIds: List<String>,
        state: DerivedArtifactState,
        invalidatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE derived_artifacts
        SET state = :state, invalidated_at = :invalidatedAt
        WHERE derived_artifact_id IN (
            SELECT derived_artifact_id
            FROM derived_artifact_open_loop_dependencies
            WHERE open_loop_id IN (:openLoopIds)
        )
        """,
    )
    fun markOpenLoopDerivedArtifacts(
        openLoopIds: List<String>,
        state: DerivedArtifactState,
        invalidatedAt: Long,
    ): Int

    @Query("SELECT * FROM memory_audit_history WHERE memory_id = :memoryId ORDER BY occurred_at, memory_audit_id")
    fun memoryAuditHistory(memoryId: String): List<MemoryAuditHistoryEntity>

    @Query("SELECT * FROM suppression_tombstones ORDER BY created_at, tombstone_id")
    fun suppressionTombstones(): List<SuppressionTombstoneEntity>

    @Query("SELECT * FROM repair_jobs WHERE target_type = :targetType AND target_id = :targetId ORDER BY created_at, repair_job_id")
    fun repairJobs(targetType: String, targetId: String): List<RepairJobEntity>
}
