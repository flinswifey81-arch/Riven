package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "conversation_drafts",
    primaryKeys = ["conversation_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationDraftEntity(
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    val content: String,
    val revision: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "draft_attachments",
    primaryKeys = ["conversation_id", "attachment_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationDraftEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["attachment_id"],
            childColumns = ["attachment_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["attachment_id"]),
        Index(value = ["conversation_id", "attachment_order"], unique = true),
    ],
)
data class DraftAttachmentEntity(
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "attachment_id")
    val attachmentId: String,
    @ColumnInfo(name = "attachment_order")
    val attachmentOrder: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
