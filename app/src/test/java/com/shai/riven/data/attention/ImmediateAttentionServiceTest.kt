package com.shai.riven.data.attention

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.experience.ConversationExperienceLookupResult
import com.shai.riven.data.experience.ConversationExperienceService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ConversationDraftEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ShaiSystemInstructionsEntity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImmediateAttentionServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var timeline: ConversationTimelineService
    private lateinit var experienceService: ConversationExperienceService
    private lateinit var analyzer: RecordingAnalyzer
    private lateinit var service: ImmediateAttentionService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = ConversationTimelineService(database)
        experienceService = ConversationExperienceService(database)
        analyzer = RecordingAnalyzer()
        service = ImmediateAttentionService(database, analyzer)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun forwardAssessmentPersistsOutcomeSignalAndRevisionOne() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = forward(PositiveAttentionSignal.IDENTITY)
        val persisted = persisted(service.assess(AssessImmediateAttentionInput(id, 0, 10)))
        assertEquals(AttentionOutcome.FORWARD_FOR_INTERPRETATION, persisted.outcome)
        assertEquals(setOf(PositiveAttentionSignal.IDENTITY), persisted.positiveSignals)
        assertEquals(1L, persisted.revision)
        assertNotNull(database.experienceAttentionDao().assessment(id))
    }

    @Test
    fun noCandidateAssessmentPersists() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = ImmediateAttentionProposal(AttentionOutcome.NO_CANDIDATE)
        assertEquals(AttentionOutcome.NO_CANDIDATE, persisted(assess(id)).outcome)
    }

    @Test
    fun deferForContextAssessmentPersists() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = ImmediateAttentionProposal(AttentionOutcome.DEFER_FOR_CONTEXT)
        assertEquals(AttentionOutcome.DEFER_FOR_CONTEXT, persisted(assess(id)).outcome)
    }

    @Test
    fun multiplePositiveSignalsPersistTogether() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = forward(
            PositiveAttentionSignal.PREFERENCE,
            PositiveAttentionSignal.RELATIONSHIP,
            PositiveAttentionSignal.PRACTICAL_SIGNIFICANCE,
        )
        assertEquals(analyzer.proposal.positiveSignals.toSet(), persisted(assess(id)).positiveSignals)
    }

    @Test
    fun antiSignalsPersistWithSourcePolarity() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = ImmediateAttentionProposal(
            AttentionOutcome.NO_CANDIDATE,
            antiSignals = listOf(
                AttentionAntiSignal.CONVERSATIONAL_FILLER,
                AttentionAntiSignal.ONE_OFF_INCIDENTAL_DETAIL,
            ),
        )
        val result = persisted(assess(id))
        assertEquals(analyzer.proposal.antiSignals.toSet(), result.antiSignals)
        assertTrue(result.positiveSignals.isEmpty())
    }

    @Test
    fun reassessmentAtomicallyReplacesSignalsAndIncrementsRevision() = runBlocking {
        val id = sourceExperience()
        analyzer.proposal = forward(PositiveAttentionSignal.IDENTITY)
        assess(id)
        analyzer.proposal = ImmediateAttentionProposal(
            AttentionOutcome.NO_CANDIDATE,
            antiSignals = listOf(AttentionAntiSignal.DUPLICATE_RESTATEMENT),
        )
        val second = persisted(service.assess(AssessImmediateAttentionInput(id, 1, 20)))
        assertEquals(2L, second.revision)
        assertTrue(second.positiveSignals.isEmpty())
        assertEquals(setOf(AttentionAntiSignal.DUPLICATE_RESTATEMENT), second.antiSignals)
        assertEquals(1, database.experienceAttentionDao().signals(id).size)
    }

    @Test
    fun staleAttentionRevisionDoesNotMutatePersistedAssessment() = runBlocking {
        val id = sourceExperience()
        assess(id)
        val before = database.experienceAttentionDao().assessment(id)
        val failure = failure(service.assess(AssessImmediateAttentionInput(id, 0, 20)))
        assertTrue(failure is ImmediateAttentionError.StaleAttentionRevision)
        assertEquals(before, database.experienceAttentionDao().assessment(id))
    }

    @Test
    fun attentionRevisionOverflowDoesNotMutateAssessment() = runBlocking {
        val id = sourceExperience()
        database.experienceAttentionDao().insertAssessment(
            ExperienceAttentionAssessmentEntity(id, AttentionOutcome.NO_CANDIDATE, Long.MAX_VALUE, 1, 1),
        )
        val failure = failure(service.assess(AssessImmediateAttentionInput(id, Long.MAX_VALUE, 20)))
        assertEquals(ImmediateAttentionError.AttentionRevisionOverflow(id), failure)
        assertEquals(Long.MAX_VALUE, database.experienceAttentionDao().assessment(id)?.revision)
    }

    @Test
    fun readMissingAssessmentReturnsTypedNoAssessment() = runBlocking {
        val id = sourceExperience()
        assertEquals(ReadAttentionAssessmentResult.NoAssessment(id), service.readAssessment(id))
    }

    @Test
    fun manualMemoryIntentIsRejectedWithoutAssessment() = runBlocking {
        val id = insertExperience("manual", ExperienceType.MANUAL_MEMORY_INTENT)
        assertEquals(ImmediateAttentionError.AlreadyConsumedManualMemoryIntent(id), failure(assess(id)))
        assertEquals(0L, rowCount("experience_attention_assessments"))
    }

    @Test
    fun deletedExperienceIsRejectedWithoutAssessment() = runBlocking {
        val id = insertExperience("deleted", availability = ExperienceAvailability.DELETED)
        assertEquals(ImmediateAttentionError.ExperienceUnavailable(id), failure(assess(id)))
        assertEquals(0L, rowCount("experience_attention_assessments"))
    }

    @Test
    fun analyzerFailureIsTypedAndPersistsNothing() = runBlocking {
        val id = sourceExperience()
        analyzer.failure = IllegalStateException("must not escape")
        assertEquals(ImmediateAttentionError.AnalyzerFailure("IllegalStateException"), failure(assess(id)))
        assertEquals(0L, rowCount("experience_attention_assessments"))
        assertEquals(0L, rowCount("experience_attention_signals"))
    }

    @Test(expected = CancellationException::class)
    fun analyzerCancellationPropagates() = runBlocking {
        val id = sourceExperience()
        analyzer.failure = CancellationException("cancel")
        assess(id)
        Unit
    }

    @Test
    fun analyzerReceivesActiveSourceWithExactExperienceFields() = runBlocking {
        createConversation()
        append("source", content = "  exact source\n  ", createdAt = 7, occurredAt = 9)
        val id = experienceId("source")
        assess(id)
        val snapshot = checkNotNull(analyzer.snapshots.single())
        assertEquals(id, snapshot.experienceId)
        assertEquals("  exact source\n  ", snapshot.sourceContent)
        assertEquals(7L, snapshot.occurredAt)
        assertEquals(MessageRole.USER, snapshot.sourceMessage?.role)
        assertEquals("source", snapshot.sourceMessage?.messageId)
    }

    @Test
    fun analyzerContextUsesOnlyPrecedingActiveMessages() = runBlocking {
        createConversation()
        append("one", content = "first")
        append("two", content = "second")
        append("source", content = "source")
        assess(experienceId("source"))
        assertEquals(listOf("one", "two"), analyzer.snapshots.single().precedingActiveContext.map { it.messageId })
    }

    @Test
    fun analyzerContextDoesNotLeakFutureMessages() = runBlocking {
        createConversation()
        append("source")
        val id = experienceId("source")
        append("future")
        assess(id)
        assertTrue(analyzer.snapshots.single().precedingActiveContext.isEmpty())
    }

    @Test
    fun analyzerContextExcludesInactiveBranchMessages() = runBlocking {
        createConversation()
        append("root")
        append("inactive")
        rewind("root")
        append("source")
        assess(experienceId("source"))
        assertEquals(listOf("root"), analyzer.snapshots.single().precedingActiveContext.map { it.messageId })
    }

    @Test
    fun analyzerContextExcludesDraftContent() = runBlocking {
        createConversation()
        database.conversationDraftDao().insertDraft(
            ConversationDraftEntity("conversation", "secret draft", 1, 1, 1),
        )
        append("source")
        assess(experienceId("source"))
        val snapshot = analyzer.snapshots.single()
        assertFalse(snapshot.precedingActiveContext.any { it.content == "secret draft" })
        assertTrue(snapshot.sourceContent != "secret draft")
    }

    @Test
    fun contextMessageLimitKeepsNearestTwelveMessages() = runBlocking {
        createConversation()
        repeat(14) { append("m$it") }
        append("source")
        assess(experienceId("source"))
        val ids = analyzer.snapshots.single().precedingActiveContext.map { it.messageId }
        assertEquals(12, ids.size)
        assertEquals("m2", ids.first())
        assertEquals("m13", ids.last())
    }

    @Test
    fun contextCharacterLimitPreservesOnlyNearestWholeMessages() = runBlocking {
        createConversation()
        append("old", content = "o".repeat(20_000))
        append("near", content = "n".repeat(20_000))
        append("source")
        assess(experienceId("source"))
        val context = analyzer.snapshots.single().precedingActiveContext
        assertEquals(listOf("near"), context.map { it.messageId })
        assertTrue(context.sumOf { it.content.length } <= MAX_ATTENTION_CONTEXT_CHARS)
    }

    @Test
    fun attachmentMetadataPreservesMessageOrder() = runBlocking {
        createConversation()
        insertAttachment("b", AttachmentKind.FILE, "text/plain", AttachmentSource.RIVEN_GENERATED)
        insertAttachment("a", AttachmentKind.IMAGE, "image/png", AttachmentSource.SHAI_IMPORT)
        append("source", attachmentIds = listOf("b", "a"))
        assess(experienceId("source"))
        val attachments = analyzer.snapshots.single().sourceMessage!!.attachments
        assertEquals(listOf("b", "a"), attachments.map { it.attachmentId })
        assertEquals(listOf(AttachmentKind.FILE, AttachmentKind.IMAGE), attachments.map { it.kind })
    }

    @Test
    fun attachmentOnlyMessageCanBeAssessed() = runBlocking {
        createConversation()
        insertAttachment("image")
        append("source", content = "", attachmentIds = listOf("image"))
        analyzer.proposal = ImmediateAttentionProposal(AttentionOutcome.DEFER_FOR_CONTEXT)
        assertEquals(AttentionOutcome.DEFER_FOR_CONTEXT, persisted(assess(experienceId("source"))).outcome)
    }

    @Test
    fun rewindBetweenSnapshotAndPersistRejectsStaleProposal() = runBlocking {
        createConversation()
        append("root")
        append("source")
        val id = experienceId("source")
        analyzer.beforeReturn = { rewind("root") }
        val error = failure(assess(id))
        assertTrue(error is ImmediateAttentionError.StaleAttentionContext)
        assertEquals(0L, rowCount("experience_attention_assessments"))
    }

    @Test
    fun regenerateBetweenSnapshotAndPersistRejectsStaleProposal() = runBlocking {
        createConversation()
        append("root", role = MessageRole.USER)
        append("source", role = MessageRole.ASSISTANT, delivery = MessageDeliveryState.SUCCEEDED)
        val id = experienceId("source")
        analyzer.beforeReturn = {
            val result = timeline.commitRegeneratedAssistantResponse(
                CommitRegeneratedAssistantResponseInput(
                    "conversation",
                    "source",
                    message("replacement", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, 3),
                    2,
                    3,
                ),
            )
            assertTrue(result is TimelineWriteResult.AssistantRegenerated)
        }
        assertTrue(failure(assess(id)) is ImmediateAttentionError.StaleAttentionContext)
        assertEquals(0L, rowCount("experience_attention_assessments"))
    }

    @Test
    fun actionableForwardBecomesInactiveAfterRewindButAssessmentRemains() = runBlocking {
        createConversation()
        append("root")
        append("source")
        val id = experienceId("source")
        assess(id)
        rewind("root")
        val result = service.readActionableForwardAssessment(id)
        assertTrue((result as ReadActionableForwardAssessmentResult.Failure).error is ImmediateAttentionError.InactiveConversationSource)
        assertNotNull(database.experienceAttentionDao().assessment(id))
    }

    @Test
    fun actionableForwardBecomesInactiveAfterRegenerate() = runBlocking {
        createConversation()
        append("root")
        append("source", role = MessageRole.ASSISTANT, delivery = MessageDeliveryState.SUCCEEDED)
        val id = experienceId("source")
        assess(id)
        val result = timeline.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                "conversation",
                "source",
                message("replacement", MessageRole.ASSISTANT, MessageDeliveryState.SUCCEEDED, 3),
                2,
                3,
            ),
        )
        assertTrue(result is TimelineWriteResult.AssistantRegenerated)
        val read = service.readActionableForwardAssessment(id) as ReadActionableForwardAssessmentResult.Failure
        assertTrue(read.error is ImmediateAttentionError.InactiveConversationSource)
    }

    @Test
    fun activeForwardAssessmentRemainsActionableWithGroundedSnapshot() = runBlocking {
        val id = sourceExperience()
        assess(id)
        val result = service.readActionableForwardAssessment(id)
            as ReadActionableForwardAssessmentResult.Actionable
        assertEquals(id, result.assessment.experienceId)
        assertEquals("source", result.snapshot.sourceMessage?.messageId)
    }

    @Test
    fun timelineReadHelpersDoNotMutateAnyRows() = runBlocking {
        sourceExperience()
        val before = allTableCounts()
        timeline.activeTimeline("conversation")
        timeline.allMessages("conversation")
        assertEquals(before, allTableCounts())
    }

    @Test
    fun attentionCreatesZeroCandidateRows() = runBlocking {
        assess(sourceExperience())
        assertEquals(0L, rowCount("candidate_memories"))
        assertEquals(0L, rowCount("candidate_memory_evidence"))
    }

    @Test
    fun attentionCreatesZeroMemoryRows() = runBlocking {
        assess(sourceExperience())
        assertEquals(0L, rowCount("memories"))
        assertEquals(0L, rowCount("memory_evidence"))
    }

    @Test
    fun attentionCreatesZeroOpenLoops() = runBlocking {
        assess(sourceExperience())
        assertEquals(0L, rowCount("open_loops"))
    }

    @Test
    fun attentionCreatesZeroRepairJobs() = runBlocking {
        assess(sourceExperience())
        assertEquals(0L, rowCount("repair_jobs"))
    }

    @Test
    fun attentionDoesNotModifySystemInstructions() = runBlocking {
        val id = sourceExperience()
        database.shaiSystemInstructionsDao().insert(
            ShaiSystemInstructionsEntity("instructions", "private", true, 1, 1, 1),
        )
        val before = database.shaiSystemInstructionsDao().instructions("instructions")
        assess(id)
        assertEquals(before, database.shaiSystemInstructionsDao().instructions("instructions"))
    }

    @Test
    fun attentionDoesNotModifyDraft() = runBlocking {
        createConversation()
        database.conversationDraftDao().insertDraft(ConversationDraftEntity("conversation", "private", 1, 1, 1))
        append("source")
        val before = database.conversationDraftDao().draft("conversation")
        assess(experienceId("source"))
        assertEquals(before, database.conversationDraftDao().draft("conversation"))
    }

    private suspend fun sourceExperience(): String {
        createConversation()
        append("source")
        return experienceId("source")
    }

    private suspend fun createConversation() {
        if (database.conversationTimelineDao().conversation("conversation") != null) return
        assertTrue(
            timeline.createConversationWithTimeline(
                CreateTimelineConversationInput("conversation", 0, 0, ConversationStatus.ACTIVE),
            ) is TimelineWriteResult.ConversationCreated,
        )
    }

    private suspend fun append(
        id: String,
        role: MessageRole = MessageRole.USER,
        delivery: MessageDeliveryState = MessageDeliveryState.PERSISTED,
        content: String = id,
        createdAt: Long = database.conversationTimelineDao().timelineHead("conversation")!!.timelineRevision + 1,
        occurredAt: Long = createdAt,
        attachmentIds: List<String> = emptyList(),
    ) {
        val revision = database.conversationTimelineDao().timelineHead("conversation")!!.timelineRevision
        val result = timeline.appendMessage(
            AppendTimelineMessageInput(
                "conversation",
                message(id, role, delivery, createdAt, content, attachmentIds),
                revision,
                occurredAt,
            ),
        )
        assertTrue("Expected append but was $result", result is TimelineWriteResult.MessageAppended)
    }

    private fun message(
        id: String,
        role: MessageRole,
        delivery: MessageDeliveryState,
        at: Long,
        content: String = id,
        attachmentIds: List<String> = emptyList(),
    ) = NewTimelineMessageInput(id, role, delivery, content, at, at, attachmentIds = attachmentIds)

    private suspend fun rewind(target: String) {
        val revision = database.conversationTimelineDao().timelineHead("conversation")!!.timelineRevision
        assertTrue(
            timeline.rewindTo(RewindTimelineInput("conversation", target, revision, revision + 1))
                is TimelineWriteResult.Rewound,
        )
    }

    private suspend fun experienceId(messageId: String): String {
        val result = experienceService.conversationExperienceForMessage(messageId)
            as ConversationExperienceLookupResult.Found
        return result.experience.id
    }

    private suspend fun assess(id: String) = service.assess(AssessImmediateAttentionInput(id, 0, 10))

    private fun forward(vararg signals: PositiveAttentionSignal) = ImmediateAttentionProposal(
        outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
        positiveSignals = signals.toList().ifEmpty { listOf(PositiveAttentionSignal.IDENTITY) },
    )

    private fun persisted(result: AssessImmediateAttentionResult): AttentionAssessment {
        assertTrue("Expected persisted but was $result", result is AssessImmediateAttentionResult.Persisted)
        return (result as AssessImmediateAttentionResult.Persisted).assessment
    }

    private fun failure(result: AssessImmediateAttentionResult): ImmediateAttentionError {
        assertTrue("Expected failure but was $result", result is AssessImmediateAttentionResult.Failure)
        return (result as AssessImmediateAttentionResult.Failure).error
    }

    private fun insertExperience(
        id: String,
        type: ExperienceType = ExperienceType.OTHER,
        availability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
    ): String {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id,
                (database.memoryDao().maximumEventOrder() ?: 0) + 1,
                type,
                ExperienceActor.OTHER,
                sourceContent = "grounded",
                occurredAt = 1,
                recordedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                availability = availability,
            ),
        )
        return id
    }

    private fun insertAttachment(
        id: String,
        kind: AttachmentKind = AttachmentKind.IMAGE,
        mimeType: String = "image/png",
        source: AttachmentSource = AttachmentSource.SHAI_IMPORT,
    ) = database.attachmentDao().insertAttachment(
        AttachmentEntity(
            id,
            kind,
            mimeType,
            AttachmentState.AVAILABLE,
            "attachments/$id.blob",
            1,
            "a".repeat(64),
            source,
            1,
            1,
        ),
    )

    private fun rowCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`")
        .use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun allTableCounts(): Map<String, Long> = database.openHelper.writableDatabase
        .query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.associateWith(::rowCount)

    private class RecordingAnalyzer : ImmediateAttentionAnalyzer {
        var proposal = forwardProposal()
        var failure: Throwable? = null
        var beforeReturn: suspend () -> Unit = {}
        val snapshots = mutableListOf<ImmediateAttentionSnapshot>()

        override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal {
            snapshots += snapshot
            failure?.let { throw it }
            beforeReturn()
            return proposal
        }

        companion object {
            fun forwardProposal() = ImmediateAttentionProposal(
                AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                positiveSignals = listOf(PositiveAttentionSignal.IDENTITY),
            )
        }
    }
}
