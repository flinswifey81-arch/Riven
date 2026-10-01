package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger

@Entity(
    tableName = "conversation_runs",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["user_message_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["assistant_message_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["user_message_id"]),
        Index(value = ["assistant_message_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["active_conversation_id"], unique = true),
        Index(value = ["owner_session_token"]),
        Index(value = ["retry_of_run_id"]),
        Index(value = ["regenerate_of_message_id"]),
    ],
)
data class ConversationRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id")
    val runId: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "user_message_id")
    val userMessageId: String,
    @ColumnInfo(name = "assistant_message_id")
    val assistantMessageId: String,
    val trigger: ConversationRunTrigger,
    @ColumnInfo(name = "retry_of_run_id")
    val retryOfRunId: String? = null,
    @ColumnInfo(name = "regenerate_of_message_id")
    val regenerateOfMessageId: String? = null,
    val state: ConversationRunState,
    @ColumnInfo(name = "active_conversation_id")
    val activeConversationId: String?,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "input_fingerprint")
    val inputFingerprint: String,
    @ColumnInfo(name = "owner_session_token")
    val ownerSessionToken: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "profile_revision")
    val profileRevision: Long? = null,
    @ColumnInfo(name = "adapter_id")
    val adapterId: String? = null,
    @ColumnInfo(name = "endpoint_base_url")
    val endpointBaseUrl: String? = null,
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "selected_head_message_id")
    val selectedHeadMessageId: String?,
    @ColumnInfo(name = "context_head_message_id")
    val contextHeadMessageId: String,
    @ColumnInfo(name = "reserved_timeline_revision")
    val reservedTimelineRevision: Long,
    @ColumnInfo(name = "provider_request_id")
    val providerRequestId: String? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
)
