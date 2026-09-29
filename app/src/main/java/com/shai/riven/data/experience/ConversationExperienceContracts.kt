package com.shai.riven.data.experience

import com.shai.riven.data.persistence.entity.ExperienceEntity
import java.util.UUID

const val MAX_ACTIVE_EXPERIENCE_CATCH_UP_CREATIONS = 500

enum class ConversationExperienceOperation {
    FIND_FOR_MESSAGE,
    ENSURE_ACTIVE_CONVERSATION,
}

enum class InvalidCanonicalExperienceReason {
    MESSAGE_NOT_ELIGIBLE,
    TYPE_MISMATCH,
    ACTOR_MISMATCH,
    SOURCE_CONTENT_MISMATCH,
    OCCURRED_AT_MISMATCH,
    ACTOR_ENTITY_MISMATCH,
    SOURCE_COUNT_MISMATCH,
    SOURCE_SHAPE_MISMATCH,
    SENSITIVITY_MISMATCH,
}

sealed interface ConversationExperienceError {
    data class MissingMessage(val messageId: String) : ConversationExperienceError
    data class MissingConversation(val conversationId: String) : ConversationExperienceError
    data class MissingTimelineHead(val conversationId: String) : ConversationExperienceError
    data class MessageNotEligible(val messageId: String) : ConversationExperienceError
    data class DuplicateCanonicalExperience(val messageId: String) : ConversationExperienceError
    data class InvalidCanonicalExperience(
        val messageId: String,
        val reason: InvalidCanonicalExperienceReason,
    ) : ConversationExperienceError
    data object ExperienceOrderOverflow : ConversationExperienceError
    data class InvalidLimit(val limit: Int) : ConversationExperienceError
    data class StorageFailure(
        val operation: ConversationExperienceOperation,
        val causeType: String,
    ) : ConversationExperienceError
}

sealed interface ConversationExperienceLookupResult {
    data class Found(val experience: ExperienceEntity) : ConversationExperienceLookupResult
    data class NotRecorded(val messageId: String) : ConversationExperienceLookupResult
    data class Failure(val error: ConversationExperienceError) : ConversationExperienceLookupResult
}

sealed interface EnsureConversationExperiencesResult {
    /**
     * [inspectedMessageCount] is the number of active Messages examined while finding missing
     * eligible work. It can exceed the requested creation limit because ineligible and already
     * recorded Messages are skipped. Returned ID collections contain at most `limit` entries each.
     */
    data class Ensured(
        val conversationId: String,
        val createdExperienceIds: List<String>,
        val alreadyRecordedExperienceIds: List<String>,
        val inspectedMessageCount: Int,
        val truncated: Boolean,
    ) : EnsureConversationExperiencesResult

    data class Failure(val error: ConversationExperienceError) : EnsureConversationExperiencesResult
}

interface ConversationExperienceIdGenerator {
    fun nextExperienceId(): String
}

object UuidConversationExperienceIdGenerator : ConversationExperienceIdGenerator {
    override fun nextExperienceId(): String = UUID.randomUUID().toString()
}

internal sealed interface ConversationExperienceRecordResult {
    data class Created(val experience: ExperienceEntity) : ConversationExperienceRecordResult
    data class AlreadyRecorded(val experience: ExperienceEntity) : ConversationExperienceRecordResult
    data class NotEligible(val messageId: String) : ConversationExperienceRecordResult
}

internal class ConversationExperienceAbort(
    val error: ConversationExperienceError,
) : RuntimeException()
