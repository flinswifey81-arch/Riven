package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState

/**
 * Durable, resumable processing state for one canonical conversation Experience.
 *
 * The source message is unique so Retry can create a new assistant job without reprocessing the
 * same user message. [sourceTimelineRevision] records the branch revision that admitted the work;
 * every semantic stage still performs its own current active-parent-walk fence before committing.
 */
@Entity(
    tableName = "automatic_memory_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationRunEntity::class,
            parentColumns = ["run_id"],
            childColumns = ["originating_run_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["source_message_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["source_experience_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["originating_run_id"]),
        Index(value = ["source_message_id"], unique = true),
        Index(value = ["source_experience_id"], unique = true),
        Index(value = ["state", "updated_at"]),
    ],
)
data class AutomaticMemoryJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "automatic_memory_job_id")
    val id: String,
    @ColumnInfo(name = "originating_run_id")
    val originatingRunId: String,
    @ColumnInfo(name = "source_message_id")
    val sourceMessageId: String,
    @ColumnInfo(name = "source_experience_id")
    val sourceExperienceId: String,
    @ColumnInfo(name = "source_timeline_revision")
    val sourceTimelineRevision: Long,
    val state: AutomaticMemoryJobState,
    @ColumnInfo(name = "next_stage")
    val nextStage: AutomaticMemoryJobStage,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String? = null,
)
