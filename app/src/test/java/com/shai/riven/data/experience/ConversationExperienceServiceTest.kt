package com.shai.riven.data.experience

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineError
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.memory.intent.ManualMemoryIntentIdGenerator
import com.shai.riven.data.memory.intent.ManualMemoryIntentResult
import com.shai.riven.data.memory.intent.ManualMemoryIntentService
import com.shai.riven.data.memory.intent.ManualRememberMemoryInput
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.MessageParentEdgeEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class ConversationExperienceServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var timeline: ConversationTimelineService
    private lateinit var experiences: ConversationExperienceService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = ConversationTimelineService(database)
        experiences = ConversationExperienceService(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun userPersistedMessageCreatesExactShaiConversationExperienceAndPrimarySource() = runBlocking {
        createConversation()
        append("user", MessageRole.USER, MessageDeliveryState.PERSISTED, "  exact\ncontent  ", 11, 99)

        val experience = experienceFor("user")
        assertEquals(ExperienceType.CONVERSATION_MESSAGE, experience.experienceType)
        assertEquals(ExperienceActor.SHAI, experience.actor)
        assertEquals("  exact\ncontent  ", experience.sourceContent)
        assertEquals(11L, experience.occurredAt)
        assertEquals(99L, experience.recordedAt)
        assertEquals(SensitivityLevel.STANDARD, experience.sensitivity)
        assertEquals(ExperienceAvailability.AVAILABLE, experience.availability)
        assertNull(experience.actorEntityId)
        val source = database.memoryDao().messageSourcesForExperience(experience.id).single()
        assertEquals("user", source.messageId)
        assertEquals(0, source.sourceOrder)
        assertEquals(ExperienceMessageSourceRole.PRIMARY, source.sourceRole)
        assertNull(source.characterStart)
        assertNull(source.characterEnd)
        assertEquals(99L, source.createdAt)
    }

    @Test
    fun assistantSucceededMessageCreatesRivenConversationExperience() = runBlocking {
        createConversation()
        append("assistant", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED)
        assertEquals(ExperienceActor.RIVEN, experienceFor("assistant").actor)
    }

    @Test
    fun toolSucceededMessageCreatesToolResultExperience() = runBlocking {
        createConversation()
        append("tool", MessageRole.TOOL, MessageDeliveryState.SUCCEEDED)
        val experience = experienceFor("tool")
        assertEquals(ExperienceType.TOOL_RESULT, experience.experienceType)
        assertEquals(ExperienceActor.TOOL, experience.actor)
    }

    @Test
    fun systemMessageCreatesNoAutomaticExperience() = runBlocking {
        createConversation()
        append("system", MessageRole.SYSTEM, MessageDeliveryState.PERSISTED)
        assertTrue(experiences.conversationExperienceForMessage("system") is ConversationExperienceLookupResult.NotRecorded)
    }

    @Test
    fun pendingMessageCreatesNoAutomaticExperience() = runBlocking {
        assertIneligible(MessageDeliveryState.PENDING)
    }

    @Test
    fun failedMessageCreatesNoAutomaticExperience() = runBlocking {
        assertIneligible(MessageDeliveryState.FAILED)
    }

    @Test
    fun cancelledMessageCreatesNoAutomaticExperience() = runBlocking {
        assertIneligible(MessageDeliveryState.CANCELLED)
    }

    @Test
    fun controlledExperienceFailureRollsBackMessageGraphAttachmentHeadAndExperience() = runBlocking {
        createConversation()
        insertAttachment("attachment")
        val failing = ConversationTimelineService(
            database,
            afterConversationExperienceWrite = { error("controlled") },
        )
        val result = failing.appendMessage(
            AppendTimelineMessageInput(
                "conversation",
                message("message", MessageRole.USER, MessageDeliveryState.PERSISTED, attachmentIds = listOf("attachment")),
                0,
                10,
            ),
        )
        assertTrue(result is TimelineWriteResult.Failure)
        assertNull(database.conversationTimelineDao().message("message"))
        assertNull(database.conversationTimelineDao().parentEdge("message"))
        assertEquals(0, database.attachmentDao().messageReferenceCount("attachment"))
        assertNull(database.conversationTimelineDao().timelineHead("conversation")?.activeHeadMessageId)
        assertEquals(0L, database.conversationTimelineDao().timelineHead("conversation")?.timelineRevision)
        assertEquals(0, database.memoryDao().experienceCount())
    }

    @Test
    fun automaticExperienceSharesSequentialEventOrderWithManualMemoryIntent() = runBlocking {
        val manual = ManualMemoryIntentService(
            database = database,
            backgroundScheduler = NoOpScheduler,
            idGenerator = object : ManualMemoryIntentIdGenerator {
                override fun nextExperienceId() = "manual-experience"
                override fun nextMemoryId() = "manual-memory"
            },
        )
        val remembered = manual.remember(
            ManualRememberMemoryInput(
                meaning = "remembered",
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                certainty = MemoryCertainty.CERTAIN,
                sensitivity = SensitivityLevel.STANDARD,
                occurredAt = 1,
            ),
        )
        assertTrue(remembered is ManualMemoryIntentResult.Remembered)
        createConversation()
        append("message", MessageRole.USER, MessageDeliveryState.PERSISTED)
        assertEquals(1L, database.memoryDao().experience("manual-experience")?.eventOrder)
        assertEquals(2L, experienceFor("message").eventOrder)
    }

    @Test
    fun eventOrderOverflowReturnsTypedFailureWithoutPartialAppend() = runBlocking {
        database.memoryDao().insertExperience(experience("maximum", Long.MAX_VALUE))
        createConversation()
        val result = timeline.appendMessage(AppendTimelineMessageInput("conversation", message("message"), 0, 1))
        assertEquals(
            ConversationTimelineError.ExperienceOrderOverflow,
            (result as TimelineWriteResult.Failure).error,
        )
        assertNull(database.conversationTimelineDao().message("message"))
        assertEquals(1, database.memoryDao().experienceCount())
    }

    @Test
    fun regenerateCreatesReplacementExperienceAndRetainsOriginalExperience() = runBlocking {
        createConversation()
        append("user", MessageRole.USER, MessageDeliveryState.PERSISTED, occurredAt = 1)
        append("old", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, occurredAt = 2)
        val oldExperience = experienceFor("old")
        val result = timeline.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation",
                "old",
                message("new", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, at = 3),
                2,
                3,
            ),
        )
        assertTrue(result is TimelineWriteResult.AssistantRegenerated)
        assertNotNull(database.memoryDao().experience(oldExperience.id))
        assertTrue(experienceFor("new").id != oldExperience.id)
    }

    @Test
    fun rewindDoesNotDeleteOrRewriteHistoricalExperience() = runBlocking {
        createConversation()
        append("one", occurredAt = 1)
        append("two", occurredAt = 2)
        val before = experienceFor("two")
        assertTrue(timeline.rewindTo(RewindTimelineInput("conversation", "one", 2, 3)) is TimelineWriteResult.Rewound)
        assertEquals(before, database.memoryDao().experience(before.id))
    }

    @Test
    fun catchUpCreatesMissingEligibleExperiencesInActiveTimelineOrder() = runBlocking {
        createHistoricalConversation(
            message("one", at = 1),
            message("two", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, at = 2),
        )
        val result = experiences.ensureActiveConversationExperiences("conversation", 50, 10)
            as EnsureConversationExperiencesResult.Ensured
        assertEquals(2, result.createdExperienceIds.size)
        assertEquals(listOf(1L, 2L), result.createdExperienceIds.map { database.memoryDao().experience(it)!!.eventOrder })
    }

    @Test
    fun catchUpIsIdempotent() = runBlocking {
        createHistoricalConversation(message("one"))
        val first = experiences.ensureActiveConversationExperiences("conversation", 50, 10)
            as EnsureConversationExperiencesResult.Ensured
        val second = experiences.ensureActiveConversationExperiences("conversation", 60, 10)
            as EnsureConversationExperiencesResult.Ensured
        assertEquals(1, first.createdExperienceIds.size)
        assertEquals(first.createdExperienceIds, second.alreadyRecordedExperienceIds)
        assertTrue(second.createdExperienceIds.isEmpty())
        assertEquals(1, database.memoryDao().experienceCount())
    }

    @Test
    fun catchUpProgressesAcrossMultipleBatches() = runBlocking {
        createHistoricalConversation(*(1..5).map { message("message-$it", at = it.toLong()) }.toTypedArray())

        val first = ensured(limit = 2)
        val second = ensured(limit = 2)
        val third = ensured(limit = 2)
        val fourth = ensured(limit = 2)

        assertEquals(2, first.createdExperienceIds.size)
        assertTrue(first.truncated)
        assertEquals(2, second.createdExperienceIds.size)
        assertTrue(second.truncated)
        assertEquals(1, third.createdExperienceIds.size)
        assertTrue(!third.truncated)
        assertTrue(fourth.createdExperienceIds.isEmpty())
        assertTrue(!fourth.truncated)
        val canonical = (1..5).map { database.memoryDao().canonicalConversationExperiencesForMessage("message-$it").single() }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), canonical.map { it.eventOrder })
        canonical.forEachIndexed { index, experience ->
            val sources = database.memoryDao().messageSourcesForExperience(experience.id)
            assertEquals("message-${index + 1}", sources.single().messageId)
        }
    }

    @Test
    fun catchUpSkippedPrefixDoesNotStarveLaterMissingMessages() = runBlocking {
        createHistoricalConversation(
            message("already", at = 1),
            message("system", role = MessageRole.SYSTEM, at = 2),
            message("pending", delivery = MessageDeliveryState.PENDING, at = 3),
            message("later-one", at = 4),
            message("later-two", at = 5),
        )

        assertTrue(ensured(limit = 1).truncated)
        val second = ensured(limit = 1)
        val third = ensured(limit = 1)

        assertEquals(listOf("later-one"), second.createdExperienceIds.map(::messageIdForExperience))
        assertTrue(second.truncated)
        assertEquals(listOf("later-two"), third.createdExperienceIds.map(::messageIdForExperience))
        assertTrue(!third.truncated)
        assertTrue(second.inspectedMessageCount > 1)
        assertEquals(3, database.memoryDao().experienceCount())
    }

    @Test
    fun existingCanonicalExperienceWithWrongActorFailsLookupAndCatchUpTyped() = runBlocking {
        createHistoricalConversation(message("message", at = 1))
        insertApparentCanonical("message", actor = ExperienceActor.RIVEN)

        assertInvalidCanonical("message", InvalidCanonicalExperienceReason.ACTOR_MISMATCH)
        val catchUp = experiences.ensureActiveConversationExperiences("conversation", 10, 1)
            as EnsureConversationExperiencesResult.Failure
        assertEquals(
            ConversationExperienceError.InvalidCanonicalExperience(
                "message",
                InvalidCanonicalExperienceReason.ACTOR_MISMATCH,
            ),
            catchUp.error,
        )
        assertEquals(1, database.memoryDao().experienceCount())
    }

    @Test
    fun existingCanonicalExperienceWithWrongTypeFailsTyped() = runBlocking {
        createHistoricalConversation(message("message", at = 1))
        insertApparentCanonical("message", type = ExperienceType.TOOL_RESULT)
        assertInvalidCanonical("message", InvalidCanonicalExperienceReason.TYPE_MISMATCH)
    }

    @Test
    fun existingCanonicalExperienceWithWrongSourceContentFailsTyped() = runBlocking {
        createHistoricalConversation(message("message", content = "exact", at = 1))
        insertApparentCanonical("message", sourceContent = "different")
        assertInvalidCanonical("message", InvalidCanonicalExperienceReason.SOURCE_CONTENT_MISMATCH)
    }

    @Test
    fun existingCanonicalExperienceWithExtraSourceRowFailsTyped() = runBlocking {
        createHistoricalConversation(message("message", at = 1), message("extra", at = 2))
        insertApparentCanonical("message", extraMessageId = "extra")
        assertInvalidCanonical("message", InvalidCanonicalExperienceReason.SOURCE_COUNT_MISMATCH)
    }

    @Test
    fun deletedValidCanonicalExperienceIsAcceptedWithoutResurrection() = runBlocking {
        createHistoricalConversation(message("message", at = 1))
        insertApparentCanonical("message", availability = ExperienceAvailability.DELETED)

        val lookup = experiences.conversationExperienceForMessage("message")
        assertTrue(lookup is ConversationExperienceLookupResult.Found)
        val catchUp = ensured(limit = 1)
        assertTrue(catchUp.createdExperienceIds.isEmpty())
        assertEquals(1, database.memoryDao().experienceCount())
    }

    @Test
    fun ineligibleMessageWithApparentCanonicalExperienceFailsTyped() = runBlocking {
        createHistoricalConversation(message("message", delivery = MessageDeliveryState.PENDING, at = 1))
        insertApparentCanonical("message")

        assertInvalidCanonical("message", InvalidCanonicalExperienceReason.MESSAGE_NOT_ELIGIBLE)
        val catchUp = experiences.ensureActiveConversationExperiences("conversation", 10, 1)
            as EnsureConversationExperiencesResult.Failure
        assertEquals(
            ConversationExperienceError.InvalidCanonicalExperience(
                "message",
                InvalidCanonicalExperienceReason.MESSAGE_NOT_ELIGIBLE,
            ),
            catchUp.error,
        )
    }

    @Test
    fun catchUpIgnoresInactiveBranch() = runBlocking {
        createHistoricalConversation(message("root", at = 1), message("active", at = 2))
        database.conversationTimelineDao().insertMessage(message("inactive", at = 3).toEntity(3))
        database.conversationTimelineDao().insertParentEdge(MessageParentEdgeEntity("inactive", "root", 3))
        experiences.ensureActiveConversationExperiences("conversation", 50, 10)
        assertTrue(experiences.conversationExperienceForMessage("inactive") is ConversationExperienceLookupResult.NotRecorded)
    }

    @Test
    fun catchUpIgnoresIneligibleMessagesAndSystemMessages() = runBlocking {
        createHistoricalConversation(
            message("pending", delivery = MessageDeliveryState.PENDING, at = 1),
            message("system", role = MessageRole.SYSTEM, at = 2),
            message("eligible", at = 3),
        )
        experiences.ensureActiveConversationExperiences("conversation", 50, 10)
        assertEquals(1, database.memoryDao().experienceCount())
        assertEquals("eligible", database.memoryDao().messageSourcesForExperience(experienceFor("eligible").id).single().messageId)
    }

    @Test
    fun automaticExperienceCreatesNoCandidateOrMemory() = runBlocking {
        createConversation()
        append("message")
        assertEquals(1, database.memoryDao().experienceCount())
        assertEquals(0, database.memoryDao().candidateMemoryCount())
        assertEquals(0, database.memoryDao().memoryCount())
    }

    private suspend fun assertIneligible(delivery: MessageDeliveryState) {
        createConversation()
        append("message", MessageRole.USER, delivery)
        assertTrue(experiences.conversationExperienceForMessage("message") is ConversationExperienceLookupResult.NotRecorded)
    }

    private suspend fun createConversation() {
        val result = timeline.createConversationWithTimeline(
            CreateTimelineConversationInput("conversation", 0, 0, ConversationStatus.ACTIVE),
        )
        assertTrue(result is TimelineWriteResult.ConversationCreated)
    }

    private suspend fun append(
        id: String,
        role: MessageRole = MessageRole.USER,
        delivery: MessageDeliveryState = MessageDeliveryState.PERSISTED,
        content: String = id,
        createdAt: Long = 1,
        occurredAt: Long = createdAt,
    ) {
        val revision = database.conversationTimelineDao().timelineHead("conversation")!!.timelineRevision
        val result = timeline.appendMessage(
            AppendTimelineMessageInput(
                "conversation",
                message(id, role, delivery, content, createdAt),
                revision,
                occurredAt,
            ),
        )
        assertTrue("Expected append but was $result", result is TimelineWriteResult.MessageAppended)
    }

    private fun message(
        id: String,
        role: MessageRole = MessageRole.USER,
        delivery: MessageDeliveryState = MessageDeliveryState.PERSISTED,
        content: String = id,
        at: Long = 1,
        attachmentIds: List<String> = emptyList(),
    ) = NewTimelineMessageInput(id, role, delivery, content, at, at, attachmentIds = attachmentIds)

    private fun NewTimelineMessageInput.toEntity(sequence: Long) = MessageEntity(
        messageId,
        "conversation",
        sequence,
        role,
        deliveryState,
        content,
        createdAt,
        updatedAt,
        providerName,
        providerModel,
        providerRequestId,
        errorCode,
    )

    private fun createHistoricalConversation(vararg messages: NewTimelineMessageInput) {
        val dao = database.conversationTimelineDao()
        dao.insertConversation(com.shai.riven.data.persistence.entity.ConversationEntity("conversation", 0, 0, ConversationStatus.ACTIVE))
        messages.forEachIndexed { index, input ->
            dao.insertMessage(input.toEntity(index + 1L))
            if (index > 0) dao.insertParentEdge(MessageParentEdgeEntity(input.messageId, messages[index - 1].messageId, input.createdAt))
        }
        dao.insertTimelineHead(
            ConversationTimelineHeadEntity("conversation", messages.lastOrNull()?.messageId, 0, 0),
        )
    }

    private suspend fun experienceFor(messageId: String): ExperienceEntity {
        val result = experiences.conversationExperienceForMessage(messageId)
        assertTrue("Expected Experience for $messageId but was $result", result is ConversationExperienceLookupResult.Found)
        return (result as ConversationExperienceLookupResult.Found).experience
    }

    private suspend fun ensured(limit: Int): EnsureConversationExperiencesResult.Ensured =
        experiences.ensureActiveConversationExperiences("conversation", recordedAt = 10, limit = limit)
            as EnsureConversationExperiencesResult.Ensured

    private fun messageIdForExperience(experienceId: String): String =
        database.memoryDao().messageSourcesForExperience(experienceId).single().messageId

    private suspend fun assertInvalidCanonical(
        messageId: String,
        reason: InvalidCanonicalExperienceReason,
    ) {
        val result = experiences.conversationExperienceForMessage(messageId)
            as ConversationExperienceLookupResult.Failure
        assertEquals(ConversationExperienceError.InvalidCanonicalExperience(messageId, reason), result.error)
    }

    private fun insertApparentCanonical(
        messageId: String,
        type: ExperienceType = ExperienceType.CONVERSATION_MESSAGE,
        actor: ExperienceActor = ExperienceActor.SHAI,
        sourceContent: String = messageId,
        availability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
        extraMessageId: String? = null,
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = "apparent-$messageId",
                eventOrder = 1,
                experienceType = type,
                actor = actor,
                sourceContent = sourceContent,
                occurredAt = 1,
                recordedAt = 10,
                sensitivity = SensitivityLevel.STANDARD,
                availability = availability,
            ),
        )
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = "apparent-$messageId",
                messageId = messageId,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                createdAt = 10,
            ),
        )
        extraMessageId?.let {
            database.memoryDao().insertExperienceMessageSource(
                ExperienceMessageSourceEntity(
                    experienceId = "apparent-$messageId",
                    messageId = it,
                    sourceOrder = 1,
                    sourceRole = ExperienceMessageSourceRole.SUPPORTING,
                    createdAt = 10,
                ),
            )
        }
    }

    private fun experience(id: String, order: Long) = ExperienceEntity(
        id = id,
        eventOrder = order,
        experienceType = ExperienceType.OTHER,
        actor = ExperienceActor.OTHER,
        occurredAt = 1,
        recordedAt = 1,
        sensitivity = SensitivityLevel.STANDARD,
        availability = ExperienceAvailability.AVAILABLE,
    )

    private fun insertAttachment(id: String) = database.attachmentDao().insertAttachment(
        AttachmentEntity(
            id,
            AttachmentKind.IMAGE,
            "image/png",
            AttachmentState.AVAILABLE,
            "attachments/$id.blob",
            1,
            "a".repeat(64),
            AttachmentSource.SHAI_IMPORT,
            1,
            1,
        ),
    )

    private object NoOpScheduler : RivenBackgroundWorkScheduler {
        private val result = RivenBackgroundScheduleResult.Enqueued(emptyList())
        override fun enqueueAttachmentCleanup(attachmentId: String) = result
        override fun enqueueAttachmentMaintenanceSweep() = result
        override fun enqueueRepairJob(repairJobId: String) = result
        override fun enqueueRepairSweep() = result
        override fun ensurePeriodicMaintenance() = result
    }
}
