package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.AutomaticMemoryJobEntity
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState

@Dao
interface AutomaticMemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertJob(job: AutomaticMemoryJobEntity)

    @Query("SELECT * FROM automatic_memory_jobs WHERE automatic_memory_job_id = :jobId")
    fun job(jobId: String): AutomaticMemoryJobEntity?

    @Query("SELECT * FROM automatic_memory_jobs WHERE source_message_id = :messageId")
    fun jobForMessage(messageId: String): AutomaticMemoryJobEntity?

    @Query(
        "SELECT * FROM automatic_memory_jobs " +
            "WHERE state = :state ORDER BY updated_at, automatic_memory_job_id LIMIT :limit",
    )
    fun jobsInState(
        state: AutomaticMemoryJobState,
        limit: Int,
    ): List<AutomaticMemoryJobEntity>

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET state = :runningState,
            attempt_count = attempt_count + 1,
            updated_at = :claimedAt,
            last_error_code = NULL
        WHERE automatic_memory_job_id = :jobId
          AND state = :pendingState
          AND attempt_count = :expectedAttemptCount
        """,
    )
    fun claimPending(
        jobId: String,
        pendingState: AutomaticMemoryJobState,
        runningState: AutomaticMemoryJobState,
        expectedAttemptCount: Int,
        claimedAt: Long,
    ): Int

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET attempt_count = attempt_count + 1,
            updated_at = :claimedAt,
            last_error_code = NULL
        WHERE automatic_memory_job_id = :jobId
          AND state = :runningState
          AND attempt_count = :expectedAttemptCount
          AND updated_at = :expectedUpdatedAt
          AND updated_at <= :staleBefore
        """,
    )
    fun reclaimStaleRunning(
        jobId: String,
        runningState: AutomaticMemoryJobState,
        expectedAttemptCount: Int,
        expectedUpdatedAt: Long,
        staleBefore: Long,
        claimedAt: Long,
    ): Int

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET next_stage = :nextStage,
            updated_at = :updatedAt
        WHERE automatic_memory_job_id = :jobId
          AND state = :runningState
          AND next_stage = :expectedStage
          AND attempt_count = :expectedAttemptCount
        """,
    )
    fun advanceStage(
        jobId: String,
        runningState: AutomaticMemoryJobState,
        expectedStage: AutomaticMemoryJobStage,
        nextStage: AutomaticMemoryJobStage,
        expectedAttemptCount: Int,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET state = :newState,
            next_stage = :nextStage,
            updated_at = :updatedAt,
            last_error_code = :lastErrorCode
        WHERE automatic_memory_job_id = :jobId
          AND state = :runningState
          AND attempt_count = :expectedAttemptCount
        """,
    )
    fun finishClaimed(
        jobId: String,
        runningState: AutomaticMemoryJobState,
        newState: AutomaticMemoryJobState,
        nextStage: AutomaticMemoryJobStage,
        expectedAttemptCount: Int,
        updatedAt: Long,
        lastErrorCode: String?,
    ): Int

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET state = :excludedState,
            next_stage = :completeStage,
            updated_at = :updatedAt,
            last_error_code = :reasonCode
        WHERE source_message_id = :messageId
          AND state != :excludedState
        """,
    )
    fun excludeForMessage(
        messageId: String,
        excludedState: AutomaticMemoryJobState,
        completeStage: AutomaticMemoryJobStage,
        updatedAt: Long,
        reasonCode: String,
    ): Int

    @Query(
        """
        UPDATE automatic_memory_jobs
        SET state = :pendingState,
            updated_at = :updatedAt,
            last_error_code = :lastErrorCode
        WHERE automatic_memory_job_id = :jobId
          AND state = :runningState
          AND attempt_count = :expectedAttemptCount
        """,
    )
    fun releaseForRetry(
        jobId: String,
        runningState: AutomaticMemoryJobState,
        pendingState: AutomaticMemoryJobState,
        expectedAttemptCount: Int,
        updatedAt: Long,
        lastErrorCode: String,
    ): Int

    @Query("SELECT COUNT(*) FROM automatic_memory_jobs WHERE state = :state")
    fun countInState(state: AutomaticMemoryJobState): Int

    @Query(
        "SELECT last_error_code FROM automatic_memory_jobs " +
            "WHERE last_error_code IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
    )
    fun latestErrorCode(): String?
}
