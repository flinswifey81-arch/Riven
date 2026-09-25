package com.shai.riven.data.conversation

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.model.MessageRole
import kotlinx.coroutines.CancellationException

class ConversationTimelineService(
    private val database: RivenDatabase,
    private val afterMessageGraphWrite: (TimelineOperation) -> Unit = {},
) {
    private val timelineDao = database.conversationTimelineDao()

    suspend fun createConversationWithTimeline(
        input: CreateTimelineConversationInput,
    ): TimelineWriteResult = executeWrite(TimelineOperation.CREATE_CONVERSATION) {
        if (timelineDao.conversation(input.conversationId) != null) {
            abort(ConversationTimelineError.DuplicateConversationId(input.conversationId))
        }
        timelineDao.insertConversation(
            ConversationEntity(
                id = input.conversationId,
                createdAt = input.createdAt,
                updatedAt = input.updatedAt,
                status = input.status,
                title = input.title,
            ),
        )
        timelineDao.insertTimelineHead(
            ConversationTimelineHeadEntity(
                conversationId = input.conversationId,
                activeHeadMessageId = null,
                timelineRevision = INITIAL_TIMELINE_REVISION,
                updatedAt = input.updatedAt,
            ),
        )
        TimelineWriteResult.ConversationCreated(input.conversationId, INITIAL_TIMELINE_REVISION)
    }

    suspend fun appendMessage(input: AppendTimelineMessageInput): TimelineWriteResult =
        executeWrite(TimelineOperation.APPEND_MESSAGE) {
            val conversation = requireConversation(input.conversationId)
            val head = requireTimelineHead(input.conversationId)
            requireExpectedRevision(head, input.expectedTimelineRevision)
            walkActivePath(head)
            requireNewMessageId(input.message.messageId)
            val sequenceNumber = nextSequenceNumber(input.conversationId)
            val message = input.message.toEntity(input.conversationId, sequenceNumber)
            timelineDao.insertMessage(message)
            head.activeHeadMessageId?.let { parentId ->
                insertValidatedParentEdge(message.id, parentId, input.occurredAt)
            }
            afterMessageGraphWrite(TimelineOperation.APPEND_MESSAGE)
            val nextRevision = nextRevision(head)
            timelineDao.updateTimelineHead(
                head.copy(
                    activeHeadMessageId = message.id,
                    timelineRevision = nextRevision,
                    updatedAt = input.occurredAt,
                ),
            )
            timelineDao.updateConversation(conversation.copy(updatedAt = input.occurredAt))
            TimelineWriteResult.MessageAppended(
                conversationId = input.conversationId,
                messageId = message.id,
                sequenceNumber = sequenceNumber,
                timelineRevision = nextRevision,
            )
        }

    suspend fun activeTimeline(conversationId: String): TimelineReadResult =
        executeRead(TimelineOperation.READ_ACTIVE_TIMELINE) {
            requireConversation(conversationId)
            val head = requireTimelineHead(conversationId)
            TimelineReadResult.Success(
                conversationId = conversationId,
                timelineRevision = head.timelineRevision,
                messages = walkActivePath(head),
            )
        }

    suspend fun allMessages(conversationId: String): TimelineReadResult =
        executeRead(TimelineOperation.READ_ALL_MESSAGES) {
            requireConversation(conversationId)
            val head = requireTimelineHead(conversationId)
            TimelineReadResult.Success(
                conversationId = conversationId,
                timelineRevision = head.timelineRevision,
                messages = timelineDao.allMessages(conversationId),
            )
        }

    suspend fun rewindTo(input: RewindTimelineInput): TimelineWriteResult =
        executeWrite(TimelineOperation.REWIND) {
            val conversation = requireConversation(input.conversationId)
            val head = requireTimelineHead(input.conversationId)
            requireExpectedRevision(head, input.expectedTimelineRevision)
            val target = requireMessage(input.targetMessageId)
            requireMessageConversation(target, input.conversationId)
            val activePath = walkActivePath(head)
            val targetIndex = activePath.indexOfFirst { it.id == target.id }
            if (targetIndex == -1) {
                abort(
                    ConversationTimelineError.MessageNotOnActiveTimeline(
                        input.conversationId,
                        target.id,
                    ),
                )
            }
            if (head.activeHeadMessageId == target.id) {
                return@executeWrite TimelineWriteResult.AlreadyAtRewindTarget(
                    conversationId = input.conversationId,
                    activeHeadMessageId = target.id,
                    timelineRevision = head.timelineRevision,
                )
            }

            val nextRevision = nextRevision(head)
            timelineDao.updateTimelineHead(
                head.copy(
                    activeHeadMessageId = target.id,
                    timelineRevision = nextRevision,
                    updatedAt = input.occurredAt,
                ),
            )
            timelineDao.updateConversation(conversation.copy(updatedAt = input.occurredAt))
            TimelineWriteResult.Rewound(
                conversationId = input.conversationId,
                activeHeadMessageId = target.id,
                deactivatedMessageIds = activePath.drop(targetIndex + 1).map { it.id },
                timelineRevision = nextRevision,
            )
        }

    suspend fun commitRegeneratedAssistantResponse(
        input: CommitRegeneratedAssistantResponseInput,
    ): TimelineWriteResult = executeWrite(TimelineOperation.REGENERATE_ASSISTANT) {
        val conversation = requireConversation(input.conversationId)
        val head = requireTimelineHead(input.conversationId)
        requireExpectedRevision(head, input.expectedTimelineRevision)
        val original = requireMessage(input.originalMessageId)
        requireMessageConversation(original, input.conversationId)
        if (original.role != MessageRole.ASSISTANT) {
            abort(
                ConversationTimelineError.InvalidRegenerationTarget(
                    original.id,
                    InvalidRegenerationReason.ORIGINAL_NOT_ASSISTANT,
                ),
            )
        }
        if (head.activeHeadMessageId != original.id) {
            abort(
                ConversationTimelineError.InvalidRegenerationTarget(
                    original.id,
                    InvalidRegenerationReason.ORIGINAL_NOT_ACTIVE_HEAD,
                ),
            )
        }
        walkActivePath(head)
        if (input.replacement.role != MessageRole.ASSISTANT) {
            abort(
                ConversationTimelineError.InvalidRegenerationTarget(
                    input.replacement.messageId,
                    InvalidRegenerationReason.REPLACEMENT_NOT_ASSISTANT,
                ),
            )
        }
        val originalParent = timelineDao.parentEdge(original.id)
            ?: abort(
                ConversationTimelineError.InvalidRegenerationTarget(
                    original.id,
                    InvalidRegenerationReason.ROOT_ALTERNATIVE_UNSUPPORTED,
                ),
            )
        requireNewMessageId(input.replacement.messageId)
        val sequenceNumber = nextSequenceNumber(input.conversationId)
        val replacement = input.replacement.toEntity(input.conversationId, sequenceNumber)
        timelineDao.insertMessage(replacement)
        insertValidatedParentEdge(replacement.id, originalParent.parentMessageId, input.occurredAt)
        afterMessageGraphWrite(TimelineOperation.REGENERATE_ASSISTANT)
        val nextRevision = nextRevision(head)
        timelineDao.updateTimelineHead(
            head.copy(
                activeHeadMessageId = replacement.id,
                timelineRevision = nextRevision,
                updatedAt = input.occurredAt,
            ),
        )
        timelineDao.updateConversation(conversation.copy(updatedAt = input.occurredAt))
        TimelineWriteResult.AssistantRegenerated(
            conversationId = input.conversationId,
            deactivatedMessageId = original.id,
            activatedMessageId = replacement.id,
            sequenceNumber = sequenceNumber,
            timelineRevision = nextRevision,
        )
    }

    internal suspend fun validateParentAssignment(
        childMessageId: String,
        parentMessageId: String,
    ): TimelineWriteResult = executeWrite(TimelineOperation.VALIDATE_PARENT_ASSIGNMENT) {
        requireValidParentAssignment(childMessageId, parentMessageId)
        TimelineWriteResult.ParentAssignmentValidated(childMessageId, parentMessageId)
    }

    private fun insertValidatedParentEdge(childMessageId: String, parentMessageId: String, createdAt: Long) {
        requireValidParentAssignment(childMessageId, parentMessageId)
        timelineDao.insertParentEdge(
            MessageParentEdgeEntity(
                childMessageId = childMessageId,
                parentMessageId = parentMessageId,
                createdAt = createdAt,
            ),
        )
    }

    private fun requireValidParentAssignment(childMessageId: String, parentMessageId: String) {
        val child = requireMessage(childMessageId)
        val parent = requireMessage(parentMessageId)
        if (child.id == parent.id) {
            abort(ConversationTimelineError.SelfParent(child.id))
        }
        timelineDao.parentEdge(child.id)?.let { existing ->
            abort(ConversationTimelineError.ParentAlreadyAssigned(child.id, existing.parentMessageId))
        }
        if (child.conversationId != parent.conversationId) {
            abort(ConversationTimelineError.ParentBelongsToDifferentConversation(child.id, parent.id))
        }

        val visited = mutableSetOf<String>()
        var current = parent
        while (true) {
            if (current.id == child.id) {
                abort(ConversationTimelineError.CycleDetected(child.id, parent.id))
            }
            if (!visited.add(current.id)) {
                val next = timelineDao.parentEdge(current.id)?.parentMessageId ?: current.id
                abort(ConversationTimelineError.CycleDetected(current.id, next))
            }
            val edge = timelineDao.parentEdge(current.id) ?: break
            val next = requireMessage(edge.parentMessageId)
            if (next.conversationId != child.conversationId) {
                abort(ConversationTimelineError.ParentBelongsToDifferentConversation(current.id, next.id))
            }
            current = next
        }
    }

    private fun walkActivePath(head: ConversationTimelineHeadEntity): List<MessageEntity> {
        requireValidStoredRevision(head)
        val headMessageId = head.activeHeadMessageId ?: return emptyList()
        val reversePath = mutableListOf<MessageEntity>()
        val visited = mutableSetOf<String>()
        var current = requireMessage(headMessageId)
        if (current.conversationId != head.conversationId) {
            abort(
                ConversationTimelineError.TimelineHeadBelongsToDifferentConversation(
                    conversationId = head.conversationId,
                    headMessageId = current.id,
                    actualConversationId = current.conversationId,
                ),
            )
        }

        while (true) {
            if (!visited.add(current.id)) {
                val next = timelineDao.parentEdge(current.id)?.parentMessageId ?: current.id
                abort(ConversationTimelineError.CycleDetected(current.id, next))
            }
            reversePath += current
            val edge = timelineDao.parentEdge(current.id) ?: break
            if (edge.parentMessageId == current.id) {
                abort(ConversationTimelineError.SelfParent(current.id))
            }
            val parent = requireMessage(edge.parentMessageId)
            if (parent.conversationId != head.conversationId) {
                abort(ConversationTimelineError.ParentBelongsToDifferentConversation(current.id, parent.id))
            }
            if (parent.id in visited) {
                abort(ConversationTimelineError.CycleDetected(current.id, parent.id))
            }
            current = parent
        }
        return reversePath.asReversed()
    }

    private fun requireConversation(conversationId: String): ConversationEntity =
        timelineDao.conversation(conversationId)
            ?: abort(ConversationTimelineError.MissingConversation(conversationId))

    private fun requireTimelineHead(conversationId: String): ConversationTimelineHeadEntity =
        timelineDao.timelineHead(conversationId)
            ?: abort(ConversationTimelineError.MissingTimelineHeadRecord(conversationId))

    private fun requireMessage(messageId: String): MessageEntity =
        timelineDao.message(messageId) ?: abort(ConversationTimelineError.MissingMessage(messageId))

    private fun requireMessageConversation(message: MessageEntity, conversationId: String) {
        if (message.conversationId != conversationId) {
            abort(
                ConversationTimelineError.MessageBelongsToDifferentConversation(
                    messageId = message.id,
                    expectedConversationId = conversationId,
                    actualConversationId = message.conversationId,
                ),
            )
        }
    }

    private fun requireNewMessageId(messageId: String) {
        if (timelineDao.message(messageId) != null) {
            abort(ConversationTimelineError.DuplicateMessageId(messageId))
        }
    }

    private fun requireExpectedRevision(head: ConversationTimelineHeadEntity, expected: Long) {
        requireValidStoredRevision(head)
        if (head.timelineRevision != expected) {
            abort(ConversationTimelineError.StaleTimelineRevision(expected, head.timelineRevision))
        }
    }

    private fun requireValidStoredRevision(head: ConversationTimelineHeadEntity) {
        if (head.timelineRevision < INITIAL_TIMELINE_REVISION) {
            abort(ConversationTimelineError.InvalidTimelineRevision(head.conversationId, head.timelineRevision))
        }
    }

    private fun nextRevision(head: ConversationTimelineHeadEntity): Long {
        if (head.timelineRevision == Long.MAX_VALUE) {
            abort(ConversationTimelineError.TimelineRevisionOverflow(head.conversationId))
        }
        return head.timelineRevision + 1
    }

    private fun nextSequenceNumber(conversationId: String): Long {
        val maximum = timelineDao.maximumSequenceNumber(conversationId)
        val next = when (maximum) {
            null -> FIRST_SEQUENCE_NUMBER
            Long.MAX_VALUE -> abort(ConversationTimelineError.SequenceCollision(conversationId, Long.MAX_VALUE))
            else -> maximum + 1
        }
        if (timelineDao.sequenceNumberCount(conversationId, next) != 0) {
            abort(ConversationTimelineError.SequenceCollision(conversationId, next))
        }
        return next
    }

    private fun NewTimelineMessageInput.toEntity(conversationId: String, sequenceNumber: Long) = MessageEntity(
        id = messageId,
        conversationId = conversationId,
        sequenceNumber = sequenceNumber,
        role = role,
        deliveryState = deliveryState,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        providerName = providerName,
        providerModel = providerModel,
        providerRequestId = providerRequestId,
        errorCode = errorCode,
    )

    private suspend fun executeWrite(
        operation: TimelineOperation,
        block: suspend () -> TimelineWriteResult,
    ): TimelineWriteResult = try {
        database.withTransaction { block() }
    } catch (abort: TimelineAbort) {
        TimelineWriteResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        TimelineWriteResult.Failure(
            ConversationTimelineError.StorageFailure(operation, constraint::class.java.simpleName),
        )
    } catch (failure: Exception) {
        TimelineWriteResult.Failure(
            ConversationTimelineError.StorageFailure(operation, failure::class.java.simpleName),
        )
    }

    private suspend fun executeRead(
        operation: TimelineOperation,
        block: suspend () -> TimelineReadResult.Success,
    ): TimelineReadResult = try {
        database.withTransaction { block() }
    } catch (abort: TimelineAbort) {
        TimelineReadResult.Failure(abort.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        TimelineReadResult.Failure(
            ConversationTimelineError.StorageFailure(operation, failure::class.java.simpleName),
        )
    }

    private fun abort(error: ConversationTimelineError): Nothing = throw TimelineAbort(error)

    private class TimelineAbort(val error: ConversationTimelineError) : RuntimeException()

    private companion object {
        const val INITIAL_TIMELINE_REVISION = 0L
        const val FIRST_SEQUENCE_NUMBER = 1L
    }
}
