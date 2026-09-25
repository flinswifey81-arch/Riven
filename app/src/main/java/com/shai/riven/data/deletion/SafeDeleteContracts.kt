package com.shai.riven.data.deletion

enum class SafeDeleteOperation {
    DELETE_MEMORY,
    DELETE_TIMELINE_MESSAGE,
}

sealed interface SafeDeleteError {
    data class MissingConversation(val conversationId: String) : SafeDeleteError
    data class MissingTimelineHeadRecord(val conversationId: String) : SafeDeleteError
    data class MissingMessage(val messageId: String) : SafeDeleteError
    data class MessageBelongsToDifferentConversation(
        val messageId: String,
        val expectedConversationId: String,
        val actualConversationId: String,
    ) : SafeDeleteError

    data class TimelineHeadBelongsToDifferentConversation(
        val conversationId: String,
        val headMessageId: String,
        val actualConversationId: String,
    ) : SafeDeleteError

    data class StaleTimelineRevision(
        val expected: Long,
        val actual: Long,
    ) : SafeDeleteError

    data class InvalidTimelineRevision(
        val conversationId: String,
        val actual: Long,
    ) : SafeDeleteError

    data class TimelineRevisionOverflow(val conversationId: String) : SafeDeleteError
    data class MessageHasChildren(
        val messageId: String,
        val childCount: Int,
    ) : SafeDeleteError

    data class MissingMemory(val memoryId: String) : SafeDeleteError
    data class MissingExperience(val experienceId: String) : SafeDeleteError
    data class AmbiguousProvenanceForDeletion(
        val messageId: String,
        val experienceId: String,
    ) : SafeDeleteError

    data class AmbiguousOpenLoopRollback(
        val openLoopId: String,
        val experienceId: String,
    ) : SafeDeleteError

    data class StorageFailure(
        val operation: SafeDeleteOperation,
        val causeType: String,
    ) : SafeDeleteError
}

data class DeleteMemoryInput(
    val memoryId: String,
    val occurredAt: Long,
)

data class DeleteTimelineMessageInput(
    val conversationId: String,
    val messageId: String,
    val expectedTimelineRevision: Long,
    val occurredAt: Long,
)

data class SuppressionTombstoneReference(
    val tombstoneId: String,
    val sourceLineageHash: String,
)

sealed interface MemoryDeleteResult {
    data class Deleted(
        val deletedMemoryId: String,
        val neighboringMemoryIdsRequiringReassessment: Set<String>,
        val invalidatedDerivedArtifactIds: Set<String>,
        val suppressionTombstones: Set<SuppressionTombstoneReference>,
    ) : MemoryDeleteResult

    data class Failure(val error: SafeDeleteError) : MemoryDeleteResult
}

sealed interface TimelineDeleteResult {
    data class Deleted(
        val conversationId: String,
        val deletedMessageId: String,
        val activeHeadMessageId: String?,
        val timelineRevision: Long,
        val deletedExperienceIds: Set<String>,
        val deletedCandidateMemoryIds: Set<String>,
        val deletedMemoryIds: Set<String>,
        val deletedOpenLoopIds: Set<String>,
        val invalidatedDerivedArtifactIds: Set<String>,
    ) : TimelineDeleteResult

    data class Failure(val error: SafeDeleteError) : TimelineDeleteResult
}

fun interface SafeDeleteIdGenerator {
    fun nextId(): String
}
