package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.DraftAttachmentEntity

@Dao
interface ConversationDraftDao {
    @Query("SELECT * FROM conversation_drafts WHERE conversation_id = :conversationId")
    fun draft(conversationId: String): ConversationDraftEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDraft(draft: ConversationDraftEntity)

    @Update
    fun updateDraft(draft: ConversationDraftEntity): Int

    @Query("DELETE FROM conversation_drafts WHERE conversation_id = :conversationId")
    fun deleteDraft(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDraftAttachment(attachment: DraftAttachmentEntity)

    @Query("DELETE FROM draft_attachments WHERE conversation_id = :conversationId")
    fun deleteDraftAttachmentsForConversation(conversationId: String): Int

    @Query(
        """
        SELECT attachment_id
        FROM draft_attachments
        WHERE conversation_id = :conversationId
        ORDER BY attachment_order
        """,
    )
    fun attachmentIdsForDraft(conversationId: String): List<String>

    @Query("SELECT COUNT(*) FROM draft_attachments WHERE attachment_id = :attachmentId")
    fun draftReferenceCount(attachmentId: String): Int

    @Query("SELECT * FROM conversation_drafts ORDER BY conversation_id")
    fun allDrafts(): List<ConversationDraftEntity>

    @Query(
        """
        SELECT * FROM draft_attachments
        WHERE conversation_id = :conversationId
        ORDER BY attachment_order
        """,
    )
    fun allAttachmentsForDraft(conversationId: String): List<DraftAttachmentEntity>
}
