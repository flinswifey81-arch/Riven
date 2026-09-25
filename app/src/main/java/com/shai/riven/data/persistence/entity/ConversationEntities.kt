package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["created_at"])],
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val id: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    val status: ConversationStatus,
    val title: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "sequence_number"], unique = true),
        Index(value = ["created_at"]),
        Index(value = ["provider_request_id"]),
    ],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val id: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Long,
    val role: MessageRole,
    @ColumnInfo(name = "delivery_state")
    val deliveryState: MessageDeliveryState,
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "provider_name")
    val providerName: String? = null,
    @ColumnInfo(name = "provider_model")
    val providerModel: String? = null,
    @ColumnInfo(name = "provider_request_id")
    val providerRequestId: String? = null,
    @ColumnInfo(name = "error_code")
    val errorCode: String? = null,
)
