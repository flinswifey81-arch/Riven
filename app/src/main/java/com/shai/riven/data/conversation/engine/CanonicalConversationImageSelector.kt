package com.shai.riven.data.conversation.engine

import com.shai.riven.data.context.ActiveConversationContextSource
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole

/**
 * Selects the only images that may accompany a canonical run: images on the current user turn,
 * or, for a text-only follow-up, images on its immediately preceding persisted user turn.
 */
internal class CanonicalConversationImageSelector(
    private val database: RivenDatabase,
) {
    private val timeline = ConversationTimelineService(database)

    suspend fun select(
        conversationId: String,
        userMessageId: String,
        contextHeadMessageId: String,
    ): Map<String, List<String>> {
        val currentIds = database.attachmentDao().attachmentIdsForMessage(userMessageId)
        if (currentIds.isNotEmpty()) return mapOf(userMessageId to currentIds)
        val tail = timeline.activeTimelineTailEndingAt(
            conversationId = conversationId,
            contextHeadMessageId = contextHeadMessageId,
            maximumMessages = ActiveConversationContextSource.MAX_TRANSCRIPT_MESSAGES,
        ) as? TimelineReadResult.Success ?: return emptyMap()
        val currentIndex = tail.messages.indexOfLast { it.id == userMessageId }
        if (currentIndex <= 0) return emptyMap()
        val previousUser = tail.messages.subList(0, currentIndex)
            .lastOrNull { message ->
                message.role == MessageRole.USER &&
                    message.deliveryState == MessageDeliveryState.PERSISTED
            } ?: return emptyMap()
        val previousIds = database.attachmentDao().attachmentIdsForMessage(previousUser.id)
        return if (previousIds.isEmpty()) emptyMap() else mapOf(previousUser.id to previousIds)
    }
}
