package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ConversationRunEntity

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
}
