package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity

@Dao
interface ConversationTimelineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTimelineHead(head: ConversationTimelineHeadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertParentEdge(edge: MessageParentEdgeEntity)

    @Update
    fun updateConversation(conversation: ConversationEntity)

    @Update
    fun updateTimelineHead(head: ConversationTimelineHeadEntity)

    @Query("SELECT * FROM conversations WHERE conversation_id = :conversationId")
    fun conversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversation_timeline_heads WHERE conversation_id = :conversationId")
    fun timelineHead(conversationId: String): ConversationTimelineHeadEntity?

    @Query("SELECT * FROM messages WHERE message_id = :messageId")
    fun message(messageId: String): MessageEntity?

    @Query("SELECT * FROM message_parent_edges WHERE child_message_id = :childMessageId")
    fun parentEdge(childMessageId: String): MessageParentEdgeEntity?

    @Query("SELECT MAX(sequence_number) FROM messages WHERE conversation_id = :conversationId")
    fun maximumSequenceNumber(conversationId: String): Long?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY sequence_number
        """,
    )
    fun allMessages(conversationId: String): List<MessageEntity>

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE conversation_id = :conversationId AND sequence_number = :sequenceNumber
        """,
    )
    fun sequenceNumberCount(conversationId: String, sequenceNumber: Long): Int
}
