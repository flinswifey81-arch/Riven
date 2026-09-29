package com.shai.riven.data.experience

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.conversation.ConversationTimelineError
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import kotlinx.coroutines.CancellationException

/**
 * Records canonical transcript evidence only. The fixed [SensitivityLevel.STANDARD] default is
 * deliberately conservative and non-semantic; sensitivity classification is outside this feature.
 */
class ConversationExperienceService(
    private val database: RivenDatabase,
    private val idGenerator: ConversationExperienceIdGenerator = UuidConversationExperienceIdGenerator,
    private val afterExperienceInserted: (String) -> Unit = {},
) {
    private val memoryDao = database.memoryDao()
    private val timelineDao = database.conversationTimelineDao()
    private val orderAllocator = ExperienceOrderAllocator(memoryDao)

    suspend fun conversationExperienceForMessage(messageId: String): ConversationExperienceLookupResult = try {
        database.withTransaction {
            val message = timelineDao.message(messageId)
            if (message == null) {
                return@withTransaction ConversationExperienceLookupResult.Failure(
                    ConversationExperienceError.MissingMessage(messageId),
                )
            }
            canonicalExperienceOrNull(message, mapping(message))
                ?.let(ConversationExperienceLookupResult::Found)
                ?: ConversationExperienceLookupResult.NotRecorded(messageId)
        }
    } catch (abort: ConversationExperienceAbort) {
        ConversationExperienceLookupResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ConversationExperienceLookupResult.Failure(
            ConversationExperienceError.StorageFailure(
                ConversationExperienceOperation.FIND_FOR_MESSAGE,
                failure::class.java.simpleName,
            ),
        )
    }

    suspend fun ensureActiveConversationExperiences(
        conversationId: String,
        recordedAt: Long,
        limit: Int,
    ): EnsureConversationExperiencesResult {
        if (limit !in 1..MAX_ACTIVE_EXPERIENCE_CATCH_UP_CREATIONS) {
            return EnsureConversationExperiencesResult.Failure(ConversationExperienceError.InvalidLimit(limit))
        }
        return try {
            database.withTransaction {
                val timeline = try {
                    ConversationTimelineService(database).activeTimelineInCurrentTransaction(conversationId)
                } catch (abort: ConversationTimelineService.TimelineAbort) {
                    throw ConversationExperienceAbort(mapTimelineError(conversationId, abort.error))
                }
                val created = mutableListOf<String>()
                val existing = mutableListOf<String>()
                var inspectedMessageCount = 0
                var truncated = false
                for (message in timeline.messages) {
                    inspectedMessageCount += 1
                    val expected = mapping(message)
                    val alreadyRecorded = canonicalExperienceOrNull(message, expected)
                    if (alreadyRecorded != null) {
                        if (existing.size < limit) existing += alreadyRecorded.id
                        continue
                    }
                    if (expected == null) continue
                    if (created.size == limit) {
                        truncated = true
                        break
                    }
                    created += insertCanonicalExperience(message, recordedAt, expected).id
                }
                EnsureConversationExperiencesResult.Ensured(
                    conversationId = conversationId,
                    createdExperienceIds = created,
                    alreadyRecordedExperienceIds = existing,
                    inspectedMessageCount = inspectedMessageCount,
                    truncated = truncated,
                )
            }
        } catch (abort: ConversationExperienceAbort) {
            EnsureConversationExperiencesResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            EnsureConversationExperiencesResult.Failure(
                ConversationExperienceError.StorageFailure(
                    ConversationExperienceOperation.ENSURE_ACTIVE_CONVERSATION,
                    failure::class.java.simpleName,
                ),
            )
        }
    }

    internal fun recordMessageInCurrentTransaction(
        message: MessageEntity,
        recordedAt: Long,
    ): ConversationExperienceRecordResult {
        val mapping = mapping(message)
        canonicalExperienceOrNull(message, mapping)?.let {
            return ConversationExperienceRecordResult.AlreadyRecorded(it)
        }
        if (mapping == null) return ConversationExperienceRecordResult.NotEligible(message.id)
        return ConversationExperienceRecordResult.Created(
            insertCanonicalExperience(message, recordedAt, mapping),
        )
    }

    private fun insertCanonicalExperience(
        message: MessageEntity,
        recordedAt: Long,
        mapping: Pair<ExperienceType, ExperienceActor>,
    ): ExperienceEntity {
        val eventOrder = try {
            orderAllocator.next()
        } catch (_: ExperienceOrderOverflowException) {
            throw ConversationExperienceAbort(ConversationExperienceError.ExperienceOrderOverflow)
        }
        val experienceId = try {
            idGenerator.nextExperienceId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ConversationExperienceAbort(
                ConversationExperienceError.StorageFailure(
                    ConversationExperienceOperation.ENSURE_ACTIVE_CONVERSATION,
                    failure::class.java.simpleName,
                ),
            )
        }
        val experience = ExperienceEntity(
            id = experienceId,
            eventOrder = eventOrder,
            experienceType = mapping.first,
            actor = mapping.second,
            actorEntityId = null,
            sourceContent = message.content,
            occurredAt = message.createdAt,
            recordedAt = recordedAt,
            sensitivity = SensitivityLevel.STANDARD,
            availability = ExperienceAvailability.AVAILABLE,
        )
        try {
            memoryDao.insertExperience(experience)
            memoryDao.insertExperienceMessageSource(
                ExperienceMessageSourceEntity(
                    experienceId = experience.id,
                    messageId = message.id,
                    sourceOrder = 0,
                    sourceRole = ExperienceMessageSourceRole.PRIMARY,
                    characterStart = null,
                    characterEnd = null,
                    createdAt = recordedAt,
                ),
            )
            afterExperienceInserted(message.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (constraint: SQLiteConstraintException) {
            throw ConversationExperienceAbort(
                ConversationExperienceError.StorageFailure(
                    ConversationExperienceOperation.ENSURE_ACTIVE_CONVERSATION,
                    constraint::class.java.simpleName,
                ),
            )
        }
        return experience
    }

    private fun canonicalExperiences(messageId: String): List<ExperienceEntity> =
        memoryDao.canonicalConversationExperiencesForMessage(messageId)

    private fun canonicalExperienceOrNull(
        message: MessageEntity,
        expected: Pair<ExperienceType, ExperienceActor>?,
    ): ExperienceEntity? {
        val experiences = canonicalExperiences(message.id)
        if (experiences.size > 1) {
            throw ConversationExperienceAbort(
                ConversationExperienceError.DuplicateCanonicalExperience(message.id),
            )
        }
        return experiences.singleOrNull()?.also { experience ->
            if (expected == null) {
                throw ConversationExperienceAbort(
                    ConversationExperienceError.InvalidCanonicalExperience(
                        message.id,
                        InvalidCanonicalExperienceReason.MESSAGE_NOT_ELIGIBLE,
                    ),
                )
            }
            validateCanonicalExperience(message, experience, expected)
        }
    }

    private fun validateCanonicalExperience(
        message: MessageEntity,
        experience: ExperienceEntity,
        expected: Pair<ExperienceType, ExperienceActor>,
    ) {
        val reason = when {
            experience.experienceType != expected.first -> InvalidCanonicalExperienceReason.TYPE_MISMATCH
            experience.actor != expected.second -> InvalidCanonicalExperienceReason.ACTOR_MISMATCH
            experience.sourceContent != message.content -> InvalidCanonicalExperienceReason.SOURCE_CONTENT_MISMATCH
            experience.occurredAt != message.createdAt -> InvalidCanonicalExperienceReason.OCCURRED_AT_MISMATCH
            experience.actorEntityId != null -> InvalidCanonicalExperienceReason.ACTOR_ENTITY_MISMATCH
            experience.sensitivity != SensitivityLevel.STANDARD -> InvalidCanonicalExperienceReason.SENSITIVITY_MISMATCH
            else -> {
                val sources = memoryDao.messageSourcesForExperience(experience.id)
                if (sources.size != 1) {
                    InvalidCanonicalExperienceReason.SOURCE_COUNT_MISMATCH
                } else {
                    val source = sources.single()
                    if (source.messageId != message.id ||
                        source.sourceOrder != 0 ||
                        source.sourceRole != ExperienceMessageSourceRole.PRIMARY ||
                        source.characterStart != null ||
                        source.characterEnd != null ||
                        source.createdAt != experience.recordedAt
                    ) {
                        InvalidCanonicalExperienceReason.SOURCE_SHAPE_MISMATCH
                    } else {
                        null
                    }
                }
            }
        }
        if (reason != null) {
            throw ConversationExperienceAbort(
                ConversationExperienceError.InvalidCanonicalExperience(message.id, reason),
            )
        }
    }

    private fun mapping(message: MessageEntity): Pair<ExperienceType, ExperienceActor>? {
        if (message.deliveryState != MessageDeliveryState.PERSISTED &&
            message.deliveryState != MessageDeliveryState.SUCCEEDED
        ) {
            return null
        }
        return when (message.role) {
            MessageRole.USER -> ExperienceType.CONVERSATION_MESSAGE to ExperienceActor.SHAI
            MessageRole.ASSISTANT -> ExperienceType.CONVERSATION_MESSAGE to ExperienceActor.RIVEN
            MessageRole.TOOL -> ExperienceType.TOOL_RESULT to ExperienceActor.TOOL
            MessageRole.SYSTEM -> null
        }
    }

    private fun mapTimelineError(
        conversationId: String,
        error: ConversationTimelineError,
    ): ConversationExperienceError = when (error) {
        is ConversationTimelineError.MissingConversation ->
            ConversationExperienceError.MissingConversation(conversationId)
        is ConversationTimelineError.MissingTimelineHeadRecord ->
            ConversationExperienceError.MissingTimelineHead(conversationId)
        else -> ConversationExperienceError.StorageFailure(
            ConversationExperienceOperation.ENSURE_ACTIVE_CONVERSATION,
            error::class.java.simpleName,
        )
    }
}
