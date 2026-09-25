package com.shai.riven.data.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationTimelineServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var service: ConversationTimelineService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = ConversationTimelineService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createAndAppendBuildsLinearActiveTimelineWithImmutableCreationOrder() = runBlocking {
        createConversation("conversation-1", updatedAt = 10)
        val initialHead = database.conversationTimelineDao().timelineHead("conversation-1")!!
        assertNull(initialHead.activeHeadMessageId)
        assertEquals(0L, initialHead.timelineRevision)

        val first = append("conversation-1", "message-1", MessageRole.USER, expectedRevision = 0, occurredAt = 11)
        val second = append("conversation-1", "message-2", MessageRole.ASSISTANT, expectedRevision = 1, occurredAt = 12)
        val third = append("conversation-1", "message-3", MessageRole.USER, expectedRevision = 2, occurredAt = 13)

        assertEquals(listOf(1L, 2L, 3L), listOf(first.sequenceNumber, second.sequenceNumber, third.sequenceNumber))
        assertNull(database.conversationTimelineDao().parentEdge("message-1"))
        assertEquals("message-1", database.conversationTimelineDao().parentEdge("message-2")?.parentMessageId)
        assertEquals("message-2", database.conversationTimelineDao().parentEdge("message-3")?.parentMessageId)
        assertEquals("message-3", database.conversationTimelineDao().timelineHead("conversation-1")?.activeHeadMessageId)
        assertEquals(3L, database.conversationTimelineDao().timelineHead("conversation-1")?.timelineRevision)
        assertEquals(
            listOf("message-1", "message-2", "message-3"),
            assertReadSuccess(service.activeTimeline("conversation-1")).messages.map { it.id },
        )
        assertEquals(
            listOf("message-1", "message-2", "message-3"),
            assertReadSuccess(service.allMessages("conversation-1")).messages.map { it.id },
        )

        val duplicateConversation = service.createConversationWithTimeline(
            createConversationInput("conversation-1", 14),
        )
        assertEquals(
            ConversationTimelineError.DuplicateConversationId("conversation-1"),
            assertWriteFailure(duplicateConversation),
        )
    }

    @Test
    fun rewindRetainsFormerSuffixAndAppendCreatesNewHigherSequenceBranch() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)
        append("conversation-1", "u2", MessageRole.USER, 2, 3)
        append("conversation-1", "a2", MessageRole.ASSISTANT, 3, 4)

        val rewind = assertRewound(
            service.rewindTo(
                RewindTimelineInput(
                    conversationId = "conversation-1",
                    targetMessageId = "u2",
                    expectedTimelineRevision = 4,
                    occurredAt = 5,
                ),
            ),
        )
        assertEquals(listOf("a2"), rewind.deactivatedMessageIds)
        assertEquals(5L, rewind.timelineRevision)
        assertEquals(listOf("u1", "a1", "u2"), activeIds("conversation-1"))
        assertNotNull(database.conversationTimelineDao().message("a2"))
        assertEquals("u2", database.conversationTimelineDao().parentEdge("a2")?.parentMessageId)
        assertEquals(MessageDeliveryState.PERSISTED, database.conversationTimelineDao().message("a2")?.deliveryState)

        val noOp = service.rewindTo(
            RewindTimelineInput("conversation-1", "u2", expectedTimelineRevision = 5, occurredAt = 6),
        )
        assertTrue(noOp is TimelineWriteResult.AlreadyAtRewindTarget)
        assertEquals(5L, database.conversationTimelineDao().timelineHead("conversation-1")?.timelineRevision)

        val appended = append("conversation-1", "u3", MessageRole.USER, 5, 7)
        assertEquals(5L, appended.sequenceNumber)
        assertEquals("u2", database.conversationTimelineDao().parentEdge("u3")?.parentMessageId)
        assertEquals(listOf("u1", "a1", "u2", "u3"), activeIds("conversation-1"))
        assertEquals(listOf("u1", "a1", "u2", "a2", "u3"), allIds("conversation-1"))
        assertFalse(activeIds("conversation-1").contains("a2"))
        assertTrue(database.maintenanceDao().suppressionTombstones().isEmpty())
    }

    @Test
    fun regenerateCreatesSiblingAlternativeAndPreservesOriginalResponse() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)

        val result = assertRegenerated(
            service.commitRegeneratedAssistantResponse(
                CommitRegeneratedAssistantResponseInput(
                    conversationId = "conversation-1",
                    originalMessageId = "a1",
                    replacement = newMessage("a1b", MessageRole.ASSISTANT, 3),
                    expectedTimelineRevision = 2,
                    occurredAt = 3,
                ),
            ),
        )

        assertEquals("a1", result.deactivatedMessageId)
        assertEquals("a1b", result.activatedMessageId)
        assertEquals(3L, result.sequenceNumber)
        assertEquals(3L, result.timelineRevision)
        assertEquals("u1", database.conversationTimelineDao().parentEdge("a1")?.parentMessageId)
        assertEquals("u1", database.conversationTimelineDao().parentEdge("a1b")?.parentMessageId)
        assertEquals(listOf("u1", "a1b"), activeIds("conversation-1"))
        assertEquals(listOf("u1", "a1", "a1b"), allIds("conversation-1"))
        assertNotNull(database.conversationTimelineDao().message("a1"))
        assertTrue(database.maintenanceDao().suppressionTombstones().isEmpty())
    }

    @Test
    fun staleRevisionRejectsAppendAndRegenerateWithoutPartialMutation() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)

        val staleAppend = service.appendMessage(
            AppendTimelineMessageInput(
                conversationId = "conversation-1",
                message = newMessage("u2", MessageRole.USER, 3),
                expectedTimelineRevision = 1,
                occurredAt = 3,
            ),
        )
        assertEquals(
            ConversationTimelineError.StaleTimelineRevision(expected = 1, actual = 2),
            assertWriteFailure(staleAppend),
        )
        assertNull(database.conversationTimelineDao().message("u2"))
        assertNull(database.conversationTimelineDao().parentEdge("u2"))

        val staleRegenerate = service.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                conversationId = "conversation-1",
                originalMessageId = "a1",
                replacement = newMessage("a1b", MessageRole.ASSISTANT, 3),
                expectedTimelineRevision = 1,
                occurredAt = 3,
            ),
        )
        assertEquals(
            ConversationTimelineError.StaleTimelineRevision(expected = 1, actual = 2),
            assertWriteFailure(staleRegenerate),
        )
        assertNull(database.conversationTimelineDao().message("a1b"))
        assertEquals("a1", database.conversationTimelineDao().timelineHead("conversation-1")?.activeHeadMessageId)
        assertEquals(2L, database.conversationTimelineDao().timelineHead("conversation-1")?.timelineRevision)
    }

    @Test
    fun invalidParentAssignmentsRejectSelfCrossConversationSecondParentAndCycle() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)
        createConversation("conversation-2")
        append("conversation-2", "other-u1", MessageRole.USER, 0, 1)

        assertEquals(
            ConversationTimelineError.SelfParent("u1"),
            assertWriteFailure(service.validateParentAssignment("u1", "u1")),
        )
        assertEquals(
            ConversationTimelineError.ParentBelongsToDifferentConversation("u1", "other-u1"),
            assertWriteFailure(service.validateParentAssignment("u1", "other-u1")),
        )
        assertEquals(
            ConversationTimelineError.ParentAlreadyAssigned("a1", "u1"),
            assertWriteFailure(service.validateParentAssignment("a1", "u1")),
        )
        assertEquals(
            ConversationTimelineError.CycleDetected("u1", "a1"),
            assertWriteFailure(service.validateParentAssignment("u1", "a1")),
        )
        assertNull(database.conversationTimelineDao().parentEdge("u1"))
        assertEquals("u1", database.conversationTimelineDao().parentEdge("a1")?.parentMessageId)
    }

    @Test
    fun rewindRejectsMessageThatIsRetainedButOffActivePath() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)
        append("conversation-1", "u2", MessageRole.USER, 2, 3)
        append("conversation-1", "a2", MessageRole.ASSISTANT, 3, 4)
        assertRewound(service.rewindTo(RewindTimelineInput("conversation-1", "u2", 4, 5)))
        append("conversation-1", "u3", MessageRole.USER, 5, 6)

        val result = service.rewindTo(
            RewindTimelineInput("conversation-1", "a2", expectedTimelineRevision = 6, occurredAt = 7),
        )

        assertEquals(
            ConversationTimelineError.MessageNotOnActiveTimeline("conversation-1", "a2"),
            assertWriteFailure(result),
        )
        assertEquals(listOf("u1", "a1", "u2", "u3"), activeIds("conversation-1"))
        assertNotNull(database.conversationTimelineDao().message("a2"))
        assertEquals(6L, database.conversationTimelineDao().timelineHead("conversation-1")?.timelineRevision)
    }

    @Test
    fun regenerateRejectsNonAssistantNonHeadWrongRoleAndRootAssistant() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        val nonAssistant = service.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation-1",
                "u1",
                newMessage("replacement-1", MessageRole.ASSISTANT, 2),
                1,
                2,
            ),
        )
        assertEquals(
            ConversationTimelineError.InvalidRegenerationTarget(
                "u1",
                InvalidRegenerationReason.ORIGINAL_NOT_ASSISTANT,
            ),
            assertWriteFailure(nonAssistant),
        )

        append("conversation-1", "a1", MessageRole.ASSISTANT, 1, 2)
        val wrongReplacementRole = service.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation-1",
                "a1",
                newMessage("replacement-2", MessageRole.USER, 3),
                2,
                3,
            ),
        )
        assertEquals(
            ConversationTimelineError.InvalidRegenerationTarget(
                "replacement-2",
                InvalidRegenerationReason.REPLACEMENT_NOT_ASSISTANT,
            ),
            assertWriteFailure(wrongReplacementRole),
        )

        append("conversation-1", "u2", MessageRole.USER, 2, 3)
        val nonHead = service.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation-1",
                "a1",
                newMessage("replacement-3", MessageRole.ASSISTANT, 4),
                3,
                4,
            ),
        )
        assertEquals(
            ConversationTimelineError.InvalidRegenerationTarget(
                "a1",
                InvalidRegenerationReason.ORIGINAL_NOT_ACTIVE_HEAD,
            ),
            assertWriteFailure(nonHead),
        )

        createConversation("root-assistant")
        append("root-assistant", "root-a1", MessageRole.ASSISTANT, 0, 1)
        val rootAlternative = service.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "root-assistant",
                "root-a1",
                newMessage("root-a1b", MessageRole.ASSISTANT, 2),
                1,
                2,
            ),
        )
        assertEquals(
            ConversationTimelineError.InvalidRegenerationTarget(
                "root-a1",
                InvalidRegenerationReason.ROOT_ALTERNATIVE_UNSUPPORTED,
            ),
            assertWriteFailure(rootAlternative),
        )
        assertNull(database.conversationTimelineDao().message("root-a1b"))
    }

    @Test
    fun activeTimelineRejectsHeadMessageFromAnotherConversation() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "message-1", MessageRole.USER, 0, 1)
        createConversation("conversation-2")
        append("conversation-2", "message-2", MessageRole.USER, 0, 1)
        val originalHead = database.conversationTimelineDao().timelineHead("conversation-1")!!
        database.conversationTimelineDao().updateTimelineHead(
            originalHead.copy(activeHeadMessageId = "message-2"),
        )

        val result = service.activeTimeline("conversation-1")

        assertEquals(
            ConversationTimelineError.TimelineHeadBelongsToDifferentConversation(
                conversationId = "conversation-1",
                headMessageId = "message-2",
                actualConversationId = "conversation-2",
            ),
            assertReadFailure(result),
        )
    }

    @Test
    fun controlledFailureAfterMessageAndEdgeWriteRollsBackEntireAppend() = runBlocking {
        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        val failingService = ConversationTimelineService(database) { operation ->
            if (operation == TimelineOperation.APPEND_MESSAGE) error("controlled failure")
        }

        val result = failingService.appendMessage(
            AppendTimelineMessageInput(
                conversationId = "conversation-1",
                message = newMessage("a1", MessageRole.ASSISTANT, 2),
                expectedTimelineRevision = 1,
                occurredAt = 2,
            ),
        )

        assertTrue(assertWriteFailure(result) is ConversationTimelineError.StorageFailure)
        assertNull(database.conversationTimelineDao().message("a1"))
        assertNull(database.conversationTimelineDao().parentEdge("a1"))
        val head = database.conversationTimelineDao().timelineHead("conversation-1")!!
        assertEquals("u1", head.activeHeadMessageId)
        assertEquals(1L, head.timelineRevision)
        assertEquals(listOf("u1"), allIds("conversation-1"))
    }

    @Test
    fun typedIntegrityErrorsCoverMissingHeadDuplicateMessageAndSequenceOverflow() = runBlocking {
        val missingConversation = service.appendMessage(
            AppendTimelineMessageInput(
                "missing",
                newMessage("message-missing", MessageRole.USER, 1),
                0,
                1,
            ),
        )
        assertEquals(
            ConversationTimelineError.MissingConversation("missing"),
            assertWriteFailure(missingConversation),
        )

        database.conversationDao().insertConversation(
            ConversationEntity(
                id = "missing-head",
                createdAt = 1,
                updatedAt = 1,
                status = ConversationStatus.ACTIVE,
            ),
        )
        val missingHead = service.appendMessage(
            AppendTimelineMessageInput(
                "missing-head",
                newMessage("message-1", MessageRole.USER, 1),
                0,
                1,
            ),
        )
        assertEquals(
            ConversationTimelineError.MissingTimelineHeadRecord("missing-head"),
            assertWriteFailure(missingHead),
        )

        createConversation("conversation-1")
        append("conversation-1", "u1", MessageRole.USER, 0, 1)
        val duplicate = service.appendMessage(
            AppendTimelineMessageInput(
                "conversation-1",
                newMessage("u1", MessageRole.USER, 2),
                1,
                2,
            ),
        )
        assertEquals(ConversationTimelineError.DuplicateMessageId("u1"), assertWriteFailure(duplicate))

        database.conversationDao().insertMessage(
            MessageEntity(
                id = "max-sequence",
                conversationId = "conversation-1",
                sequenceNumber = Long.MAX_VALUE,
                role = MessageRole.USER,
                deliveryState = MessageDeliveryState.PERSISTED,
                content = "Raw retained history at the sequence limit.",
                createdAt = 2,
                updatedAt = 2,
            ),
        )
        val overflow = service.appendMessage(
            AppendTimelineMessageInput(
                "conversation-1",
                newMessage("u2", MessageRole.USER, 3),
                1,
                3,
            ),
        )
        assertEquals(
            ConversationTimelineError.SequenceCollision("conversation-1", Long.MAX_VALUE),
            assertWriteFailure(overflow),
        )
        assertNull(database.conversationTimelineDao().message("u2"))
    }

    private suspend fun createConversation(id: String, updatedAt: Long = 0) {
        val result = service.createConversationWithTimeline(createConversationInput(id, updatedAt))
        assertTrue("Expected conversation creation but was $result", result is TimelineWriteResult.ConversationCreated)
    }

    private fun createConversationInput(id: String, updatedAt: Long) = CreateTimelineConversationInput(
        conversationId = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        status = ConversationStatus.ACTIVE,
    )

    private suspend fun append(
        conversationId: String,
        messageId: String,
        role: MessageRole,
        expectedRevision: Long,
        occurredAt: Long,
    ): TimelineWriteResult.MessageAppended = assertAppended(
        service.appendMessage(
            AppendTimelineMessageInput(
                conversationId = conversationId,
                message = newMessage(messageId, role, occurredAt),
                expectedTimelineRevision = expectedRevision,
                occurredAt = occurredAt,
            ),
        ),
    )

    private fun newMessage(id: String, role: MessageRole, at: Long) = NewTimelineMessageInput(
        messageId = id,
        role = role,
        deliveryState = MessageDeliveryState.PERSISTED,
        content = "Timeline content for $id.",
        createdAt = at,
        updatedAt = at,
    )

    private suspend fun activeIds(conversationId: String): List<String> =
        assertReadSuccess(service.activeTimeline(conversationId)).messages.map { it.id }

    private suspend fun allIds(conversationId: String): List<String> =
        assertReadSuccess(service.allMessages(conversationId)).messages.map { it.id }

    private fun assertAppended(result: TimelineWriteResult): TimelineWriteResult.MessageAppended {
        assertTrue("Expected append success but was $result", result is TimelineWriteResult.MessageAppended)
        return result as TimelineWriteResult.MessageAppended
    }

    private fun assertRewound(result: TimelineWriteResult): TimelineWriteResult.Rewound {
        assertTrue("Expected rewind success but was $result", result is TimelineWriteResult.Rewound)
        return result as TimelineWriteResult.Rewound
    }

    private fun assertRegenerated(result: TimelineWriteResult): TimelineWriteResult.AssistantRegenerated {
        assertTrue("Expected regenerate success but was $result", result is TimelineWriteResult.AssistantRegenerated)
        return result as TimelineWriteResult.AssistantRegenerated
    }

    private fun assertWriteFailure(result: TimelineWriteResult): ConversationTimelineError {
        assertTrue("Expected write failure but was $result", result is TimelineWriteResult.Failure)
        return (result as TimelineWriteResult.Failure).error
    }

    private fun assertReadSuccess(result: TimelineReadResult): TimelineReadResult.Success {
        assertTrue("Expected read success but was $result", result is TimelineReadResult.Success)
        return result as TimelineReadResult.Success
    }

    private fun assertReadFailure(result: TimelineReadResult): ConversationTimelineError {
        assertTrue("Expected read failure but was $result", result is TimelineReadResult.Failure)
        return (result as TimelineReadResult.Failure).error
    }
}
