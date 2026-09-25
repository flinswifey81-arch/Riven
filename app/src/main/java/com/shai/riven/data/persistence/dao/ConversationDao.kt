package com.shai.riven.data.persistence.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.MessageEntity

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY sequence_number")
    fun messagesForConversation(conversationId: String): List<MessageEntity>

    @Delete
    fun deleteMessage(message: MessageEntity)
}
