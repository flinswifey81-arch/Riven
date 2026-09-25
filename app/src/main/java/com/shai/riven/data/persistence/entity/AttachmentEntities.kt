package com.shai.riven.data.persistence.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.GeneratedMediaKind

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["storage_key"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "attachment_id")
    val id: String,
    val kind: AttachmentKind,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    val state: AttachmentState,
    @ColumnInfo(name = "storage_key")
    val storageKey: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long? = null,
    @ColumnInfo(name = "content_sha256")
    val contentSha256: String? = null,
    val source: AttachmentSource,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "message_attachments",
    primaryKeys = ["message_id", "attachment_id"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
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
        Index(value = ["message_id", "attachment_order"], unique = true),
    ],
)
data class MessageAttachmentEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "attachment_id")
    val attachmentId: String,
    @ColumnInfo(name = "attachment_order")
    val attachmentOrder: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "generated_media_provenance",
    foreignKeys = [
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["attachment_id"],
            childColumns = ["attachment_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class GeneratedMediaProvenanceEntity(
    @PrimaryKey
    @ColumnInfo(name = "attachment_id")
    val attachmentId: String,
    @ColumnInfo(name = "generation_kind")
    val generationKind: GeneratedMediaKind,
    @ColumnInfo(name = "generator_provider")
    val generatorProvider: String? = null,
    @ColumnInfo(name = "generator_model")
    val generatorModel: String? = null,
    @ColumnInfo(name = "provider_request_id")
    val providerRequestId: String? = null,
    @ColumnInfo(name = "appearance_authority")
    val appearanceAuthority: String? = null,
    @ColumnInfo(name = "appearance_authority_fingerprint")
    val appearanceAuthorityFingerprint: String? = null,
    @ColumnInfo(name = "request_fingerprint")
    val requestFingerprint: String? = null,
    @ColumnInfo(name = "generated_at")
    val generatedAt: Long,
)

@Entity(
    tableName = "derived_artifact_attachment_dependencies",
    primaryKeys = ["derived_artifact_id", "attachment_id"],
    foreignKeys = [
        ForeignKey(
            entity = DerivedArtifactEntity::class,
            parentColumns = ["derived_artifact_id"],
            childColumns = ["derived_artifact_id"],
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
    indices = [Index(value = ["attachment_id"])],
)
data class DerivedArtifactAttachmentDependencyEntity(
    @ColumnInfo(name = "derived_artifact_id")
    val derivedArtifactId: String,
    @ColumnInfo(name = "attachment_id")
    val attachmentId: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
