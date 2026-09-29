package com.shai.riven.data.draft

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.DraftAttachmentEntity
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import kotlinx.coroutines.CancellationException

class ConversationDraftService(
    private val database: RivenDatabase,
    private val attachmentCleanupScheduler: DraftAttachmentCleanupScheduler,
    private val afterMessageGraphMutationBeforeDraftDeletion: () -> Unit = {},
) {
    private val draftDao = database.conversationDraftDao()
    private val timelineDao = database.conversationTimelineDao()
    private val attachmentDao = database.attachmentDao()
    private val timelineService = ConversationTimelineService(database)

    suspend fun readDraft(conversationId: String): ReadConversationDraftResult = try {
        database.withTransaction {
            val draft = draftDao.draft(conversationId)
                ?: return@withTransaction ReadConversationDraftResult.NoDraft(conversationId)
            ReadConversationDraftResult.Draft(draft.toSnapshot())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ReadConversationDraftResult.Failure(
            storageFailure(ConversationDraftOperation.READ_DRAFT, failure),
        )
    }

    suspend fun saveDraft(input: SaveConversationDraftInput): SaveConversationDraftResult {
        if (input.content.length > MAX_DRAFT_CONTENT_CHARS) {
            return SaveConversationDraftResult.Failure(
                ConversationDraftError.DraftContentTooLong(
                    maximumChars = MAX_DRAFT_CONTENT_CHARS,
                    actualChars = input.content.length,
                ),
            )
        }
        val mutation = try {
            database.withTransaction {
                requireConversationScope(input.conversationId)
                val current = draftDao.draft(input.conversationId)
                val actualRevision = current?.revision ?: NO_DRAFT_REVISION
                requireExpectedDraftRevision(input.expectedRevision, actualRevision)
                if (current?.revision == Long.MAX_VALUE) {
                    abort(ConversationDraftError.DraftRevisionOverflow(input.conversationId))
                }
                requireAvailableAttachments(input.attachmentIds)

                val oldAttachmentIds = current?.let {
                    draftDao.attachmentIdsForDraft(input.conversationId)
                }.orEmpty()
                val nextRevision = actualRevision + 1
                val replacement = ConversationDraftEntity(
                    conversationId = input.conversationId,
                    content = input.content,
                    revision = nextRevision,
                    createdAt = current?.createdAt ?: input.occurredAt,
                    updatedAt = input.occurredAt,
                )
                if (current == null) {
                    draftDao.insertDraft(replacement)
                } else {
                    check(draftDao.updateDraft(replacement) == 1)
                    draftDao.deleteDraftAttachmentsForConversation(input.conversationId)
                }
                input.attachmentIds.forEachIndexed { index, attachmentId ->
                    draftDao.insertDraftAttachment(
                        DraftAttachmentEntity(
                            conversationId = input.conversationId,
                            attachmentId = attachmentId,
                            attachmentOrder = index,
                            createdAt = input.occurredAt,
                        ),
                    )
                }
                val removed = oldAttachmentIds.toSet() - input.attachmentIds.toSet()
                SaveMutation(
                    revision = nextRevision,
                    cleanupAttachmentIds = markEligibleOrphansPending(removed, input.occurredAt),
                )
            }
        } catch (abort: DraftAbort) {
            return SaveConversationDraftResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return SaveConversationDraftResult.Failure(
                storageFailure(ConversationDraftOperation.SAVE_DRAFT, failure),
            )
        }
        return SaveConversationDraftResult.Saved(
            conversationId = input.conversationId,
            revision = mutation.revision,
            attachmentCleanup = scheduleCleanup(mutation.cleanupAttachmentIds),
        )
    }

    suspend fun clearDraft(input: ClearConversationDraftInput): ClearConversationDraftResult {
        val mutation = try {
            database.withTransaction {
                val current = draftDao.draft(input.conversationId)
                if (current == null) {
                    if (input.expectedRevision == NO_DRAFT_REVISION) {
                        return@withTransaction ClearMutation.AlreadyClear
                    }
                    abort(
                        ConversationDraftError.StaleDraftRevision(
                            expected = input.expectedRevision,
                            actual = NO_DRAFT_REVISION,
                        ),
                    )
                }
                requireExpectedDraftRevision(input.expectedRevision, current.revision)
                val attachmentIds = draftDao.attachmentIdsForDraft(input.conversationId)
                check(draftDao.deleteDraft(input.conversationId) == 1)
                ClearMutation.Cleared(
                    revision = current.revision,
                    cleanupAttachmentIds = markEligibleOrphansPending(
                        attachmentIds.toSet(),
                        input.occurredAt,
                    ),
                )
            }
        } catch (abort: DraftAbort) {
            return ClearConversationDraftResult.Failure(abort.error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ClearConversationDraftResult.Failure(
                storageFailure(ConversationDraftOperation.CLEAR_DRAFT, failure),
            )
        }
        return when (mutation) {
            ClearMutation.AlreadyClear -> ClearConversationDraftResult.AlreadyClear(input.conversationId)
            is ClearMutation.Cleared -> ClearConversationDraftResult.Cleared(
                conversationId = input.conversationId,
                clearedRevision = mutation.revision,
                attachmentCleanup = scheduleCleanup(mutation.cleanupAttachmentIds),
            )
        }
    }

    suspend fun commitDraftAsUserMessage(
        input: CommitDraftAsUserMessageInput,
    ): CommitDraftAsUserMessageResult = try {
        database.withTransaction {
            val draft = draftDao.draft(input.conversationId)
                ?: abort(ConversationDraftError.NoDraft(input.conversationId))
            requireExpectedDraftRevision(input.expectedDraftRevision, draft.revision)
            val attachmentIds = draftDao.attachmentIdsForDraft(input.conversationId)
            if (draft.content.isBlank() && attachmentIds.isEmpty()) {
                abort(ConversationDraftError.EmptyDraft(input.conversationId))
            }
            requireAvailableAttachments(attachmentIds)
            val appended = timelineService.appendMessageInCurrentTransaction(
                AppendTimelineMessageInput(
                    conversationId = input.conversationId,
                    message = NewTimelineMessageInput(
                        messageId = input.messageId,
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = draft.content,
                        createdAt = input.occurredAt,
                        updatedAt = input.occurredAt,
                        providerName = null,
                        providerModel = null,
                        providerRequestId = null,
                        errorCode = null,
                        attachmentIds = attachmentIds,
                    ),
                    expectedTimelineRevision = input.expectedTimelineRevision,
                    occurredAt = input.occurredAt,
                ),
            )
            afterMessageGraphMutationBeforeDraftDeletion()
            check(draftDao.deleteDraft(input.conversationId) == 1)
            CommitDraftAsUserMessageResult.MessageCommitted(
                conversationId = input.conversationId,
                messageId = input.messageId,
                sequenceNumber = appended.sequenceNumber,
                timelineRevision = appended.timelineRevision,
            )
        }
    } catch (abort: DraftAbort) {
        CommitDraftAsUserMessageResult.Failure(abort.error)
    } catch (abort: ConversationTimelineService.TimelineAbort) {
        CommitDraftAsUserMessageResult.Failure(
            ConversationDraftError.TimelineWriteFailure(abort.error),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (constraint: SQLiteConstraintException) {
        CommitDraftAsUserMessageResult.Failure(
            storageFailure(ConversationDraftOperation.COMMIT_DRAFT_AS_USER_MESSAGE, constraint),
        )
    } catch (failure: Exception) {
        CommitDraftAsUserMessageResult.Failure(
            storageFailure(ConversationDraftOperation.COMMIT_DRAFT_AS_USER_MESSAGE, failure),
        )
    }

    private fun requireConversationScope(conversationId: String) {
        if (timelineDao.conversation(conversationId) == null) {
            abort(ConversationDraftError.MissingConversation(conversationId))
        }
        if (timelineDao.timelineHead(conversationId) == null) {
            abort(ConversationDraftError.MissingTimelineHead(conversationId))
        }
    }

    private fun requireExpectedDraftRevision(expected: Long, actual: Long) {
        if (expected != actual) {
            abort(ConversationDraftError.StaleDraftRevision(expected, actual))
        }
    }

    private fun requireAvailableAttachments(attachmentIds: List<String>) {
        val seen = mutableSetOf<String>()
        attachmentIds.forEach { attachmentId ->
            if (!seen.add(attachmentId)) {
                abort(ConversationDraftError.DuplicateAttachmentReference(attachmentId))
            }
            val attachment = attachmentDao.attachment(attachmentId)
                ?: abort(ConversationDraftError.MissingAttachment(attachmentId))
            if (attachment.state != AttachmentState.AVAILABLE) {
                abort(ConversationDraftError.AttachmentUnavailable(attachmentId, attachment.state))
            }
        }
    }

    private fun markEligibleOrphansPending(
        attachmentIds: Set<String>,
        occurredAt: Long,
    ): Set<String> = buildSet {
        attachmentIds.forEach { attachmentId ->
            val attachment = attachmentDao.attachment(attachmentId) ?: return@forEach
            if (attachment.state == AttachmentState.AVAILABLE &&
                attachmentDao.messageReferenceCount(attachmentId) == 0 &&
                attachmentDao.draftReferenceCount(attachmentId) == 0 &&
                attachmentDao.derivedArtifactDependencyCount(attachmentId) == 0
            ) {
                check(
                    attachmentDao.updateAttachment(
                        attachment.copy(
                            state = AttachmentState.DELETE_PENDING,
                            updatedAt = occurredAt,
                        ),
                    ) == 1,
                )
                add(attachmentId)
            }
        }
    }

    private fun scheduleCleanup(attachmentIds: Set<String>): DraftCleanupSchedulingSummary {
        if (attachmentIds.isEmpty()) {
            return DraftCleanupSchedulingSummary(
                status = DraftCleanupSchedulingStatus.NOT_REQUIRED,
                attemptedCount = 0,
                enqueuedCount = 0,
                failedCount = 0,
            )
        }
        var enqueued = 0
        var failed = 0
        attachmentIds.sorted().forEach { attachmentId ->
            val result = try {
                attachmentCleanupScheduler.enqueueAttachmentCleanup(attachmentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result is RivenBackgroundScheduleResult.Enqueued) enqueued += 1 else failed += 1
        }
        val status = when {
            failed == 0 -> DraftCleanupSchedulingStatus.ENQUEUED
            enqueued == 0 -> DraftCleanupSchedulingStatus.FAILED
            else -> DraftCleanupSchedulingStatus.PARTIAL_FAILURE
        }
        return DraftCleanupSchedulingSummary(
            status = status,
            attemptedCount = attachmentIds.size,
            enqueuedCount = enqueued,
            failedCount = failed,
        )
    }

    private fun ConversationDraftEntity.toSnapshot() = ConversationDraftSnapshot(
        conversationId = conversationId,
        content = content,
        attachmentIds = draftDao.attachmentIdsForDraft(conversationId),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun storageFailure(
        operation: ConversationDraftOperation,
        failure: Exception,
    ) = ConversationDraftError.StorageFailure(
        operation = operation,
        causeType = failure::class.java.simpleName,
    )

    private fun abort(error: ConversationDraftError): Nothing = throw DraftAbort(error)

    private class DraftAbort(val error: ConversationDraftError) : RuntimeException()

    private data class SaveMutation(
        val revision: Long,
        val cleanupAttachmentIds: Set<String>,
    )

    private sealed interface ClearMutation {
        data object AlreadyClear : ClearMutation
        data class Cleared(
            val revision: Long,
            val cleanupAttachmentIds: Set<String>,
        ) : ClearMutation
    }

    private companion object {
        const val NO_DRAFT_REVISION = 0L
    }
}
