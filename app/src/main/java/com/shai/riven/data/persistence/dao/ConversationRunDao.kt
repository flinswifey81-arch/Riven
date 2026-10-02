package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.ConversationRunState

@Dao
interface ConversationRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(run: ConversationRunEntity)

    @Update
    fun update(run: ConversationRunEntity): Int

    @Query("SELECT * FROM conversation_runs WHERE run_id = :runId")
    fun run(runId: String): ConversationRunEntity?

    @Query("SELECT * FROM conversation_runs WHERE idempotency_key = :idempotencyKey")
    fun runByIdempotencyKey(idempotencyKey: String): ConversationRunEntity?

    @Query("SELECT * FROM conversation_runs WHERE active_conversation_id = :conversationId")
    fun activeRun(conversationId: String): ConversationRunEntity?

    @Query(
        """
        SELECT * FROM conversation_runs
        WHERE active_conversation_id IS NOT NULL
        ORDER BY created_at, run_id
        """,
    )
    fun activeRuns(): List<ConversationRunEntity>

    @Query("SELECT * FROM conversation_runs WHERE conversation_id = :conversationId ORDER BY created_at, run_id")
    fun runsForConversation(conversationId: String): List<ConversationRunEntity>

    @Query("SELECT COUNT(*) FROM conversation_runs")
    fun count(): Int

    @Query(
        """
        SELECT * FROM conversation_runs
        WHERE state = :succeededState
          AND (
              NOT EXISTS (
                  SELECT 1 FROM automatic_memory_jobs
                  WHERE automatic_memory_jobs.source_message_id = conversation_runs.user_message_id
              )
              AND EXISTS (
                  SELECT 1 FROM experiences
                  INNER JOIN experience_message_sources
                      ON experience_message_sources.experience_id = experiences.experience_id
                  WHERE experience_message_sources.message_id = conversation_runs.user_message_id
                    AND experiences.experience_type IN ('CONVERSATION_MESSAGE', 'TOOL_RESULT')
                    AND experiences.availability = 'AVAILABLE'
              )
              OR NOT EXISTS (
                  SELECT 1 FROM automatic_memory_jobs
                  WHERE automatic_memory_jobs.source_message_id = conversation_runs.assistant_message_id
              )
              AND EXISTS (
                  SELECT 1 FROM experiences
                  INNER JOIN experience_message_sources
                      ON experience_message_sources.experience_id = experiences.experience_id
                  WHERE experience_message_sources.message_id = conversation_runs.assistant_message_id
                    AND experiences.experience_type IN ('CONVERSATION_MESSAGE', 'TOOL_RESULT')
                    AND experiences.availability = 'AVAILABLE'
              )
          )
        ORDER BY finished_at, run_id
        LIMIT :limit
        """,
    )
    fun succeededRunsMissingAutomaticMemoryJobs(
        succeededState: ConversationRunState,
        limit: Int,
    ): List<ConversationRunEntity>
}
