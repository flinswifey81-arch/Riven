package com.shai.riven.data.draft

import android.content.Context
import com.shai.riven.data.attachment.AttachmentByteSource
import com.shai.riven.data.attachment.AttachmentCleanupResult
import com.shai.riven.data.attachment.AttachmentCreateResult
import com.shai.riven.data.attachment.AttachmentError
import com.shai.riven.data.attachment.AttachmentService
import com.shai.riven.data.attachment.FileAttachmentBlobStore
import com.shai.riven.data.attachment.ImportedAttachmentInput
import com.shai.riven.data.background.RivenBackgroundScheduleError
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineError
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.deletion.DeleteTimelineMessageInput
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationDraftServiceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: RivenDatabase
    private lateinit var scheduler: RecordingCleanupScheduler
    private lateinit var service: ConversationDraftService
    private lateinit var timeline: ConversationTimelineService
    private lateinit var blobRoot: File
    private lateinit var blobStore: FileAttachmentBlobStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "draft-${UUID.randomUUID()}.db"
        database = openDatabase()
        scheduler = RecordingCleanupScheduler()
        service = ConversationDraftService(database, scheduler)
        timeline = ConversationTimelineService(database)
        blobRoot = File(context.cacheDir, "draft-blobs-${UUID.randomUUID()}")
        blobStore = FileAttachmentBlobStore(blobRoot)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
        if (::blobRoot.isInitialized) blobRoot.deleteRecursively()
    }

    @Test
    fun saveNewDraftStoresRevisionOne() = runBlocking {
        createConversation()

        val result = save(content = "new")

        assertEquals(1L, assertSaved(result).revision)
        assertEquals(1L, assertDraft().revision)
    }

    @Test
    fun readMissingDraftReturnsTypedNoDraftWithoutCreatingRow() = runBlocking {
        createConversation()

        assertEquals(ReadConversationDraftResult.NoDraft("conversation"), service.readDraft("conversation"))
        assertTrue(database.conversationDraftDao().allDrafts().isEmpty())
    }

    @Test
    fun updateDraftRequiresCurrentRevisionAndIncrements() = runBlocking {
        createConversation()
        save(content = "one")

        val updated = save(content = "two", expectedRevision = 1, occurredAt = 2)

        assertEquals(2L, assertSaved(updated).revision)
        assertEquals("two", assertDraft().content)
    }

    @Test
    fun staleUpdateReturnsTypedFailureWithoutMutation() = runBlocking {
        createConversation()
        save(content = "current")

        val result = save(content = "stale", expectedRevision = 0, occurredAt = 2)

        assertDraftError<ConversationDraftError.StaleDraftRevision>(result)
        assertEquals("current", assertDraft().content)
        assertEquals(1L, assertDraft().revision)
    }

    @Test
    fun revisionOverflowReturnsTypedFailureWithoutMutation() = runBlocking {
        createConversation()
        database.conversationDraftDao().insertDraft(
            ConversationDraftEntity("conversation", "max", Long.MAX_VALUE, 1, 1),
        )

        val result = save(content = "overflow", expectedRevision = Long.MAX_VALUE, occurredAt = 2)

        assertDraftError<ConversationDraftError.DraftRevisionOverflow>(result)
        assertEquals("max", assertDraft().content)
        assertEquals(Long.MAX_VALUE, assertDraft().revision)
    }

    @Test
    fun exactContentPreservesWhitespaceAndNewlines() = runBlocking {
        createConversation()
        val exact = "  leading\nbody\ntrailing  "

        save(content = exact)

        assertEquals(exact, assertDraft().content)
    }

    @Test
    fun contentLimitReturnsTypedFailureWithoutPersisting() = runBlocking {
        createConversation()

        val result = save(content = "x".repeat(MAX_DRAFT_CONTENT_CHARS + 1))

        assertDraftError<ConversationDraftError.DraftContentTooLong>(result)
        assertNull(database.conversationDraftDao().draft("conversation"))
    }

    @Test
    fun missingConversationReturnsTypedFailure() = runBlocking {
        assertDraftError<ConversationDraftError.MissingConversation>(save(content = "draft"))
        Unit
    }

    @Test
    fun missingTimelineHeadReturnsTypedFailure() = runBlocking {
        database.conversationTimelineDao().insertConversation(
            ConversationEntity("conversation", 1, 1, ConversationStatus.ACTIVE),
        )

        assertDraftError<ConversationDraftError.MissingTimelineHead>(save(content = "draft"))
        Unit
    }

    @Test
    fun emptyDraftMayBeStoredUntilExplicitClear() = runBlocking {
        createConversation()

        assertSaved(save(content = "", attachmentIds = emptyList()))

        assertEquals("", assertDraft().content)
        assertTrue(assertDraft().attachmentIds.isEmpty())
    }

    @Test
    fun attachmentsPersistInCallerOrder() = runBlocking {
        createConversation()
        insertAttachment("b")
        insertAttachment("a")

        save(attachmentIds = listOf("b", "a"))

        assertEquals(listOf("b", "a"), assertDraft().attachmentIds)
    }

    @Test
    fun duplicateAttachmentIsRejectedAtomically() = runBlocking {
        createConversation()
        insertAttachment("a")

        val result = save(attachmentIds = listOf("a", "a"))

        assertDraftError<ConversationDraftError.DuplicateAttachmentReference>(result)
        assertNull(database.conversationDraftDao().draft("conversation"))
    }

    @Test
    fun missingAttachmentIsRejectedAtomically() = runBlocking {
        createConversation()

        assertDraftError<ConversationDraftError.MissingAttachment>(save(attachmentIds = listOf("missing")))
        assertNull(database.conversationDraftDao().draft("conversation"))
    }

    @Test
    fun stagingAttachmentIsRejected() = runBlocking {
        createConversation()
        insertAttachment("staging", AttachmentState.STAGING)

        val error = assertDraftError<ConversationDraftError.AttachmentUnavailable>(
            save(attachmentIds = listOf("staging")),
        )

        assertEquals(AttachmentState.STAGING, error.state)
    }

    @Test
    fun deletePendingAttachmentIsRejected() = runBlocking {
        createConversation()
        insertAttachment("pending", AttachmentState.DELETE_PENDING)

        val error = assertDraftError<ConversationDraftError.AttachmentUnavailable>(
            save(attachmentIds = listOf("pending")),
        )

        assertEquals(AttachmentState.DELETE_PENDING, error.state)
    }

    @Test
    fun draftOnlyAvailableAttachmentIsProtectedFromCleanup() = runBlocking {
        createConversation()
        createBlobAttachment("owned")
        save(attachmentIds = listOf("owned"))
        setAttachmentState("owned", AttachmentState.DELETE_PENDING)

        val result = attachmentService().finalizePendingDeletion("owned")

        assertTrue(result is AttachmentCleanupResult.Failure)
        val error = (result as AttachmentCleanupResult.Failure).error
        assertTrue(error is AttachmentError.AttachmentStillReferenced)
        error as AttachmentError.AttachmentStillReferenced
        assertEquals(0, error.messageReferenceCount)
        assertEquals(1, error.draftReferenceCount)
        val attachment = database.attachmentDao().attachment("owned")
        assertNotNull(attachment)
        assertTrue(blobStore.exists(checkNotNull(attachment).storageKey))
    }

    @Test
    fun removedOrphanAttachmentBecomesDeletePending() = runBlocking {
        createConversation()
        insertAttachment("orphan")
        save(attachmentIds = listOf("orphan"))

        val updated = save(attachmentIds = emptyList(), expectedRevision = 1, occurredAt = 5)

        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("orphan")?.state)
        assertEquals(5L, database.attachmentDao().attachment("orphan")?.updatedAt)
        assertEquals(DraftCleanupSchedulingStatus.ENQUEUED, assertSaved(updated).attachmentCleanup.status)
        assertEquals(listOf("orphan"), scheduler.enqueuedIds)
    }

    @Test
    fun removedAttachmentStillOwnedByMessageRemainsAvailable() = runBlocking {
        createConversation()
        insertAttachment("shared")
        append("message", attachmentIds = listOf("shared"))
        save(attachmentIds = listOf("shared"))

        save(attachmentIds = emptyList(), expectedRevision = 1, occurredAt = 5)

        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("shared")?.state)
        assertTrue(scheduler.enqueuedIds.isEmpty())
    }

    @Test
    fun removedAttachmentStillOwnedByAnotherDraftRemainsAvailable() = runBlocking {
        createConversation("one")
        createConversation("two")
        insertAttachment("shared")
        save("one", attachmentIds = listOf("shared"))
        save("two", attachmentIds = listOf("shared"))

        save("one", attachmentIds = emptyList(), expectedRevision = 1, occurredAt = 5)

        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("shared")?.state)
        assertEquals(1, database.conversationDraftDao().draftReferenceCount("shared"))
    }

    @Test
    fun removedAttachmentWithDerivedDependencyRemainsAvailable() = runBlocking {
        createConversation()
        insertAttachment("derived")
        insertDerivedAttachmentDependency("derived")
        save(attachmentIds = listOf("derived"))

        save(attachmentIds = emptyList(), expectedRevision = 1, occurredAt = 5)

        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("derived")?.state)
        assertEquals(1, database.attachmentDao().derivedArtifactDependencyCount("derived"))
    }

    @Test
    fun clearDraftMarksEligibleOrphanPendingAndSchedulesCleanup() = runBlocking {
        createConversation()
        insertAttachment("clear-orphan")
        save(attachmentIds = listOf("clear-orphan"))

        val result = service.clearDraft(ClearConversationDraftInput("conversation", 1, 9))

        assertTrue(result is ClearConversationDraftResult.Cleared)
        result as ClearConversationDraftResult.Cleared
        assertEquals(DraftCleanupSchedulingStatus.ENQUEUED, result.attachmentCleanup.status)
        assertNull(database.conversationDraftDao().draft("conversation"))
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("clear-orphan")?.state)
    }

    @Test
    fun cleanupSchedulerFailureDoesNotRollbackDraftMutation() = runBlocking {
        createConversation()
        insertAttachment("orphan")
        save(attachmentIds = listOf("orphan"))
        scheduler.failIds += "orphan"

        val result = save(content = "committed", attachmentIds = emptyList(), expectedRevision = 1, occurredAt = 2)

        val saved = assertSaved(result)
        assertEquals(DraftCleanupSchedulingStatus.FAILED, saved.attachmentCleanup.status)
        assertEquals("committed", assertDraft().content)
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("orphan")?.state)
    }

    @Test
    fun messageDeleteWithDraftReferenceKeepsAttachmentAvailable() = runBlocking {
        createConversation()
        insertAttachment("shared")
        append("message", attachmentIds = listOf("shared"))
        save(attachmentIds = listOf("shared"))

        val result = SafeDeleteService(database).deleteLeafMessage(
            DeleteTimelineMessageInput("conversation", "message", 1, 3),
        )

        assertTrue(result is com.shai.riven.data.deletion.TimelineDeleteResult.Deleted)
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("shared")?.state)
        assertEquals(1, database.conversationDraftDao().draftReferenceCount("shared"))
    }

    @Test
    fun finalizePendingDeleteRefusesDraftOwnedAttachment() = runBlocking {
        createConversation()
        createBlobAttachment("pending-owned")
        save(attachmentIds = listOf("pending-owned"))
        setAttachmentState("pending-owned", AttachmentState.DELETE_PENDING)

        val result = attachmentService().finalizePendingDeletion("pending-owned")

        assertTrue(result is AttachmentCleanupResult.Failure)
        assertTrue((result as AttachmentCleanupResult.Failure).error is AttachmentError.AttachmentStillReferenced)
        val attachment = database.attachmentDao().attachment("pending-owned")
        assertNotNull(attachment)
        assertTrue(blobStore.exists(checkNotNull(attachment).storageKey))
    }

    @Test
    fun sqlDeleteGuardIncludesDraftReferences() = runBlocking {
        createConversation()
        insertAttachment("sql-guard", AttachmentState.AVAILABLE)
        save(attachmentIds = listOf("sql-guard"))
        setAttachmentState("sql-guard", AttachmentState.DELETE_PENDING)

        val deleted = database.attachmentDao().deleteUnreferencedAttachmentInState(
            "sql-guard",
            AttachmentState.DELETE_PENDING,
        )

        assertEquals(0, deleted)
        assertNotNull(database.attachmentDao().attachment("sql-guard"))
    }

    @Test
    fun attachmentBecomesEligibleAfterFinalDraftReferenceRemoved() = runBlocking {
        createConversation()
        insertAttachment("eligible")
        save(attachmentIds = listOf("eligible"))

        service.clearDraft(ClearConversationDraftInput("conversation", 1, 8))

        assertEquals(0, database.conversationDraftDao().draftReferenceCount("eligible"))
        assertEquals(AttachmentState.DELETE_PENDING, database.attachmentDao().attachment("eligible")?.state)
    }

    @Test
    fun commitTextDraftCreatesExactUserPersistedMessageAndRemovesDraft() = runBlocking {
        createConversation()
        val exact = "  exact user text\n  "
        save(content = exact)

        val result = commit("message")

        assertTrue(result is CommitDraftAsUserMessageResult.MessageCommitted)
        val message = checkNotNull(database.conversationTimelineDao().message("message"))
        assertEquals(MessageRole.USER, message.role)
        assertEquals(MessageDeliveryState.PERSISTED, message.deliveryState)
        assertEquals(exact, message.content)
        assertNull(message.providerName)
        assertNull(message.providerModel)
        assertNull(message.providerRequestId)
        assertNull(message.errorCode)
        assertNull(database.conversationDraftDao().draft("conversation"))
    }

    @Test
    fun commitAttachmentOnlyDraftIsAllowed() = runBlocking {
        createConversation()
        insertAttachment("attachment-only")
        save(content = "", attachmentIds = listOf("attachment-only"))

        val result = commit("message")

        assertTrue(result is CommitDraftAsUserMessageResult.MessageCommitted)
        assertEquals(listOf("attachment-only"), database.attachmentDao().attachmentIdsForMessage("message"))
    }

    @Test
    fun commitBlankDraftWithoutAttachmentsIsRejected() = runBlocking {
        createConversation()
        save(content = " \n ")

        val result = commit("message")

        assertCommitError<ConversationDraftError.EmptyDraft>(result)
        assertNull(database.conversationTimelineDao().message("message"))
        assertNotNull(database.conversationDraftDao().draft("conversation"))
    }

    @Test
    fun commitPreservesDraftAttachmentOrder() = runBlocking {
        createConversation()
        insertAttachment("second")
        insertAttachment("first")
        save(content = "send", attachmentIds = listOf("second", "first"))

        commit("message")

        assertEquals(listOf("second", "first"), database.attachmentDao().attachmentIdsForMessage("message"))
    }

    @Test
    fun commitUsesCanonicalTimelineParentSequenceHeadAndRevision() = runBlocking {
        createConversation()
        append("prior", content = "prior")
        save(content = "draft")

        val result = commit("message", expectedTimelineRevision = 1)

        result as CommitDraftAsUserMessageResult.MessageCommitted
        assertEquals(2L, result.sequenceNumber)
        assertEquals(2L, result.timelineRevision)
        assertEquals("prior", database.conversationTimelineDao().parentEdge("message")?.parentMessageId)
        val head = database.conversationTimelineDao().timelineHead("conversation")
        assertEquals("message", head?.activeHeadMessageId)
        assertEquals(2L, head?.timelineRevision)
    }

    @Test
    fun commitWithStaleDraftRevisionDoesNotMutateTimeline() = runBlocking {
        createConversation()
        save(content = "draft")

        val result = commit("message", expectedDraftRevision = 0)

        assertCommitError<ConversationDraftError.StaleDraftRevision>(result)
        assertNull(database.conversationTimelineDao().message("message"))
        assertNotNull(database.conversationDraftDao().draft("conversation"))
        assertEquals(0L, database.conversationTimelineDao().timelineHead("conversation")?.timelineRevision)
    }

    @Test
    fun commitWithStaleTimelineRevisionDoesNotMutateDraftOrTimeline() = runBlocking {
        createConversation()
        append("prior")
        save(content = "draft")

        val result = commit("message", expectedTimelineRevision = 0)

        val error = assertCommitError<ConversationDraftError.TimelineWriteFailure>(result)
        assertTrue(error.error is ConversationTimelineError.StaleTimelineRevision)
        assertNull(database.conversationTimelineDao().message("message"))
        assertEquals(1L, assertDraft().revision)
        assertEquals("prior", database.conversationTimelineDao().timelineHead("conversation")?.activeHeadMessageId)
    }

    @Test
    fun commitMessageIdCollisionDoesNotMutateDraftOrTimeline() = runBlocking {
        createConversation()
        append("collision")
        save(content = "draft")

        val result = commit("collision", expectedTimelineRevision = 1)

        val error = assertCommitError<ConversationDraftError.TimelineWriteFailure>(result)
        assertTrue(error.error is ConversationTimelineError.DuplicateMessageId)
        assertEquals(1, database.conversationTimelineDao().allMessages("conversation").size)
        assertEquals("draft", assertDraft().content)
    }

    @Test
    fun controlledCommitFailureRollsBackMessageGraphAndPreservesDraft() = runBlocking {
        createConversation()
        insertAttachment("owned")
        append("prior")
        save(content = "draft", attachmentIds = listOf("owned"))
        val failing = ConversationDraftService(
            database = database,
            attachmentCleanupScheduler = scheduler,
            afterMessageGraphMutationBeforeDraftDeletion = { error("controlled") },
        )

        val result = failing.commitDraftAsUserMessage(
            CommitDraftAsUserMessageInput("conversation", "message", 1, 1, 5),
        )

        assertCommitError<ConversationDraftError.StorageFailure>(result)
        assertNull(database.conversationTimelineDao().message("message"))
        assertNull(database.conversationTimelineDao().parentEdge("message"))
        assertTrue(database.attachmentDao().attachmentIdsForMessage("message").isEmpty())
        val head = database.conversationTimelineDao().timelineHead("conversation")
        assertEquals("prior", head?.activeHeadMessageId)
        assertEquals(1L, head?.timelineRevision)
        assertEquals(1L, assertDraft().revision)
        assertEquals(listOf("owned"), assertDraft().attachmentIds)
    }

    @Test
    fun commitTransfersDraftAttachmentOwnershipToMessageAtomically() = runBlocking {
        createConversation()
        insertAttachment("transfer")
        save(content = "send", attachmentIds = listOf("transfer"))

        commit("message")

        assertEquals(0, database.conversationDraftDao().draftReferenceCount("transfer"))
        assertEquals(1, database.attachmentDao().messageReferenceCount("transfer"))
        assertEquals(AttachmentState.AVAILABLE, database.attachmentDao().attachment("transfer")?.state)
    }

    @Test
    fun rewindPreservesConversationScopedDraft() = runBlocking {
        createConversation()
        append("first")
        append("second", expectedRevision = 1, role = MessageRole.ASSISTANT)
        save(content = "unsent")

        timeline.rewindTo(RewindTimelineInput("conversation", "first", 2, 4))

        assertEquals("unsent", assertDraft().content)
        assertEquals(1, database.conversationDraftDao().allDrafts().size)
    }

    @Test
    fun regeneratePreservesConversationScopedDraft() = runBlocking {
        createConversation()
        append("user")
        append("assistant", expectedRevision = 1, role = MessageRole.ASSISTANT)
        save(content = "unsent")

        timeline.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                conversationId = "conversation",
                originalMessageId = "assistant",
                replacement = messageInput("assistant-new", MessageRole.ASSISTANT, 3),
                expectedTimelineRevision = 2,
                occurredAt = 3,
            ),
        )

        assertEquals("unsent", assertDraft().content)
    }

    @Test
    fun inactiveBranchDoesNotDuplicateDraftState() = runBlocking {
        createConversation()
        append("user")
        append("assistant", expectedRevision = 1, role = MessageRole.ASSISTANT)
        insertAttachment("draft-attachment")
        save(content = "single", attachmentIds = listOf("draft-attachment"))

        timeline.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation",
                "assistant",
                messageInput("assistant-new", MessageRole.ASSISTANT, 3),
                2,
                3,
            ),
        )

        assertEquals(1, database.conversationDraftDao().allDrafts().size)
        assertEquals(1, database.conversationDraftDao().allAttachmentsForDraft("conversation").size)
    }

    @Test
    fun relaunchRestoresExactDraftSnapshot() = runBlocking {
        createConversation()
        insertAttachment("two")
        insertAttachment("one")
        val exact = "  durable\ntext  "
        save(content = exact, attachmentIds = listOf("two", "one"), occurredAt = 77)
        database.close()
        database = openDatabase()
        service = ConversationDraftService(database, scheduler)
        timeline = ConversationTimelineService(database)

        val snapshot = assertDraft()

        assertEquals(exact, snapshot.content)
        assertEquals(listOf("two", "one"), snapshot.attachmentIds)
        assertEquals(1L, snapshot.revision)
        assertEquals(77L, snapshot.createdAt)
        assertEquals(77L, snapshot.updatedAt)
    }

    @Test
    fun draftOperationsCreateNoCognitiveState() = runBlocking {
        createConversation()
        val tables = cognitiveTables()
        val before = tables.associateWith(::rowCount)

        save(content = "private composer state")
        assertDraft()
        save(content = "changed", expectedRevision = 1, occurredAt = 2)
        service.clearDraft(ClearConversationDraftInput("conversation", 2, 3))

        assertEquals(before, tables.associateWith(::rowCount))
    }

    @Test
    fun timelineReadsDoNotMutateOrExposeDraft() = runBlocking {
        createConversation()
        append("message", content = "canonical")
        save(content = "unsent private draft")

        val active = timeline.activeTimeline("conversation")
        val all = timeline.allMessages("conversation")

        assertTrue(active is TimelineReadResult.Success)
        assertTrue(all is TimelineReadResult.Success)
        assertEquals(listOf("canonical"), (active as TimelineReadResult.Success).messages.map { it.content })
        assertEquals(listOf("canonical"), (all as TimelineReadResult.Success).messages.map { it.content })
        assertEquals("unsent private draft", assertDraft().content)
    }

    private fun openDatabase(): RivenDatabase =
        RivenDatabase.buildNamedForRestoreValidation(context, databaseName).also {
            it.openHelper.writableDatabase
        }

    private suspend fun createConversation(conversationId: String = "conversation") {
        val result = timeline.createConversationWithTimeline(
            CreateTimelineConversationInput(
                conversationId = conversationId,
                createdAt = 1,
                updatedAt = 1,
                status = ConversationStatus.ACTIVE,
            ),
        )
        assertTrue(result is TimelineWriteResult.ConversationCreated)
    }

    private suspend fun save(
        conversationId: String = "conversation",
        content: String = "draft",
        attachmentIds: List<String> = emptyList(),
        expectedRevision: Long = 0,
        occurredAt: Long = 1,
    ): SaveConversationDraftResult = service.saveDraft(
        SaveConversationDraftInput(
            conversationId,
            content,
            attachmentIds,
            expectedRevision,
            occurredAt,
        ),
    )

    private suspend fun commit(
        messageId: String,
        expectedDraftRevision: Long = 1,
        expectedTimelineRevision: Long = 0,
    ): CommitDraftAsUserMessageResult = service.commitDraftAsUserMessage(
        CommitDraftAsUserMessageInput(
            conversationId = "conversation",
            messageId = messageId,
            expectedDraftRevision = expectedDraftRevision,
            expectedTimelineRevision = expectedTimelineRevision,
            occurredAt = 10,
        ),
    )

    private suspend fun append(
        messageId: String,
        content: String = messageId,
        attachmentIds: List<String> = emptyList(),
        expectedRevision: Long = 0,
        role: MessageRole = MessageRole.USER,
    ): TimelineWriteResult = timeline.appendMessage(
        AppendTimelineMessageInput(
            conversationId = "conversation",
            message = messageInput(messageId, role, expectedRevision + 1, attachmentIds, content),
            expectedTimelineRevision = expectedRevision,
            occurredAt = expectedRevision + 1,
        ),
    )

    private fun messageInput(
        messageId: String,
        role: MessageRole,
        occurredAt: Long,
        attachmentIds: List<String> = emptyList(),
        content: String = messageId,
    ) = NewTimelineMessageInput(
        messageId = messageId,
        role = role,
        deliveryState = MessageDeliveryState.PERSISTED,
        content = content,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        attachmentIds = attachmentIds,
    )

    private fun insertAttachment(
        attachmentId: String,
        state: AttachmentState = AttachmentState.AVAILABLE,
    ) {
        database.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = attachmentId,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = state,
                storageKey = "attachments/$attachmentId.blob",
                byteSize = if (state == AttachmentState.STAGING) null else 1,
                contentSha256 = if (state == AttachmentState.STAGING) null else "a".repeat(64),
                source = AttachmentSource.SHAI_IMPORT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private suspend fun createBlobAttachment(attachmentId: String) {
        val result = attachmentService().createImportedAttachment(
            ImportedAttachmentInput(
                attachmentId = attachmentId,
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                occurredAt = 1,
                bytes = AttachmentByteSource.fromBytes("blob-$attachmentId".toByteArray()),
            ),
        )
        assertTrue(result is AttachmentCreateResult.Success)
    }

    private fun attachmentService() = AttachmentService(database, blobStore)

    private fun setAttachmentState(attachmentId: String, state: AttachmentState) {
        val attachment = checkNotNull(database.attachmentDao().attachment(attachmentId))
        check(database.attachmentDao().updateAttachment(attachment.copy(state = state)) == 1)
    }

    private fun insertDerivedAttachmentDependency(attachmentId: String) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO derived_artifacts VALUES ('derived-$attachmentId', 'SUMMARY', 'CURRENT', 'hash', 1, NULL, 1, NULL)",
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO derived_artifact_attachment_dependencies VALUES ('derived-$attachmentId', '$attachmentId', 1)",
        )
    }

    private suspend fun assertDraft(conversationId: String = "conversation"): ConversationDraftSnapshot {
        val result = service.readDraft(conversationId)
        assertTrue(result is ReadConversationDraftResult.Draft)
        return (result as ReadConversationDraftResult.Draft).snapshot
    }

    private fun assertSaved(result: SaveConversationDraftResult): SaveConversationDraftResult.Saved {
        assertTrue(result is SaveConversationDraftResult.Saved)
        return result as SaveConversationDraftResult.Saved
    }

    private inline fun <reified T : ConversationDraftError> assertDraftError(
        result: SaveConversationDraftResult,
    ): T {
        assertTrue(result is SaveConversationDraftResult.Failure)
        val error = (result as SaveConversationDraftResult.Failure).error
        assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
        return error as T
    }

    private inline fun <reified T : ConversationDraftError> assertCommitError(
        result: CommitDraftAsUserMessageResult,
    ): T {
        assertTrue(result is CommitDraftAsUserMessageResult.Failure)
        val error = (result as CommitDraftAsUserMessageResult.Failure).error
        assertTrue("Expected ${T::class.java.simpleName}, got $error", error is T)
        return error as T
    }

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM `$table`",
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun cognitiveTables() = listOf(
        "experiences",
        "candidate_memories",
        "memories",
        "open_loops",
        "memory_audit_history",
        "open_loop_audit_history",
        "suppression_tombstones",
        "repair_jobs",
        "derived_artifacts",
    )

    private class RecordingCleanupScheduler : DraftAttachmentCleanupScheduler {
        val enqueuedIds = mutableListOf<String>()
        val failIds = mutableSetOf<String>()

        override fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult =
            if (attachmentId in failIds) {
                RivenBackgroundScheduleResult.Failure(
                    RivenBackgroundScheduleError.SchedulerFailure("TEST", "ControlledFailure"),
                )
            } else {
                enqueuedIds += attachmentId
                RivenBackgroundScheduleResult.Enqueued(listOf("attachment-cleanup-$attachmentId"))
            }
    }
}
