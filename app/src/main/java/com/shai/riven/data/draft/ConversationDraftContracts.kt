package com.shai.riven.data.draft

import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.conversation.ConversationTimelineError
import com.shai.riven.data.persistence.model.AttachmentState

const val MAX_DRAFT_CONTENT_CHARS = 262_144

enum class ConversationDraftOperation {
    READ_DRAFT,
    SAVE_DRAFT,
    CLEAR_DRAFT,
    COMMIT_DRAFT_AS_USER_MESSAGE,
}

data class ConversationDraftSnapshot(
    val conversationId: String,
    val content: String,
    val attachmentIds: List<String>,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SaveConversationDraftInput(
    val conversationId: String,
    val content: String,
    val attachmentIds: List<String>,
    val expectedRevision: Long,
    val occurredAt: Long,
)

data class ClearConversationDraftInput(
    val conversationId: String,
    val expectedRevision: Long,
    val occurredAt: Long,
)

data class CommitDraftAsUserMessageInput(
    val conversationId: String,
    val messageId: String,
    val expectedDraftRevision: Long,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
)

sealed interface ConversationDraftError {
    data class MissingConversation(val conversationId: String) : ConversationDraftError
    data class MissingTimelineHead(val conversationId: String) : ConversationDraftError
    data class NoDraft(val conversationId: String) : ConversationDraftError
    data class StaleDraftRevision(val expected: Long, val actual: Long) : ConversationDraftError
    data class DraftRevisionOverflow(val conversationId: String) : ConversationDraftError
    data class DraftContentTooLong(
        val maximumChars: Int,
        val actualChars: Int,
    ) : ConversationDraftError

    data class MissingAttachment(val attachmentId: String) : ConversationDraftError
    data class AttachmentUnavailable(
        val attachmentId: String,
        val state: AttachmentState,
    ) : ConversationDraftError

    data class DuplicateAttachmentReference(val attachmentId: String) : ConversationDraftError
    data class EmptyDraft(val conversationId: String) : ConversationDraftError
    data class TimelineWriteFailure(val error: ConversationTimelineError) : ConversationDraftError
    data class StorageFailure(
        val operation: ConversationDraftOperation,
        val causeType: String,
    ) : ConversationDraftError
}

enum class DraftCleanupSchedulingStatus {
    NOT_REQUIRED,
    ENQUEUED,
    PARTIAL_FAILURE,
    FAILED,
}

data class DraftCleanupSchedulingSummary(
    val status: DraftCleanupSchedulingStatus,
    val attemptedCount: Int,
    val enqueuedCount: Int,
    val failedCount: Int,
)

fun interface DraftAttachmentCleanupScheduler {
    fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult
}

sealed interface ReadConversationDraftResult {
    data class Draft(val snapshot: ConversationDraftSnapshot) : ReadConversationDraftResult
    data class NoDraft(val conversationId: String) : ReadConversationDraftResult
    data class Failure(val error: ConversationDraftError) : ReadConversationDraftResult
}

sealed interface SaveConversationDraftResult {
    data class Saved(
        val conversationId: String,
        val revision: Long,
        val attachmentCleanup: DraftCleanupSchedulingSummary,
    ) : SaveConversationDraftResult

    data class Failure(val error: ConversationDraftError) : SaveConversationDraftResult
}

sealed interface ClearConversationDraftResult {
    data class Cleared(
        val conversationId: String,
        val clearedRevision: Long,
        val attachmentCleanup: DraftCleanupSchedulingSummary,
    ) : ClearConversationDraftResult

    data class AlreadyClear(val conversationId: String) : ClearConversationDraftResult
    data class Failure(val error: ConversationDraftError) : ClearConversationDraftResult
}

sealed interface CommitDraftAsUserMessageResult {
    data class MessageCommitted(
        val conversationId: String,
        val messageId: String,
        val sequenceNumber: Long,
        val timelineRevision: Long,
    ) : CommitDraftAsUserMessageResult

    data class Failure(val error: ConversationDraftError) : CommitDraftAsUserMessageResult
}
