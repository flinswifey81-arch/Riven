package com.shai.riven.data.conversation

import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole

enum class TimelineOperation {
    CREATE_CONVERSATION,
    APPEND_MESSAGE,
    READ_ACTIVE_TIMELINE,
    READ_ALL_MESSAGES,
    REWIND,
    REGENERATE_ASSISTANT,
    VALIDATE_PARENT_ASSIGNMENT,
}

enum class InvalidRegenerationReason {
    ORIGINAL_NOT_ASSISTANT,
    ORIGINAL_NOT_ACTIVE_HEAD,
    REPLACEMENT_NOT_ASSISTANT,
    ROOT_ALTERNATIVE_UNSUPPORTED,
}

sealed interface ConversationTimelineError {
    data class MissingConversation(val conversationId: String) : ConversationTimelineError
    data class DuplicateConversationId(val conversationId: String) : ConversationTimelineError
    data class MissingTimelineHeadRecord(val conversationId: String) : ConversationTimelineError
    data class MissingMessage(val messageId: String) : ConversationTimelineError
    data class DuplicateMessageId(val messageId: String) : ConversationTimelineError
    data class MessageBelongsToDifferentConversation(
        val messageId: String,
        val expectedConversationId: String,
        val actualConversationId: String,
    ) : ConversationTimelineError

    data class TimelineHeadBelongsToDifferentConversation(
        val conversationId: String,
        val headMessageId: String,
        val actualConversationId: String,
    ) : ConversationTimelineError

    data class MessageNotOnActiveTimeline(
        val conversationId: String,
        val messageId: String,
    ) : ConversationTimelineError

    data class InvalidRegenerationTarget(
        val messageId: String,
        val reason: InvalidRegenerationReason,
    ) : ConversationTimelineError

    data class SelfParent(val messageId: String) : ConversationTimelineError
    data class ParentBelongsToDifferentConversation(
        val childMessageId: String,
        val parentMessageId: String,
    ) : ConversationTimelineError

    data class ParentAlreadyAssigned(
        val childMessageId: String,
        val existingParentMessageId: String,
    ) : ConversationTimelineError

    data class CycleDetected(
        val childMessageId: String,
        val parentMessageId: String,
    ) : ConversationTimelineError

    data class StaleTimelineRevision(
        val expected: Long,
        val actual: Long,
    ) : ConversationTimelineError

    data class InvalidTimelineRevision(
        val conversationId: String,
        val actual: Long,
    ) : ConversationTimelineError

    data class TimelineRevisionOverflow(val conversationId: String) : ConversationTimelineError
    data class SequenceCollision(
        val conversationId: String,
        val sequenceNumber: Long,
    ) : ConversationTimelineError

    data class StorageFailure(
        val operation: TimelineOperation,
        val causeType: String,
    ) : ConversationTimelineError
}

data class CreateTimelineConversationInput(
    val conversationId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ConversationStatus,
    val title: String? = null,
)

data class NewTimelineMessageInput(
    val messageId: String,
    val role: MessageRole,
    val deliveryState: MessageDeliveryState,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val providerName: String? = null,
    val providerModel: String? = null,
    val providerRequestId: String? = null,
    val errorCode: String? = null,
)

data class AppendTimelineMessageInput(
    val conversationId: String,
    val message: NewTimelineMessageInput,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
)

data class RewindTimelineInput(
    val conversationId: String,
    val targetMessageId: String,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
)

data class CommitRegeneratedAssistantResponseInput(
    val conversationId: String,
    val originalMessageId: String,
    val replacement: NewTimelineMessageInput,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
)

sealed interface TimelineWriteResult {
    data class ConversationCreated(
        val conversationId: String,
        val timelineRevision: Long,
    ) : TimelineWriteResult

    data class MessageAppended(
        val conversationId: String,
        val messageId: String,
        val sequenceNumber: Long,
        val timelineRevision: Long,
    ) : TimelineWriteResult

    data class Rewound(
        val conversationId: String,
        val activeHeadMessageId: String,
        val deactivatedMessageIds: List<String>,
        val timelineRevision: Long,
    ) : TimelineWriteResult

    data class AlreadyAtRewindTarget(
        val conversationId: String,
        val activeHeadMessageId: String,
        val timelineRevision: Long,
    ) : TimelineWriteResult

    data class AssistantRegenerated(
        val conversationId: String,
        val deactivatedMessageId: String,
        val activatedMessageId: String,
        val sequenceNumber: Long,
        val timelineRevision: Long,
    ) : TimelineWriteResult

    data class ParentAssignmentValidated(
        val childMessageId: String,
        val parentMessageId: String,
    ) : TimelineWriteResult

    data class Failure(val error: ConversationTimelineError) : TimelineWriteResult
}

sealed interface TimelineReadResult {
    data class Success(
        val conversationId: String,
        val timelineRevision: Long,
        val messages: List<MessageEntity>,
    ) : TimelineReadResult

    data class Failure(val error: ConversationTimelineError) : TimelineReadResult
}
