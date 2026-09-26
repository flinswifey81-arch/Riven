package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.GeneratedMediaProvenanceEntity
import com.shai.riven.data.persistence.entity.MessageAttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAttachment(attachment: AttachmentEntity)

    @Update
    fun updateAttachment(attachment: AttachmentEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertGeneratedMediaProvenance(provenance: GeneratedMediaProvenanceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMessageAttachment(association: MessageAttachmentEntity)

    @Query("SELECT * FROM attachments WHERE attachment_id = :attachmentId")
    fun attachment(attachmentId: String): AttachmentEntity?

    @Query(
        """
        SELECT *
        FROM attachments
        WHERE state = :deletePendingState
           OR (state = :stagingState AND updated_at <= :staleBefore)
        ORDER BY
            CASE WHEN state = :deletePendingState THEN 0 ELSE 1 END,
            updated_at,
            attachment_id
        LIMIT :limit
        """,
    )
    fun maintenanceCandidates(
        deletePendingState: AttachmentState,
        stagingState: AttachmentState,
        staleBefore: Long,
        limit: Int,
    ): List<AttachmentEntity>

    @Query("SELECT * FROM generated_media_provenance WHERE attachment_id = :attachmentId")
    fun generatedMediaProvenance(attachmentId: String): GeneratedMediaProvenanceEntity?

    @Query(
        """
        SELECT attachments.*
        FROM message_attachments
        INNER JOIN attachments
            ON attachments.attachment_id = message_attachments.attachment_id
        WHERE message_attachments.message_id = :messageId
          AND attachments.state = 'AVAILABLE'
        ORDER BY message_attachments.attachment_order
        """,
    )
    fun availableAttachmentsForMessage(messageId: String): List<AttachmentEntity>

    @Query(
        """
        SELECT attachment_id
        FROM message_attachments
        WHERE message_id = :messageId
        ORDER BY attachment_order
        """,
    )
    fun attachmentIdsForMessage(messageId: String): List<String>

    @Query("SELECT COUNT(*) FROM message_attachments WHERE attachment_id = :attachmentId")
    fun messageReferenceCount(attachmentId: String): Int

    @Query(
        """
        DELETE FROM attachments
        WHERE attachment_id = :attachmentId
          AND state = :requiredState
          AND NOT EXISTS (
              SELECT 1 FROM message_attachments
              WHERE message_attachments.attachment_id = attachments.attachment_id
          )
        """,
    )
    fun deleteUnreferencedAttachmentInState(
        attachmentId: String,
        requiredState: AttachmentState,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM derived_artifact_attachment_dependencies WHERE attachment_id = :attachmentId",
    )
    fun derivedArtifactDependencyCount(attachmentId: String): Int
}
