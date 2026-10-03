package com.shai.riven.data.automaticmemory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.attention.ImmediateAttentionProposal
import com.shai.riven.data.attention.ImmediateAttentionSnapshot
import com.shai.riven.data.attention.PositiveAttentionSignal
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.background.AUTOMATIC_MEMORY_SHORT_WINDOW_DELAY_MS
import com.shai.riven.data.background.AttachmentCleanupNoOpReason
import com.shai.riven.data.background.AttachmentMaintenanceOperations
import com.shai.riven.data.background.AttachmentMaintenanceResult
import com.shai.riven.data.background.RepairJobNoOpReason
import com.shai.riven.data.background.RepairJobRunOperations
import com.shai.riven.data.background.RepairJobRunResult
import com.shai.riven.data.background.RepairSweepOperations
import com.shai.riven.data.background.RepairSweepResult
import com.shai.riven.data.background.RivenBackgroundExecutionOutcome
import com.shai.riven.data.background.RivenBackgroundWorkExecutor
import com.shai.riven.data.background.RivenBackgroundWorkKind
import com.shai.riven.data.background.TargetedAttachmentCleanupResult
import com.shai.riven.data.candidate.CandidateExtractionProposal
import com.shai.riven.data.candidate.CandidateExtractionResult
import com.shai.riven.data.candidate.CandidateExtractionService
import com.shai.riven.data.candidate.CandidateExtractionSnapshot
import com.shai.riven.data.candidate.CandidateMemoryProposal
import com.shai.riven.data.candidate.CandidateSourceAnchor
import com.shai.riven.data.candidate.ExtractCandidateMemoriesInput
import com.shai.riven.data.candidate.candidateSourceClaims
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.CommitRegeneratedAssistantResponseInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.sourceClaimSuppressionHash
import com.shai.riven.data.memory.intent.ManualDeleteMemoryInput
import com.shai.riven.data.memory.intent.ManualForgetMemoryInput
import com.shai.riven.data.memory.intent.ManualMemoryIntentResult
import com.shai.riven.data.memory.intent.ManualMemoryIntentService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationRunEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AutomaticMemoryJobStage
import com.shai.riven.data.persistence.model.AutomaticMemoryJobState
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
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
    fun arcadeTurnIsDurablyExcludedWithoutSchedulingOrLaterReconciliation() = runBlocking {
        val turn = appendSuccessfulTurn(
            "How does this table look?",
            "The public position looks steady.",
        )

        val excluded = queue.excludeSucceededRun(
            runId = turn.runId,
            sourceTimelineRevision = turn.finalRevision,
            occurredAt = now(),
            reasonCode = "ARCADE_TRANSIENT_CONTEXT",
        ) as AutomaticMemoryExclusionResult.Excluded
        val replay = queue.excludeSucceededRun(
            runId = turn.runId,
            sourceTimelineRevision = turn.finalRevision,
            occurredAt = now(),
            reasonCode = "ARCADE_TRANSIENT_CONTEXT",
        ) as AutomaticMemoryExclusionResult.Excluded

        assertEquals(2, excluded.jobIds.size)
        assertEquals(excluded.jobIds, replay.jobIds)
        excluded.jobIds.forEach { jobId ->
            val job = checkNotNull(database.automaticMemoryDao().job(jobId))
            assertEquals(AutomaticMemoryJobState.EXCLUDED, job.state)
            assertEquals(AutomaticMemoryJobStage.COMPLETE, job.nextStage)
            assertEquals("ARCADE_TRANSIENT_CONTEXT", job.lastErrorCode)
        }
        assertEquals(2, queue.status().excluded)
        assertTrue(scheduler.automaticMemoryJobIds.isEmpty())
        assertTrue(queue.schedulePending(limit = 10).isEmpty())

        val reconciliation = queue.reconcileSucceededRuns(limit = 10, occurredAt = now())
        assertEquals(0, reconciliation.inspectedRuns)
        assertTrue(reconciliation.jobIds.isEmpty())
        assertTrue(scheduler.automaticMemoryJobIds.isEmpty())
        assertEquals(0, model.totalCalls)
    }

    @Test
    fun arcadeExclusionWinsIfPendingRowsAlreadyExist() = runBlocking {
        val turn = appendSuccessfulTurn("Comment on this game.", "The public board is close.")
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        assertEquals(2, queued.jobIds.size)

        val excluded = queue.excludeSucceededRun(
            runId = turn.runId,
            sourceTimelineRevision = turn.finalRevision,
            occurredAt = now(),
            reasonCode = "ARCADE_TRANSIENT_CONTEXT",
        ) as AutomaticMemoryExclusionResult.Excluded

        assertEquals(queued.jobIds, excluded.jobIds)
        excluded.jobIds.forEach { jobId ->
            val job = checkNotNull(database.automaticMemoryDao().job(jobId))
            assertEquals(AutomaticMemoryJobState.EXCLUDED, job.state)
            assertEquals(AutomaticMemoryJobStage.COMPLETE, job.nextStage)
            assertEquals("ARCADE_TRANSIENT_CONTEXT", job.lastErrorCode)
        }
        assertTrue(queue.schedulePending(limit = 10).isEmpty())
        assertEquals(0, model.totalCalls)
    }

    @Test
    fun missingCredentialBlocksWithoutConsumingAttemptAndRemainsRecoverable() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        val blockedRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                AutomaticMemoryModelFactoryResult.Blocked("PROFILE_MissingCredential")
            },
        )

        val result = blockedRunner.run(jobId)

        assertTrue(result is AutomaticMemoryJobRunResult.Blocked)
        val job = checkNotNull(database.automaticMemoryDao().job(jobId))
        assertEquals(AutomaticMemoryJobState.PENDING, job.state)
        assertEquals(0, job.attemptCount)
        assertEquals("PROFILE_MissingCredential", job.lastErrorCode)
    }

    @Test
    fun invalidProviderCredentialBlocksWithoutConsumingAttemptAndRecoversAfterReplacement() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        val invalidCredentialModel = object : AutomaticMemoryModel by model {
            override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal {
                throw AutomaticMemoryModelFailure(
                    "INVALID_CREDENTIAL",
                    retryable = false,
                    blocked = true,
                )
            }
        }
        val blockedRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                AutomaticMemoryModelFactoryResult.Ready(invalidCredentialModel)
            },
        )

        val blocked = blockedRunner.run(jobId)
        val blockedJob = checkNotNull(database.automaticMemoryDao().job(jobId))

        assertEquals(AutomaticMemoryJobRunResult.Blocked(jobId, "INVALID_CREDENTIAL"), blocked)
        assertEquals(AutomaticMemoryJobState.PENDING, blockedJob.state)
        assertEquals(0, blockedJob.attemptCount)
        assertEquals("INVALID_CREDENTIAL", blockedJob.lastErrorCode)
        assertTrue(runner.run(jobId) is AutomaticMemoryJobRunResult.Succeeded)
        assertEquals(1, database.memoryDao().memoryCount())
    }

    @Test
    fun elapsedBudgetYieldsAfterDurableProgressWithoutSpendingRetry() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        val elapsed = AtomicLong(0L)
        val slowAttentionModel = object : AutomaticMemoryModel by model {
            override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal =
                model.analyze(snapshot).also { elapsed.addAndGet(10L) }
        }
        val budgetedRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                AutomaticMemoryModelFactoryResult.Ready(slowAttentionModel)
            },
            clock = ::now,
            maxRunElapsedMs = 5L,
            elapsedRealtimeMs = elapsed::get,
        )

        val yielded = budgetedRunner.run(jobId)

        assertEquals(
            AutomaticMemoryJobRunResult.RetryableFailure(jobId, "ELAPSED_TIME_CONTINUATION"),
            yielded,
        )
        val durable = checkNotNull(database.automaticMemoryDao().job(jobId))
        assertEquals(AutomaticMemoryJobState.PENDING, durable.state)
        assertEquals(AutomaticMemoryJobStage.EXTRACTION, durable.nextStage)
        assertEquals(0, durable.attemptCount)
        assertEquals("ELAPSED_TIME_CONTINUATION", durable.lastErrorCode)
        val attentionCallsAfterYield = model.sardineAnalysisCalls
        assertEquals(1, attentionCallsAfterYield)

        reopenDatabase()
        assertTrue(runner.run(jobId) is AutomaticMemoryJobRunResult.Succeeded)
        assertEquals(attentionCallsAfterYield, model.sardineAnalysisCalls)
        assertEquals(AutomaticMemoryJobState.SUCCEEDED, database.automaticMemoryDao().job(jobId)?.state)
    }

    @Test
    fun permanentInputLimitFailsOnceWithoutBurningRetryBudget() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        var calls = 0
        val oversizedInputModel = object : AutomaticMemoryModel by model {
            override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal {
                calls += 1
                throw AutomaticMemoryModelFailure("INPUT_LIMIT", retryable = false)
            }
        }
        val failingRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                AutomaticMemoryModelFactoryResult.Ready(oversizedInputModel)
            },
        )

        val failed = failingRunner.run(jobId)

        assertEquals(AutomaticMemoryJobRunResult.PermanentlyFailed(jobId, "INPUT_LIMIT"), failed)
        assertTrue(failingRunner.run(jobId) is AutomaticMemoryJobRunResult.NoOp)
        assertEquals(1, calls)
        assertEquals(1, database.automaticMemoryDao().job(jobId)?.attemptCount)
    }

    @Test
    fun typedCredentialFailureFromValidationRemainsRecoverableAtValidationStage() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        val validationBlockedModel = object : AutomaticMemoryModel by model {
            override suspend fun decide(snapshot: CandidateValidationSnapshot): CandidateValidationDecision {
                throw AutomaticMemoryModelFailure(
                    "INVALID_CREDENTIAL",
                    retryable = false,
                    blocked = true,
                )
            }
        }
        val blockedRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                AutomaticMemoryModelFactoryResult.Ready(validationBlockedModel)
            },
        )

        val blocked = blockedRunner.run(jobId)
        val checkpoint = checkNotNull(database.automaticMemoryDao().job(jobId))

        assertEquals(AutomaticMemoryJobRunResult.Blocked(jobId, "INVALID_CREDENTIAL"), blocked)
        assertEquals(AutomaticMemoryJobStage.VALIDATION, checkpoint.nextStage)
        assertEquals(0, checkpoint.attemptCount)
        assertTrue(runner.run(jobId) is AutomaticMemoryJobRunResult.Succeeded)
        assertEquals(1, database.memoryDao().memoryCount())
    }

    @Test
    fun attemptBudgetFailsBeforeAnotherProviderCall() = runBlocking {
        val turn = appendSuccessfulTurn("I love sardines.", "Noted.")
        queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id
        var factoryCalls = 0
        val failingRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory {
                factoryCalls += 1
                AutomaticMemoryModelFactoryResult.RetryableFailure("TEMPORARY")
            },
            maxAttempts = 2,
        )

        assertTrue(failingRunner.run(jobId) is AutomaticMemoryJobRunResult.RetryableFailure)
        val exhausted = failingRunner.run(jobId)
        val terminalReplay = failingRunner.run(jobId)

        assertTrue(exhausted is AutomaticMemoryJobRunResult.PermanentlyFailed)
        assertTrue(terminalReplay is AutomaticMemoryJobRunResult.NoOp)
        assertEquals(2, factoryCalls)
        assertEquals(AutomaticMemoryJobState.FAILED, database.automaticMemoryDao().job(jobId)?.state)
    }

    @Test
    fun singleLineageRivenSelfDevelopmentCannotBeAdmittedByModelAlone() = runBlocking {
        model.forceSingleLineageSelfDevelopment = true
        val turn = appendSuccessfulTurn(
            "What did you learn about yourself?",
            "I discovered that I love playing chess.",
        )
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued

        val results = queued.jobIds.map { runner.run(it) }

        assertTrue(results.any { result ->
            result is AutomaticMemoryJobRunResult.RetryableFailure &&
                result.errorCode == "VALIDATION_InvalidValidationDecision"
        })
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test
    fun deferredAttentionResumesWhenLaterTurnSuppliesShortWindowContext() = runBlocking {
        model.deferSardinesUntilKeepIt = true
        enqueueAndRun(appendSuccessfulTurn("I love sardines.", "Do you want that remembered?"))
        assertEquals(0, database.memoryDao().memoryCount())

        val clarifyingTurn = appendSuccessfulTurn(
            "Keep it as a standing preference.",
            "Understood.",
        )
        val queued = queue.ensureForSucceededRun(clarifyingTurn.runId, clarifyingTurn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val results = queued.jobIds.map { runner.run(it) }

        assertTrue(results.all { it is AutomaticMemoryJobRunResult.Succeeded })
        assertEquals(listOf("Shai loves sardines."), database.memoryDao().recentMemories(10).map { it.meaning })
    }

    @Test
    fun inactivityWakeIsRearmedFromActualDeferredJobCompletion() = runBlocking {
        model.deferSardinesUntilKeepIt = true
        val turn = appendSuccessfulTurn("I love sardines.", "Do you want that remembered?")
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        assertEquals(0, scheduler.shortWindowSweepCount)

        queued.jobIds.forEach { assertTrue(runner.run(it) is AutomaticMemoryJobRunResult.Succeeded) }

        val deferredJob = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId))
        assertEquals(AutomaticMemoryJobState.SUCCEEDED, deferredJob.state)
        assertEquals(1, scheduler.shortWindowSweepCount)
        assertEquals(AUTOMATIC_MEMORY_SHORT_WINDOW_DELAY_MS, scheduler.shortWindowDelays.single())
        val justBefore = queue.requeueDueShortWindow(
            deferredJob.updatedAt + AUTOMATIC_MEMORY_SHORT_WINDOW_DELAY_MS - 1,
            10,
        )
        assertTrue(justBefore.schedulingFailedJobIds.isEmpty())
        assertEquals(AutomaticMemoryJobState.SUCCEEDED, database.automaticMemoryDao().job(deferredJob.id)?.state)

        queue.requeueDueShortWindow(
            deferredJob.updatedAt + AUTOMATIC_MEMORY_SHORT_WINDOW_DELAY_MS,
            10,
        )
        assertEquals(AutomaticMemoryJobState.PENDING, database.automaticMemoryDao().job(deferredJob.id)?.state)
        assertEquals(AutomaticMemoryJobStage.REFRESH_ATTENTION, database.automaticMemoryDao().job(deferredJob.id)?.nextStage)
    }

    @Test
    fun startupSweepRecreatesDeadlineAfterDeathBetweenCompletionAndSchedulerCall() = runBlocking {
        model.deferSardinesUntilKeepIt = true
        val turn = appendSuccessfulTurn("I love sardines.", "Do you want that remembered?")
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val crashGapRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory { AutomaticMemoryModelFactoryResult.Ready(model) },
            clock = ::now,
            reconcileShortWindowSweep = { error("simulated process death before scheduling") },
        )
        queued.jobIds.forEach { jobId ->
            assertTrue(crashGapRunner.run(jobId) is AutomaticMemoryJobRunResult.Succeeded)
        }
        assertEquals(0, scheduler.shortWindowSweepCount)
        val deferredJob = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId))
        val restartAt = deferredJob.updatedAt + 1_000L

        reopenDatabase()
        val result = AutomaticMemorySweepService(queue, clock = { restartAt }, limit = 10).runSweep()

        assertTrue(result is AutomaticMemorySweepResult.Completed)
        assertEquals(1, scheduler.shortWindowSweepCount)
        assertEquals(
            AUTOMATIC_MEMORY_SHORT_WINDOW_DELAY_MS - 1_000L,
            scheduler.shortWindowDelays.single(),
        )
        assertEquals(
            AutomaticMemoryJobState.SUCCEEDED,
            database.automaticMemoryDao().job(deferredJob.id)?.state,
        )
    }

    @Test
    fun refreshIntentSurvivesInterruptionImmediatelyAfterClaim() = runBlocking {
        model.deferSardinesUntilKeepIt = true
        val sourceTurn = appendSuccessfulTurn("I love sardines.", "Do you want that remembered?")
        enqueueAndRun(sourceTurn)
        assertEquals(0, database.memoryDao().memoryCount())

        val clarification = appendSuccessfulTurn(
            "Keep it as a standing preference.",
            "Understood.",
        )
        queue.ensureForSucceededRun(clarification.runId, clarification.finalRevision, now())
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(sourceTurn.userMessageId)).id
        assertEquals(
            AutomaticMemoryJobStage.REFRESH_ATTENTION,
            database.automaticMemoryDao().job(jobId)?.nextStage,
        )

        val analysesBeforeInterruption = model.sardineAnalysisCalls
        model.cancelNextSardineAnalysis = true
        try {
            runner.run(jobId)
            throw AssertionError("Expected the refresh analysis to be interrupted")
        } catch (_: CancellationException) {
            // Simulates process loss after the durable job was claimed and its display error cleared.
        }

        val interrupted = checkNotNull(database.automaticMemoryDao().job(jobId))
        assertEquals(AutomaticMemoryJobState.RUNNING, interrupted.state)
        assertEquals(AutomaticMemoryJobStage.REFRESH_ATTENTION, interrupted.nextStage)
        assertEquals(null, interrupted.lastErrorCode)
        assertEquals(analysesBeforeInterruption + 1, model.sardineAnalysisCalls)

        val recoveryRunner = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory { AutomaticMemoryModelFactoryResult.Ready(model) },
            clock = { Long.MAX_VALUE / 4 },
            runningLeaseMs = 1L,
        )
        val recovered = recoveryRunner.run(jobId)

        assertTrue(recovered is AutomaticMemoryJobRunResult.Succeeded)
        assertEquals(analysesBeforeInterruption + 2, model.sardineAnalysisCalls)
        assertEquals(listOf("Shai loves sardines."), database.memoryDao().recentMemories(10).map { it.meaning })
    }

    @Test
    fun reconciliationSkipsOlderExcludedSourcesInsteadOfStarvingAvailableRun() = runBlocking {
        val turns = (0..25).map { index ->
            appendSuccessfulTurn("Historical statement $index", "Historical response $index")
        }
        turns.take(25).forEach { turn ->
            listOf(turn.userMessageId, turn.assistantMessageId).forEach { messageId ->
                assertEquals(
                    1,
                    database.memoryDao().updateExperienceAvailability(
                        experienceFor(messageId),
                        ExperienceAvailability.EXCLUDED,
                    ),
                )
            }
        }

        val reconciled = queue.reconcileSucceededRuns(25, now())
        val available = turns.last()

        assertTrue(reconciled.jobIds.isNotEmpty())
        assertNotNull(database.automaticMemoryDao().jobForMessage(available.userMessageId))
        assertNotNull(database.automaticMemoryDao().jobForMessage(available.assistantMessageId))
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
        val sardineEvidence = database.memoryDao().evidenceForMemory(sardineMemory.id).single()
        val teaEvidence = database.memoryDao().evidenceForMemory(teaMemory.id).single()

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is ManualMemoryIntentResult.Forgotten)
        assertTrue(database.maintenanceDao().suppressionTombstone(
            sourceClaimSuppressionHash(sardineEvidence.experienceId, sardineEvidence.lineageKey),
        )?.isActive == true)
        assertEquals(null, database.maintenanceDao().suppressionTombstone(
            sourceClaimSuppressionHash(teaEvidence.experienceId, teaEvidence.lineageKey),
        ))
        assertTrue(manual.delete(ManualDeleteMemoryInput(teaMemory.id, now())) is ManualMemoryIntentResult.Deleted)

        model.paraphraseKnownFacts = true
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
    fun admittedSiblingDoesNotDuplicateOrBlockDeferredSiblingReprocessing() = runBlocking {
        model.twoFactShortWindow = true
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines, and my dog's name is Pixel.",
            "Two separate facts; Pixel may need clarification.",
        )
        enqueueAndRun(sourceTurn)
        assertEquals(listOf("Shai loves sardines."), database.memoryDao().recentMemories(10).map { it.meaning })

        val clarification = appendSuccessfulTurn(
            "Pixel is the permanent name; keep that as an ongoing fact.",
            "Understood.",
        )
        val queued = queue.ensureForSucceededRun(clarification.runId, clarification.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val results = queued.jobIds.map { runner.run(it) }

        assertTrue(results.all { it is AutomaticMemoryJobRunResult.Succeeded })
        val memories = database.memoryDao().recentMemories(10)
        assertEquals(1, memories.count { it.meaning.contains("sardine", ignoreCase = true) })
        val pixel = memories.single { it.meaning == "Shai's dog is named Pixel." }
        assertEquals(MemoryCertainty.CERTAIN, pixel.certainty)
        assertEquals(SensitivityLevel.STANDARD, pixel.sensitivity)
        assertTrue(
            database.memoryDao().candidateSeedEvidenceForExperience(experienceFor(sourceTurn.userMessageId)).isEmpty(),
        )
    }

    @Test
    fun forgottenClaimParaphraseIsSuppressedWhileDeferredSiblingFromSameMessageIsAdmitted() = runBlocking {
        model.twoFactShortWindow = true
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines, and my dog's name is Pixel.",
            "Two separate facts; I will keep their meanings distinct.",
        )
        enqueueAndRun(sourceTurn)
        val sardineMemory = database.memoryDao().recentMemories(10).single()
        assertTrue(sardineMemory.meaning.contains("sardines"))
        val deferred = database.memoryDao()
            .candidateSeedEvidenceForExperience(experienceFor(sourceTurn.userMessageId))
            .mapNotNull { database.memoryDao().candidateMemory(it.candidateMemoryId) }
            .single()
        assertEquals(CandidateMemoryState.PENDING_CONTEXT, deferred.state)
        assertTrue(deferred.proposedMeaning.contains("Pixel"))

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Forgotten,
        )

        val clarification = appendSuccessfulTurn(
            "Pixel is the permanent name; keep that as an ongoing fact.",
            "Understood.",
        )
        val queued = queue.ensureForSucceededRun(clarification.runId, clarification.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val results = queued.jobIds.map { runner.run(it) }

        assertTrue(results.all { it is AutomaticMemoryJobRunResult.Succeeded })
        val retained = database.memoryDao().recentMemories(10)
        val pixel = retained.single { it.meaning == "Shai's dog is named Pixel." }
        assertEquals(MemoryCertainty.CERTAIN, pixel.certainty)
        assertEquals(SensitivityLevel.STANDARD, pixel.sensitivity)
        assertEquals(1, retained.count { it.meaning.contains("sardine", ignoreCase = true) })

        val replay = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted
        assertEquals(1, replay.suppressedLineageCount)
        assertTrue(replay.createdCandidateIds.isEmpty())
        assertEquals(
            listOf(pixel.id),
            replay.existingMemoryIds,
        )
    }

    @Test
    fun deletedClaimParaphraseIsSuppressedWithoutSuppressingSiblingFromSameMessage() = runBlocking {
        model.twoFactShortWindow = true
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines, and my dog's name is Pixel.",
            "Pixel is the permanent name, and I will keep both facts distinct.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.delete(ManualDeleteMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Deleted,
        )
        assertEquals(null, database.memoryDao().memory(sardineMemory.id))
        assertNotNull(database.memoryDao().memory(pixelMemory.id))

        val replay = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(1, replay.suppressedLineageCount)
        assertTrue(replay.createdCandidateIds.isEmpty())
        assertEquals(listOf(pixelMemory.id), replay.existingMemoryIds)
        assertFalse(database.memoryDao().recentMemories(10).any { it.meaning.contains("sardine", ignoreCase = true) })
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
    }

    @Test
    fun forgetSurvivesReorderedAndSplitReplayWhileUnrelatedClaimRemains() = runBlocking {
        model.twoFactShortWindow = true
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines, and my dog's name is Pixel.",
            "Pixel is the permanent name, so both facts are ready.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Forgotten,
        )

        model.extractionOverride = { snapshot ->
            CandidateExtractionProposal(
                listOf(
                    proposalFor(snapshot, "and my dog's name is Pixel.", "Shai's dog is named Pixel."),
                    proposalFor(snapshot, "I love sardines", "Sardines are a food Shai loves."),
                    proposalFor(
                        snapshot,
                        "I love sardines",
                        "Shai has an enduring positive food preference for sardines.",
                        kind = MemoryKind.EPISODIC,
                    ),
                ),
            )
        }
        val replay = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(2, replay.suppressedLineageCount)
        assertTrue(replay.createdCandidateIds.isEmpty())
        assertEquals(listOf(pixelMemory.id), replay.existingMemoryIds)
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
        val recall = TargetedConversationalMemoryRetriever(database)
        val recalled = recall.retrieve(ConversationalMemoryQuery("sardines Pixel", now()))
        recall.close()
        assertFalse(recalled.memories.any { it.meaning.contains("sardine", ignoreCase = true) })
        assertTrue(recalled.memories.any { it.memoryId == pixelMemory.id })
    }

    @Test
    fun freshIndependentChildAnchorsWithinOneCompoundSourceAreBothAdmitted() = runBlocking {
        model.extractionOverride = { snapshot ->
            val parentIds = candidateSourceClaims(snapshot.sourceContent).map { it.id }.distinct()
            assertEquals(listOf("SOURCE_CLAIM_V1:0"), parentIds)
            CandidateExtractionProposal(
                listOf(
                    proposalFor(snapshot, "I love sardines", "Shai loves sardines."),
                    proposalFor(snapshot, "my dog's name is Pixel", "Shai's dog is named Pixel."),
                ),
            )
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines and my dog's name is Pixel.",
            "Those are two independently grounded facts.",
        )

        enqueueAndRun(sourceTurn)

        val memories = database.memoryDao().recentMemories(10)
        assertEquals(1, memories.count { it.meaning == "Shai loves sardines." })
        assertEquals(1, memories.count { it.meaning == "Shai's dog is named Pixel." })
        val evidence = memories.flatMap { database.memoryDao().evidenceForMemory(it.id) }
        assertEquals(2, evidence.map { sourceClaimSuppressionHash(it.experienceId, it.lineageKey) }.toSet().size)
    }

    @Test
    fun admittedFirstChildDoesNotHideNewSiblingFromSameCompoundSource() = runBlocking {
        var includePixel = false
        model.extractionOverride = { snapshot ->
            CandidateExtractionProposal(buildList {
                add(proposalFor(snapshot, "I love sardines", "Shai loves sardines."))
                if (includePixel) {
                    add(proposalFor(snapshot, "my dog's name is Pixel", "Shai's dog is named Pixel."))
                }
            })
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines and my dog's name is Pixel.",
            "I will preserve their separate source anchors.",
        )
        enqueueAndRun(sourceTurn)
        val sardineMemory = database.memoryDao().recentMemories(10).single()

        includePixel = true
        val replay = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(listOf(sardineMemory.id), replay.existingMemoryIds)
        assertEquals(1, replay.createdCandidateIds.size)
        assertEquals(
            "Shai's dog is named Pixel.",
            database.memoryDao().candidateMemory(replay.createdCandidateIds.single())?.proposedMeaning,
        )
    }

    @Test
    fun forgettingOneChildOfCompoundSourcePreservesUnrelatedSibling() = runBlocking {
        var replay = false
        model.extractionOverride = { snapshot ->
            val sardineMeaning = if (replay) {
                "Sardines are a food Shai loves."
            } else {
                "Shai loves sardines."
            }
            val sardines = proposalFor(snapshot, "I love sardines", sardineMeaning)
            val pixel = proposalFor(snapshot, "my dog's name is Pixel", "Shai's dog is named Pixel.")
            CandidateExtractionProposal(if (replay) listOf(pixel, sardines) else listOf(sardines, pixel))
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines and my dog's name is Pixel.",
            "I will preserve their separate source anchors.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }
        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Forgotten,
        )

        reopenDatabase()
        replay = true
        val reextracted = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(1, reextracted.suppressedLineageCount)
        assertTrue(reextracted.createdCandidateIds.isEmpty())
        assertEquals(listOf(pixelMemory.id), reextracted.existingMemoryIds)
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
    }

    @Test
    fun forgetCoverageBlocksNarrowerBroaderPunctuationAndPartialOverlapButKeepsAdjacentSibling() = runBlocking {
        var replay = false
        model.extractionOverride = { snapshot ->
            if (!replay) {
                CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "I love sardines;", "Shai loves sardines."),
                        proposalFor(snapshot, "my dog is Pixel.", "Shai's dog is Pixel."),
                    ),
                )
            } else {
                CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "I love sardines", "Narrower sardine claim."),
                        proposalFor(snapshot, "sardines;", "Punctuation-boundary sardine claim."),
                        proposalFor(snapshot, "I love sardines;my", "Broader combined claim."),
                        proposalFor(snapshot, "sardines;my", "Partially overlapping combined claim."),
                        proposalFor(snapshot, "my dog is Pixel.", "Shai's dog is Pixel."),
                    ),
                )
            }
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines;my dog is Pixel.",
            "I will keep those independently anchored facts.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }
        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.forget(ManualForgetMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Forgotten,
        )

        replay = true
        val result = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(4, result.suppressedLineageCount)
        assertTrue(result.createdCandidateIds.isEmpty())
        assertEquals(listOf(pixelMemory.id), result.existingMemoryIds)
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
        assertEquals(1L, rowCount("suppression_source_coverages"))
        assertEquals(0 to 16, singleCoverageRange())
    }

    @Test
    fun deleteCoverageBlocksBoundaryDriftAndPartialOverlapButKeepsDisjointSibling() = runBlocking {
        var replay = false
        model.extractionOverride = { snapshot ->
            if (!replay) {
                CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "I love sardines", "Shai loves sardines."),
                        proposalFor(snapshot, "my dog is Pixel.", "Shai's dog is Pixel."),
                    ),
                )
            } else {
                CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "love sardines", "Narrower deleted claim."),
                        proposalFor(snapshot, "I love sardines;", "Punctuation-expanded deleted claim."),
                        proposalFor(snapshot, "love sardines;my", "Partial deleted overlap."),
                        proposalFor(snapshot, "I love sardines;my", "Broad deleted overlap."),
                        proposalFor(snapshot, "my dog is Pixel.", "Shai's dog is Pixel."),
                    ),
                )
            }
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines;my dog is Pixel.",
            "I will keep those independently anchored facts.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }
        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.delete(ManualDeleteMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Deleted,
        )

        replay = true
        val result = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(4, result.suppressedLineageCount)
        assertTrue(result.createdCandidateIds.isEmpty())
        assertEquals(listOf(pixelMemory.id), result.existingMemoryIds)
        assertEquals(null, database.memoryDao().memory(sardineMemory.id))
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
        assertEquals(1L, rowCount("suppression_source_coverages"))
        assertEquals(0 to 15, singleCoverageRange())
    }

    @Test
    fun deleteSurvivesInsertedOmittedAndReappearingReplayWhileSiblingRemains() = runBlocking {
        var extractionShape = 0
        model.extractionOverride = { snapshot ->
            when (extractionShape) {
                0 -> CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "I love sardines", "Shai loves sardines."),
                        proposalFor(snapshot, "My dog's name is Pixel", "Shai's dog is named Pixel."),
                    ),
                )
                1 -> CandidateExtractionProposal(
                    listOf(
                        proposalFor(snapshot, "My favorite tea is oolong", "Shai's favorite tea is oolong."),
                        proposalFor(snapshot, "I love sardines", "Sardines are a food Shai loves."),
                    ),
                )
                else -> CandidateExtractionProposal(
                    listOf(proposalFor(snapshot, "My dog's name is Pixel", "Shai's dog is named Pixel.")),
                )
            }
        }
        val sourceTurn = appendSuccessfulTurn(
            "I love sardines. My favorite tea is oolong. My dog's name is Pixel.",
            "I will keep the independently grounded facts distinct.",
        )
        enqueueAndRun(sourceTurn)
        val initial = database.memoryDao().recentMemories(10)
        val sardineMemory = initial.single { it.meaning.contains("sardine", ignoreCase = true) }
        val pixelMemory = initial.single { it.meaning.contains("Pixel") }

        val manual = ManualMemoryIntentService(database, scheduler)
        assertTrue(
            manual.delete(ManualDeleteMemoryInput(sardineMemory.id, now())) is
                ManualMemoryIntentResult.Deleted,
        )
        extractionShape = 1
        val insertionAndOmission = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted

        assertEquals(1, insertionAndOmission.suppressedLineageCount)
        assertEquals(1, insertionAndOmission.createdCandidateIds.size)
        assertEquals(
            "Shai's favorite tea is oolong.",
            database.memoryDao().candidateMemory(insertionAndOmission.createdCandidateIds.single())?.proposedMeaning,
        )
        assertNotNull(database.memoryDao().memory(pixelMemory.id))
        assertEquals(null, database.memoryDao().memory(sardineMemory.id))

        extractionShape = 2
        val reappearedSibling = CandidateExtractionService(database, model).extract(
            ExtractCandidateMemoriesInput(experienceFor(sourceTurn.userMessageId), now()),
        ) as CandidateExtractionResult.Extracted
        assertEquals(listOf(pixelMemory.id), reappearedSibling.existingMemoryIds)
        assertTrue(reappearedSibling.createdCandidateIds.isEmpty())
        assertFalse(database.memoryDao().recentMemories(10).any {
            it.meaning.contains("sardine", ignoreCase = true)
        })
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

    @Test
    fun consolidationStageSurvivesInterruptionRelaunchAndOverlappingClaim() = runBlocking {
        enqueueAndRun(appendSuccessfulTurn("I love sardines.", "Noted."))
        val second = appendSuccessfulTurn("My favorite tea is oolong.", "Also noted.")
        val queued = queue.ensureForSucceededRun(second.runId, second.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val userJob = checkNotNull(database.automaticMemoryDao().jobForMessage(second.userMessageId))
        model.cancelNextConsolidation = true

        val interrupted = runCatching { runner.run(userJob.id) }.exceptionOrNull()

        assertTrue(interrupted is CancellationException)
        val durable = checkNotNull(database.automaticMemoryDao().job(userJob.id))
        assertEquals(AutomaticMemoryJobState.RUNNING, durable.state)
        assertEquals(AutomaticMemoryJobStage.CONSOLIDATION, durable.nextStage)
        assertTrue(runner.run(userJob.id) is AutomaticMemoryJobRunResult.AlreadyRunning)

        clock.addAndGet(com.shai.riven.data.background.AUTOMATIC_MEMORY_RUNNING_LEASE_MS + 1L)
        reopenDatabase()
        assertTrue(runner.run(userJob.id) is AutomaticMemoryJobRunResult.Succeeded)
        assertEquals(2L, rowCount("consolidation_checkpoints"))
        queued.jobIds.filterNot { it == userJob.id }.forEach { jobId ->
            assertTrue(runner.run(jobId) is AutomaticMemoryJobRunResult.Succeeded)
        }
    }

    @Test
    fun workerContinuationDrainsAllConsolidationPatternsAcrossRestarts() = runBlocking {
        listOf("alpha", "beta", "gamma", "delta").forEachIndexed { index, suffix ->
            insertConsolidationSource("drain-$suffix", 10_000L + index)
        }
        model.consolidationPairs = listOf(
            listOf("drain-alpha", "drain-beta"),
            listOf("drain-gamma", "drain-delta"),
        )
        val turn = appendSuccessfulTurn("No memory candidate in this turn.", "Acknowledged.")
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        val jobId = checkNotNull(database.automaticMemoryDao().jobForMessage(turn.userMessageId)).id

        fun continuationRunner() = AutomaticMemoryJobRunner(
            database = database,
            modelFactory = AutomaticMemoryModelFactory { AutomaticMemoryModelFactoryResult.Ready(model) },
            clock = ::now,
            maxConsolidationPassesPerRun = 1,
            reconcileShortWindowSweep = { completedAt ->
                queue.reconcileShortWindowSchedule(completedAt)
                Unit
            },
        )
        fun worker(runner: AutomaticMemoryJobRunner) = RivenBackgroundWorkExecutor(
            attachmentMaintenance = object : AttachmentMaintenanceOperations {
                override suspend fun cleanupTarget(attachmentId: String) =
                    TargetedAttachmentCleanupResult.NoOp(
                        attachmentId,
                        AttachmentCleanupNoOpReason.MISSING,
                    )

                override suspend fun runMaintenance() = AttachmentMaintenanceResult.Completed(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    moreWorkRemaining = false,
                )
            },
            repairJobRunner = RepairJobRunOperations { id ->
                RepairJobRunResult.NoOp(id, RepairJobNoOpReason.MISSING)
            },
            repairSweep = RepairSweepOperations {
                RepairSweepResult.Completed(emptyList(), emptyList(), moreWorkRemaining = false)
            },
            automaticMemoryJobRunner = runner,
        )

        assertEquals(
            RivenBackgroundExecutionOutcome.Retryable,
            worker(continuationRunner()).execute(RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB, jobId),
        )
        assertEquals(0, database.automaticMemoryDao().job(jobId)?.attemptCount)
        assertEquals(AutomaticMemoryJobStage.CONSOLIDATION, database.automaticMemoryDao().job(jobId)?.nextStage)
        reopenDatabase()
        assertEquals(
            RivenBackgroundExecutionOutcome.Retryable,
            worker(continuationRunner()).execute(RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB, jobId),
        )
        reopenDatabase()
        assertEquals(
            RivenBackgroundExecutionOutcome.Completed,
            worker(continuationRunner()).execute(RivenBackgroundWorkKind.AUTOMATIC_MEMORY_JOB, jobId),
        )

        assertEquals(AutomaticMemoryJobState.SUCCEEDED, database.automaticMemoryDao().job(jobId)?.state)
        assertEquals(
            2,
            database.memoryDao().recentMemories(20).count { it.epistemicBasis == EpistemicBasis.CONSOLIDATION },
        )
        assertEquals(3L, rowCount("consolidation_checkpoints"))
        queued.jobIds.filterNot { it == jobId }.forEach { otherJobId ->
            assertEquals(AutomaticMemoryJobState.PENDING, database.automaticMemoryDao().job(otherJobId)?.state)
        }
    }

    private suspend fun enqueueAndRun(turn: Turn) {
        val queued = queue.ensureForSucceededRun(turn.runId, turn.finalRevision, now())
            as AutomaticMemoryEnqueueResult.Enqueued
        queued.jobIds.forEach { jobId ->
            val result = runner.run(jobId)
            assertTrue("Unexpected automatic-memory result $result", result is AutomaticMemoryJobRunResult.Succeeded)
        }
    }

    private fun proposalFor(
        snapshot: CandidateExtractionSnapshot,
        sourceFragment: String,
        meaning: String,
        kind: MemoryKind = MemoryKind.SEMANTIC,
    ): CandidateMemoryProposal {
        val sourceClaimId = candidateSourceClaims(snapshot.sourceContent).single {
            it.text.contains(sourceFragment, ignoreCase = true)
        }.id
        return CandidateMemoryProposal(
            proposedMeaning = meaning,
            proposedKind = kind,
            proposedScope = MemoryScope.SHAI,
            proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
            proposedCertainty = MemoryCertainty.CERTAIN,
            proposedState = CandidateMemoryState.READY_FOR_VALIDATION,
            proposedSensitivity = SensitivityLevel.STANDARD,
            sourceClaimId = sourceClaimId,
            sourceAnchor = CandidateSourceAnchor(sourceFragment, 0),
        )
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

    private fun insertConsolidationSource(memoryId: String, eventOrder: Long) {
        val experienceId = "$memoryId-experience"
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = eventOrder,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = "$memoryId source",
                occurredAt = eventOrder,
                recordedAt = eventOrder,
                sensitivity = SensitivityLevel.STANDARD,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = "$memoryId meaning",
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = MemoryCertainty.CERTAIN,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = eventOrder,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = eventOrder,
                updatedAt = eventOrder,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memoryId,
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "$memoryId-lineage",
                createdAt = eventOrder,
            ),
        )
    }

    private fun rowCount(table: String): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun singleCoverageRange(): Pair<Int, Int> = database.openHelper.readableDatabase
        .query("SELECT start_offset, end_offset_exclusive FROM suppression_source_coverages")
        .use { cursor ->
            check(cursor.moveToFirst())
            val range = cursor.getInt(0) to cursor.getInt(1)
            check(!cursor.moveToNext())
            range
        }

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
            reconcileShortWindowSweep = { completedAt ->
                queue.reconcileShortWindowSchedule(completedAt)
                Unit
            },
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
        var shortWindowSweepCount = 0
        val shortWindowDelays = mutableListOf<Long>()

        override fun enqueueAttachmentCleanup(attachmentId: String) = enqueued("attachment")
        override fun enqueueAttachmentMaintenanceSweep() = enqueued("attachment-sweep")
        override fun enqueueRepairJob(repairJobId: String) = enqueued("repair-$repairJobId")
        override fun enqueueRepairSweep() = enqueued("repair-sweep")
        override fun enqueueAutomaticMemoryJob(automaticMemoryJobId: String): RivenBackgroundScheduleResult {
            automaticMemoryJobIds += automaticMemoryJobId
            return enqueued(automaticMemoryJobId)
        }
        override fun enqueueAutomaticMemorySweep() = enqueued("memory-sweep")
        override fun enqueueAutomaticMemoryShortWindowSweep(
            initialDelayMs: Long,
        ): RivenBackgroundScheduleResult {
            shortWindowSweepCount += 1
            shortWindowDelays += initialDelayMs
            return enqueued("short-window")
        }
        override fun ensurePeriodicMaintenance() = enqueued("periodic")

        private fun enqueued(name: String) = RivenBackgroundScheduleResult.Enqueued(listOf(name))
    }

    private class SpecFakeMemoryModel : AutomaticMemoryModel {
        var totalCalls = 0
        var sawCompletedTurnHindsight = false
        var paraphraseKnownFacts = false
        var forceSingleLineageSelfDevelopment = false
        var deferSardinesUntilKeepIt = false
        var twoFactShortWindow = false
        var extractionOverride: ((CandidateExtractionSnapshot) -> CandidateExtractionProposal)? = null
        var cancelNextSardineAnalysis = false
        var cancelNextConsolidation = false
        var consolidationPairs: List<List<String>> = emptyList()
        var sardineAnalysisCalls = 0

        override suspend fun analyze(snapshot: ImmediateAttentionSnapshot): ImmediateAttentionProposal {
            totalCalls += 1
            val source = snapshot.sourceContent.orEmpty()
            if (source.contains("sardines", ignoreCase = true)) {
                sardineAnalysisCalls += 1
                if (cancelNextSardineAnalysis) {
                    cancelNextSardineAnalysis = false
                    throw CancellationException("simulated interruption after claim")
                }
                sawCompletedTurnHindsight = snapshot.followingActiveContext.isNotEmpty()
                if (
                    deferSardinesUntilKeepIt &&
                    snapshot.followingActiveContext.none { it.content.contains("keep it", ignoreCase = true) }
                ) {
                    return ImmediateAttentionProposal(AttentionOutcome.DEFER_FOR_CONTEXT)
                }
            }
            val signal = when {
                source.startsWith("Correction:") -> PositiveAttentionSignal.CORRECTION_OR_REVISION
                source.contains("dog's name is Pixel", ignoreCase = true) -> PositiveAttentionSignal.PREFERENCE
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
            snapshot: CandidateExtractionSnapshot,
        ): CandidateExtractionProposal {
            totalCalls += 1
            extractionOverride?.let { return it(snapshot) }
            val source = snapshot.sourceContent.orEmpty()
            val sourceClaims = candidateSourceClaims(source)
            fun claimIdContaining(text: String): String = sourceClaims.single {
                it.text.contains(text, ignoreCase = true)
            }.id
            fun sourceAnchorFor(text: String) = CandidateSourceAnchor(
                sourceClaims.single { it.text.contains(text, ignoreCase = true) }.text,
                0,
            )
            if (twoFactShortWindow && source.contains("dog's name is Pixel", ignoreCase = true)) {
                val resolved = snapshot.followingActiveContext.any {
                    it.content.contains("permanent name", ignoreCase = true)
                }
                return CandidateExtractionProposal(
                    listOf(
                        CandidateMemoryProposal(
                            if (resolved) "Sardines are a food Shai loves." else "Shai loves sardines.",
                            MemoryKind.SEMANTIC,
                            MemoryScope.SHAI,
                            EpistemicBasis.DIRECT_USER_STATEMENT,
                            MemoryCertainty.CERTAIN,
                            CandidateMemoryState.READY_FOR_VALIDATION,
                            SensitivityLevel.STANDARD,
                            claimIdContaining("sardines"),
                            sourceAnchorFor("sardines"),
                        ),
                        CandidateMemoryProposal(
                            "Shai's dog is named Pixel.",
                            MemoryKind.SEMANTIC,
                            MemoryScope.SHAI,
                            EpistemicBasis.DIRECT_USER_STATEMENT,
                            if (resolved) MemoryCertainty.CERTAIN else MemoryCertainty.UNCERTAIN,
                            if (resolved) {
                                CandidateMemoryState.READY_FOR_VALIDATION
                            } else {
                                CandidateMemoryState.PENDING_CONTEXT
                            },
                            if (resolved) SensitivityLevel.STANDARD else SensitivityLevel.SENSITIVE,
                            claimIdContaining("dog's name is Pixel"),
                            sourceAnchorFor("dog's name is Pixel"),
                        ),
                    ),
                )
            }
            val proposal = when {
                source.contains("sardines", ignoreCase = true) -> CandidateMemoryProposal(
                    if (paraphraseKnownFacts) "Sardines are a food Shai loves." else "Shai loves sardines.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                    claimIdContaining("sardines"),
                    sourceAnchorFor("sardines"),
                )
                source.contains("favorite tea", ignoreCase = true) -> CandidateMemoryProposal(
                    if (paraphraseKnownFacts) "Oolong is Shai's favorite tea." else "Shai's favorite tea is oolong.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                    claimIdContaining("favorite tea"),
                    sourceAnchorFor("favorite tea"),
                )
                source.startsWith("Correction:") -> CandidateMemoryProposal(
                    "Shai grew up in Brooklyn and currently lives in Kansas.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.EXPLICIT_CORRECTION,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                    claimIdContaining("Correction"),
                    sourceAnchorFor("Correction"),
                )
                source.contains("grew up in Kansas", ignoreCase = true) -> CandidateMemoryProposal(
                    "Shai grew up in Kansas.",
                    MemoryKind.SEMANTIC,
                    MemoryScope.SHAI,
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                    claimIdContaining("grew up in Kansas"),
                    sourceAnchorFor("grew up in Kansas"),
                )
                source.contains("discovered that I love playing chess", ignoreCase = true) -> CandidateMemoryProposal(
                    "Riven discovered that he loves playing chess.",
                    if (forceSingleLineageSelfDevelopment) MemoryKind.SELF_DEVELOPMENT else MemoryKind.EPISODIC,
                    MemoryScope.RIVEN,
                    EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                    MemoryCertainty.CERTAIN,
                    CandidateMemoryState.READY_FOR_VALIDATION,
                    SensitivityLevel.STANDARD,
                    claimIdContaining("playing chess"),
                    sourceAnchorFor("playing chess"),
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

        override suspend fun proposeConsolidation(
            snapshot: com.shai.riven.data.memory.ConsolidationSnapshot,
        ): com.shai.riven.data.memory.ConsolidationProposal {
            totalCalls += 1
            if (cancelNextConsolidation) {
                cancelNextConsolidation = false
                throw CancellationException("simulated consolidation interruption")
            }
            val availableIds = snapshot.sources.mapTo(hashSetOf()) { it.memoryId }
            val processed = snapshot.processedSourceSets.mapTo(hashSetOf()) { it.toSet() }
            val next = consolidationPairs.firstOrNull { pair ->
                pair.all(availableIds::contains) && pair.toSet() !in processed
            }
            if (next != null) {
                return com.shai.riven.data.memory.ConsolidationProposal.Create(
                    sourceMemoryIds = next,
                    meaning = "Consolidated ${next.joinToString(" and ")}",
                    kind = MemoryKind.SEMANTIC,
                    scope = MemoryScope.SHAI,
                    certainty = MemoryCertainty.CERTAIN,
                    sensitivity = SensitivityLevel.STANDARD,
                )
            }
            return com.shai.riven.data.memory.ConsolidationProposal.NoConsolidation
        }
    }

    private companion object {
        const val DATABASE_NAME = "automatic-memory-integration.db"
        const val CONVERSATION_ID = "automatic-memory-conversation"
    }
}
