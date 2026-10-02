package com.shai.riven.data.automaticmemory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.attention.ImmediateAttentionProposal
import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.candidate.CandidateExtractionProposal
import com.shai.riven.data.candidate.CandidateExtractionResult
import com.shai.riven.data.candidate.CandidateExtractionService
import com.shai.riven.data.candidate.CandidateMemoryProposal
import com.shai.riven.data.candidate.ExtractCandidateMemoriesInput
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.intent.ManualDeleteMemoryInput
import com.shai.riven.data.memory.intent.ManualForgetMemoryInput
import com.shai.riven.data.memory.intent.ManualMemoryIntentResult
import com.shai.riven.data.memory.intent.ManualMemoryIntentService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.provider.openrouter.OpenRouterConversationAdapter
import com.shai.riven.data.recall.ConversationalMemoryQuery
import com.shai.riven.data.recall.ConversationalRecallReadiness
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import com.shai.riven.data.validation.CandidateValidationDecision
import com.shai.riven.data.validation.CandidateValidationOutcome
import com.shai.riven.data.validation.CandidateValidationSnapshot
import com.shai.riven.data.validation.ValidationAdmissionMetadata
import java.util.concurrent.atomic.AtomicLong
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
class AutomaticMemoryPipelineIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: RivenDatabase
    private lateinit var timeline: ConversationTimelineService
    private lateinit var scheduler: RecordingScheduler
    private lateinit var model: SpecFakeMemoryModel
    private lateinit var queue: AutomaticMemoryQueueService
    private lateinit var runner: AutomaticMemoryJobRunner
    private val clock = AtomicLong(100)

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = RivenDatabase.buildNamedForRestoreValidation(context, DATABASE_NAME)
        scheduler = RecordingScheduler()
        model = SpecFakeMemoryModel()
        resetServices()
        val created = timeline.createConversationWithTimeline(
            CreateTimelineConversationInput(CONVERSATION_ID, now(), now(), ConversationStatus.ACTIVE, "Memory"),
        )
        assertTrue(created is TimelineWriteResult.ConversationCreated)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun ordinaryFactSurvivesShortWindowRelaunchAndIsRecalledOutsideRecentContext() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted without making a performance of it.")
        enqueueAndRun(turn)
        assertTrue(model.sawCompletedTurnHindsight)

        repeat(14) { index ->
            appendMessage(
                id = "later-$index",
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                state = if (index % 2 == 0) MessageDeliveryState.PERSISTED else MessageDeliveryState.SUCCEEDED,
                content = "Unrelated later context $index",
            )
        }
        reopenDatabase()

        val recall = TargetedConversationalMemoryRetriever(database)
        val result = recall.retrieve(ConversationalMemoryQuery("What sardine snack would Shai enjoy?", now()))
        recall.close()

        assertEquals(ConversationalRecallReadiness.READY, result.readiness)
        assertEquals(listOf("Shai loves sardines."), result.memories.map { it.meaning })
    }

    @Test
    fun explicitCorrectionDisplacesOldBeliefWithoutErasingItsHistory() = runBlocking {
        enqueueAndRun(appendSuccessfulTurn("I grew up in Kansas.", "Understood."))
        enqueueAndRun(
            appendSuccessfulTurn(
                "Correction: I grew up in Brooklyn; I just live in Kansas now.",
                "Brooklyn upbringing, Kansas residence.",
            ),
        )

        val memories = database.memoryDao().recentMemories(10)
        val inaccurate = memories.single { it.meaning == "Shai grew up in Kansas." }
        val replacement = memories.single { it.meaning.contains("Brooklyn") }
        assertEquals(MemoryTruthState.CORRECTED_FALSE, inaccurate.truthState)
        assertEquals(MemoryTruthState.SUPPORTED, replacement.truthState)

        val recall = TargetedConversationalMemoryRetriever(database)
        val result = recall.retrieve(ConversationalMemoryQuery("Where did Shai grow up, Brooklyn or Kansas?", now()))
        recall.close()
        assertTrue(result.memories.any { it.memoryId == replacement.id })
        assertFalse(result.memories.any { it.memoryId == inaccurate.id })
    }

    @Test
    fun forgetAndDeleteSuppressOldEvidenceReExtractionAndRecall() = runBlocking {
        val sardines = appendSuccessfulTurn("I love sardines.", "Noted.")
        val tea = appendSuccessfulTurn("My favorite tea is oolong.", "That is useful context.")
        enqueueAndRun(sardines)
        enqueueAndRun(tea)
        val memories = database.memoryDao().recentMemories(10)
        val sardineMemory = memories.single { it.meaning.contains("sardines") }
        val teaMemory = memories.single { it.meaning.contains("oolong") }

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is ManualMemoryIntentResult.Forgotten)
        assertTrue(manual.delete(ManualDeleteMemoryInput(teaMemory.id, now())) is ManualMemoryIntentResult.Deleted)

        val extraction = CandidateExtractionService(database, model)
        val sardineReplay = extraction.extract(
            ExtractCandidateMemoriesInput(experienceFor(sardines.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted
        val teaReplay = extraction.extract(
            ExtractCandidateMemoriesInput(experienceFor(tea.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted
        assertEquals(1, sardineReplay.suppressedLineageCount)
        assertEquals(1, teaReplay.suppressedLineageCount)

        val recall = TargetedConversationalMemoryRetriever(database)
        val result = recall.retrieve(ConversationalMemoryQuery("sardines oolong", now()))
        recall.close()
        assertTrue(result.memories.isEmpty())
    }

    @Test
    fun regenerationExcludesDiscardedAssistantExperienceAndItsMemory() = runBlocking {
        val original = appendSuccessfulTurn(
            "What did you learn about yourself?",
            "I discovered that I love playing chess.",
        )
        enqueueAndRun(original)
        val selfMemory = database.memoryDao().recentMemories(10).single { it.meaning.contains("chess") }
        val recall = TargetedConversationalMemoryRetriever(database)
        assertTrue(
            recall.retrieve(ConversationalMemoryQuery("Does Riven love chess?", now()))
                .memories.any { it.memoryId == selfMemory.id },
        )

        val active = timeline.activeTimeline(CONVERSATION_ID) as TimelineReadResult.Success
        val regenerated = timeline.commitRegeneratedAssistantResponse(
            CommitRegeneratedAssistantResponseInput(
                conversationId = CONVERSATION_ID,
                originalMessageId = original.assistantMessageId,
                replacement = NewTimelineMessageInput(
                    messageId = "assistant-regenerated",
                    role = MessageRole.ASSISTANT,
                    deliveryState = MessageDeliveryState.SUCCEEDED,
                    content = "I did not form a durable new preference from that exchange.",
                    createdAt = now(),
                    updatedAt = now(),
                ),
                expectedTimelineRevision = active.timelineRevision,
                occurredAt = now(),
            ),
        ) as TimelineWriteResult.AssistantRegenerated
        val run = insertSucceededRun(
            runId = "run-regenerated",
            userMessageId = original.userMessageId,
            assistantMessageId = regenerated.activatedMessageId,
            finalRevision = regenerated.timelineRevision,
            trigger = ConversationRunTrigger.REGENERATE,
            regenerateOfMessageId = original.assistantMessageId,
        )
        val queued = queue.ensureForSucceededRun(run.runId, regenerated.timelineRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        queued.jobIds.forEach { runner.run(it) }

        assertEquals(
            ExperienceAvailability.EXCLUDED,
            database.memoryDao().experience(experienceFor(original.assistantMessageId))?.availability,
        )
        assertEquals(
            AutomaticMemoryJobState.EXCLUDED,
            database.automaticMemoryDao().jobForMessage(original.assistantMessageId)?.state,
        )
        assertNotNull(database.memoryDao().memory(selfMemory.id))
        val result = recall.retrieve(ConversationalMemoryQuery("Does Riven love chess?", now()))
        recall.close()
        assertFalse(result.memories.any { it.memoryId == selfMemory.id })
    }

    @Test
    fun completedCheckpointSurvivesRestartAndRetryDoesNotDuplicateMemory() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        enqueueAndRun(turn)
        val callsAfterSuccess = model.totalCalls
        val memoryId = database.memoryDao().recentMemories(10).single().id

        reopenDatabase()
        val reconciliation = queue.reconcileSucceededRuns(25, now())
        assertTrue(reconciliation.jobIds.isEmpty())
        assertTrue(runner.run(checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id) is
            AutomaticMemoryJobRunResult.NoOp)
        assertEquals(1, database.memoryDao().memoryCount())
        assertNotNull(database.memoryDao().memory(memoryId))
        assertEquals(callsAfterSuccess, model.totalCalls)
    }

    private suspend fun enqueueAndRun(turn: Turn) {
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        queued.jobIds.forEach { jobId ->
            val result = runner.run(jobId)
            assertTrue("Unexpected automatic-memory result $result", result is AutomaticMemoryJobRunResult.Succeeded)
        }
    }

    private suspend fun appendSuccessfulTurn(user: String, assistant: String): Turn {
        val suffix = now().toString()
        val userId = "user-$suffix"
        val assistantId = "assistant-$suffix"
        appendMessage(userId, MessageRole.USER, MessageDeliveryState.PERSISTED, user)
        val finalRevision = appendMessage(
            assistantId,
            MessageRole.ASSISTANT,
            MessageDeliveryState.SUCCEEDED,
            assistant,
        )
        val run = insertSucceededRun(
            runId = "run-$suffix",
            userMessageId = userId,
            assistantMessageId = assistantId,
            finalRevision = finalRevision,
        )
        return Turn(run.runId, userId, assistantId, finalRevision)
    }

    private suspend fun appendMessage(
        id: String,
        role: MessageRole,
        state: MessageDeliveryState,
        content: String,
    ): Long {
        val active = timeline.activeTimeline(CONVERSATION_ID) as TimelineReadResult.Success
        val occurredAt = now()
        val appended = timeline.appendMessage(
            AppendTimelineMessageInput(
                conversationId = CONVERSATION_ID,
                message = NewTimelineMessageInput(
                    messageId = id,
                    role = role,
                    deliveryState = state,
                    content = content,
                    createdAt = occurredAt,
                    updatedAt = occurredAt,
                ),
                expectedTimelineRevision = active.timelineRevision,
                occurredAt = occurredAt,
            ),
        ) as TimelineWriteResult.MessageAppended
        return appended.timelineRevision
    }

    private fun insertSucceededRun(
        runId: String,
        userMessageId: String,
        assistantMessageId: String,
        finalRevision: Long,
        trigger: ConversationRunTrigger = ConversationRunTrigger.INITIAL,
        regenerateOfMessageId: String? = null,
    ): ConversationRunEntity = ConversationRunEntity(
        runId = runId,
        conversationId = CONVERSATION_ID,
        userMessageId = userMessageId,
        assistantMessageId = assistantMessageId,
        trigger = trigger,
        retryOfRunId = null,
        regenerateOfMessageId = regenerateOfMessageId,
        state = ConversationRunState.SUCCEEDED,
        activeConversationId = null,
        idempotencyKey = "idempotency-$runId",
        inputFingerprint = "fingerprint-$runId",
        ownerSessionToken = "owner-$runId",
        profileId = "profile",
        profileRevision = 1,
        adapterId = OpenRouterConversationAdapter.ADAPTER_ID,
        endpointBaseUrl = OpenRouterConversationAdapter.DEFAULT_BASE_URL,
        modelId = "fake/model",
        selectedHeadMessageId = userMessageId,
        contextHeadMessageId = userMessageId,
        reservedTimelineRevision = finalRevision - 1,
        providerRequestId = "provider-$runId",
        createdAt = now(),
        startedAt = now(),
        updatedAt = now(),
        finishedAt = now(),
    ).also(database.conversationRunDao()::insert)

    private fun experienceFor(messageId: String): String =
        database.memoryDao().canonicalConversationExperiencesForMessage(messageId).single().id

    private fun reopenDatabase() {
        database.close()
        database = RivenDatabase.buildNamedForRestoreValidation(context, DATABASE_NAME)
        resetServices()
    }

    private fun resetServices() {
        timeline = ConversationTimelineService(database)
        queue = AutomaticMemoryQueueService(database, scheduler)
        runner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory { AutomaticMemoryModelFactoryResult.Ready(model) },
            clock = ::now,
        )
    }

    private fun now(): Long = clock.incrementAndGet()

    private data class Turn(
        val runId: String,
        val userMessageId: String,
        val assistantMessageId: String,
        val finalRevision: Long,
    )

    private class RecordingScheduler : RivenBackgroundWorkScheduler {
        val automaticMemoryJobIds = mutableListOf<String>()

        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued("attachment")
        override fun enqueueAttachmentMaintenanceSweep() = enqueued("attachment-sweep")
        override fun enqueueRepairJob(repairJobId: String) = enqueued("repair-$repairJobId")
        override fun enqueueRepairSweep() = enqueued("repair-sweep")
        override fun enqueueAutomaticMemoryJob(automaticMemoryJobId: String): RivenBackgroundScheduleResult {
            automaticMemoryJobIds += automaticMemoryJobId
            return enqueued(automaticMemoryJobId)
        }
        override fun enqueueAutomaticMemorySweep() = enqueued("memory-sweep")
        override fun ensurePeriodicMaintenance() = enqueued("periodic")

        private fun enqueued(name: String) = RivenBackgroundScheduleResult.Enqueued(listOf(name))
    }

    private class SpecFakeMemoryModel : AutomaticMemoryModel {
        var totalCalls = 0
        var sawCompletedTurnHindsight = false

        override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal {
            totalCalls += 1
            val source = snapshot.sourceContent.orEmpty()
            if (source.contains("sardines", ignoreCase = true)) {
                sawCompletedTurnHindsight = snapshot.followingActiveContext.isNotEmpty()
            }
            val signal = when {
                source.startsWith("Correction:") -> PositiveAttentionSignal.CORRECTION_OR_REVISION
                source.contains("sardines", ignoreCase = true) ||
                    source.contains("favorite tea", ignoreCase = true) ||
                    source.contains("grew up", ignoreCase = true) -> PositiveAttentionSignal.PREFERENCE
                source.contains("discovered that I love playing chess", ignoreCase = true) ->
                    PositiveAttentionSignal.SELF_DEVELOPMENT
                else -> null
            }
            return if (signal == null) {
                ImmediateAttentionProposal(AttentionOutcome.NO_CANDIDATE)
            } else {
                ImmediateAttentionProposal(AttentionOutcome.FORWARD_FOR_INTERPRETATION, listOf(signal))
            }
        }

        override suspend fun extract(
            snapshot: com.shai.riven.data.candidate.CandidateExtractionSnapshot,
        ): CandidateExtractionProposal {
            totalCalls += 1
            val source = snapshot.sourceContent.orEmpty()
            val proposal = when {
                source.contains("sardines", ignoreCase = true) -> CandidateMemoryProposal(
                    "Shai loves sardines.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                )
                source.contains("favorite tea", ignoreCase = true) -> CandidateMemoryProposal(
                    "Shai's favorite tea is oolong.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                )
                source.startsWith("Correction:") -> CandidateMemoryProposal(
                    "Shai grew up in Brooklyn and currently lives in Kansas.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.EXPLICIT_CORRECTION,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                )
                source.contains("grew up in Kansas", ignoreCase = true) -> CandidateMemoryProposal(
                    "Shai grew up in Kansas.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                )
                source.contains("discovered that I love playing chess", ignoreCase = true) -> CandidateMemoryProposal(
                    "Riven discovered that he loves playing chess.",
                    MemoryKind.SELF_DEVELOPMENT,
                    MemoryScope.RIVEN,
                    EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                )
                else -> null
            }
            return CandidateExtractionProposal(listOfNotNull(proposal))
        }

        override suspend fun decide(snapshot: CandidateValidationSnapshot): CandidateValidationDecision {
            totalCalls += 1
            val candidate = snapshot.candidate
            val admission = ValidationAdmissionMetadata(
                temporalState = TemporalState.CURRENT,
                significance = IntrinsicSignificanceInput(
                    practical = SignificanceLevel.LOW,
                    autobiographical = if (candidate.proposedScope == MemoryScope.RIVEN) {
                        SignificanceLevel.MODERATE
                    } else {
                        SignificanceLevel.NONE
                    },
                ),
            )
            if (candidate.proposedEpistemicBasis == EpistemicBasis.EXPLICIT_CORRECTION) {
                val old = snapshot.relatedMemories.first { it.meaning == "Shai grew up in Kansas." }
                return CandidateValidationDecision(
                    outcome = CandidateValidationOutcome.CORRECT_EXISTING,
                    targetMemoryIds = listOf(old.memoryId),
                    admission = admission,
                )
            }
            snapshot.relatedMemories.firstOrNull { it.meaning == candidate.proposedMeaning }?.let { existing ->
                return CandidateValidationDecision(
                    outcome = CandidateValidationOutcome.REINFORCE_EXISTING,
                    targetMemoryIds = listOf(existing.memoryId),
                )
            }
            return CandidateValidationDecision(
                outcome = CandidateValidationOutcome.ACCEPT_NEW,
                admission = admission,
            )
        }
    }

    private companion object {
        const val DATABASE_NAME = "automatic-memory-integration.db"
        const val CONVERSATION_ID = "automatic-memory-conversation"
    }
}
