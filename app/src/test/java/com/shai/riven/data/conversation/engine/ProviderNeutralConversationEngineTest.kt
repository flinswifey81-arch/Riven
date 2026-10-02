package com.shai.riven.data.conversation.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.candidate.CandidateExtractionProposal
import com.shai.riven.data.candidate.CandidateExtractionResult
import com.shai.riven.data.candidate.CandidateExtractionService
import com.shai.riven.data.candidate.CandidateIdGenerator
import com.shai.riven.data.candidate.CandidateMemoryExtractor
import com.shai.riven.data.candidate.CandidateMemoryProposal
import com.shai.riven.data.candidate.candidateSourceClaims
import com.shai.riven.data.candidate.ExtractCandidateMemoriesInput
import com.shai.riven.data.context.ActiveConversationContextSource
import com.shai.riven.data.context.ConversationalContextAssembler
import com.shai.riven.data.context.EphemeralAppStateContextSource
import com.shai.riven.data.context.EphemeralAppStateExposure
import com.shai.riven.data.context.EphemeralAppStateStore
import com.shai.riven.data.context.EphemeralAppStateWriteResult
import com.shai.riven.data.context.PublishEphemeralAppStateInput
import com.shai.riven.data.context.ProviderProfileReceiptValidator
import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextCollectionResult
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextFreshnessReceipt
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextPayload
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSource
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceDescriptor
import com.shai.riven.data.context.RivenContextSourceError
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.context.RivenContextSourceResult
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.RewindTimelineInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.credential.ClearProviderCredentialsResult
import com.shai.riven.data.credential.DeleteProviderCredentialResult
import com.shai.riven.data.credential.HasProviderCredentialResult
import com.shai.riven.data.credential.ProviderCredentialStore
import com.shai.riven.data.credential.ProviderCredentialError
import com.shai.riven.data.credential.ProviderSecret
import com.shai.riven.data.credential.PutProviderCredentialResult
import com.shai.riven.data.credential.ReadProviderCredentialResult
import com.shai.riven.data.deletion.DeleteTimelineMessageInput
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.deletion.TimelineDeleteResult
import com.shai.riven.data.experience.ConversationExperienceLookupResult
import com.shai.riven.data.experience.ConversationExperienceService
import com.shai.riven.data.instructions.SaveShaiSystemInstructionsInput
import com.shai.riven.data.instructions.ShaiSystemInstructionsContextSource
import com.shai.riven.data.instructions.ShaiSystemInstructionsService
import com.shai.riven.data.instructions.ShaiSystemInstructionsWriteResult
import com.shai.riven.data.memory.MemoryStateTransitionInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.AttachmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.MessageAttachmentEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.AttachmentKind
import com.shai.riven.data.persistence.model.AttachmentSource
import com.shai.riven.data.persistence.model.AttachmentState
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationRunState
import com.shai.riven.data.persistence.model.ConversationRunTrigger
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.provider.CreateProviderProfileInput
import com.shai.riven.data.provider.CreateProviderProfileResult
import com.shai.riven.data.provider.ProviderCapability
import com.shai.riven.data.provider.ProviderProfileService
import com.shai.riven.data.provider.ProviderRuntimeProfileResolver
import com.shai.riven.data.provider.UpdateProviderProfileInput
import com.shai.riven.data.provider.UpdateProviderProfileResult
import com.shai.riven.data.recall.ConversationalRecallReceiptValidator
import com.shai.riven.data.recall.ConversationalMemoryContextSource
import com.shai.riven.data.recall.ConversationalMemoryQuery
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import com.shai.riven.data.validation.CandidateValidationDecision
import com.shai.riven.data.validation.CandidateValidationDecider
import com.shai.riven.data.validation.CandidateValidationOutcome
import com.shai.riven.data.validation.CandidateValidationResult
import com.shai.riven.data.validation.CandidateValidationService
import com.shai.riven.data.validation.ValidateCandidateInput
import com.shai.riven.data.validation.ValidatedMemoryIdGenerator
import com.shai.riven.data.validation.ValidationAdmissionMetadata
import com.shai.riven.data.validation.ValidationMemoryRetrieval
import com.shai.riven.data.validation.ValidationMemoryRetriever
import com.shai.riven.data.validation.ValidationRecallReadiness
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.ValidationRecallGeneration
import com.shai.riven.data.validation.validationRecallCorpusFence
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
class ProviderNeutralConversationEngineTest {
    private lateinit var database: RivenDatabase
    private lateinit var timeline: ConversationTimelineService
    private lateinit var profileService: ProviderProfileService
    private lateinit var instructions: ShaiSystemInstructionsService
    private lateinit var ephemeral: EphemeralAppStateStore
    private val clock = AtomicLong(100)
    private val engines = mutableListOf<ProviderNeutralConversationEngine>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeline = ConversationTimelineService(database)
        profileService = ProviderProfileService(database)
        instructions = ShaiSystemInstructionsService(database)
        ephemeral = EphemeralAppStateStore()
    }

    @After
    fun tearDown() {
        engines.forEach(ProviderNeutralConversationEngine::close)
        database.close()
    }

    @Test
    fun successPersistsPendingBeforeInvocationThenAtomicallySelectsSucceededAssistantAndExperience() = runBlocking {
        createConversationAndUser()
        createProfile()
        val adapter = FakeAdapter { request, emit ->
            val pending = database.conversationTimelineDao().message("assistant-1")!!
            val head = database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!
            val run = database.conversationRunDao().run("run-1")!!
            assertEquals(MessageDeliveryState.PENDING, pending.deliveryState)
            assertEquals("user-1", head.activeHeadMessageId)
            assertEquals(ConversationRunState.AWAITING_PROVIDER, run.state)
            assertTrue(request.context.any { it.content.contains("Hello Riven") })
            assertEquals(
                listOf(MessageRole.USER),
                request.context.filter { it.sourceId == ActiveConversationContextSource.SOURCE_ID }
                    .map { it.conversationRole },
            )
            assertTrue(
                ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                    ConversationExperienceLookupResult.NotRecorded,
            )
            emit(ProviderStreamEvent.Delta("Hello "))
            emit(ProviderStreamEvent.Delta("Shai"))
            emit(ProviderStreamEvent.Completed("provider-request-1"))
        }
        val engine = engine(adapter)

        val result = engine.execute(input()) as ConversationEngineResult.Succeeded

        assertEquals(3L, result.timelineRevision)
        assertEquals(ConversationRunState.SUCCEEDED, result.run.state)
        val assistant = database.conversationTimelineDao().message("assistant-1")!!
        assertEquals(MessageDeliveryState.SUCCEEDED, assistant.deliveryState)
        assertEquals("Hello Shai", assistant.content)
        assertEquals("fake.adapter", assistant.providerName)
        assertEquals("model-a", assistant.providerModel)
        assertEquals("provider-request-1", assistant.providerRequestId)
        val active = timeline.activeTimeline(CONVERSATION_ID) as TimelineReadResult.Success
        assertEquals(listOf("user-1", "assistant-1"), active.messages.map { it.id })
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.Found,
        )
        assertEquals(1, adapter.invocationCount.get())
    }

    @Test
    fun midstreamFailureDiscardsPartialOutputKeepsUserSelectedAndCreatesNoAssistantExperience() = runBlocking {
        createConversationAndUser()
        createProfile()
        val adapter = FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("partial secret-looking response"))
            emit(ProviderStreamEvent.Failure(ProviderFailureCode.UNAVAILABLE, "request-failed"))
        }
        val result = engine(adapter).execute(input()) as ConversationEngineResult.Failed

        assertEquals(ConversationEngineErrorCode.PROVIDER_FAILURE, result.code)
        val assistant = database.conversationTimelineDao().message("assistant-1")!!
        assertEquals(MessageDeliveryState.FAILED, assistant.deliveryState)
        assertEquals("", assistant.content)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun duplicateKeySameInputReturnsExistingButMismatchedInputFailsWithoutSecondInvocation() = runBlocking {
        createConversationAndUser()
        createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("done"))
            emit(ProviderStreamEvent.Completed())
        }
        val firstEngine = engine(adapter)
        val secondEngine = engine(adapter)
        val first = async { firstEngine.execute(input()) }
        entered.await()

        assertTrue(secondEngine.execute(input()) is ConversationEngineResult.Existing)
        val mismatch = secondEngine.execute(input().copy(profileId = "different-profile"))
            as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.IDEMPOTENCY_MISMATCH, mismatch.code)
        assertEquals(1, adapter.invocationCount.get())
        release.complete(Unit)
        assertTrue(first.await() is ConversationEngineResult.Succeeded)
    }

    @Test
    fun databaseActiveSlotRejectsDifferentConcurrentAttemptAcrossEngineInstances() = runBlocking {
        createConversationAndUser()
        createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("done"))
            emit(ProviderStreamEvent.Completed())
        }
        val first = async { engine(adapter).execute(input()) }
        entered.await()

        val second = engine(adapter).execute(
            input().copy(runId = "run-2", idempotencyKey = "key-2", assistantMessageId = "assistant-2"),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.ACTIVE_RUN_EXISTS, second.code)
        assertNull(database.conversationTimelineDao().message("assistant-2"))
        release.complete(Unit)
        first.await()
        Unit
    }

    @Test
    fun providerRunMayOutlivePermitQueueTimeoutWhenItRemainsWithinProviderTimeout() = runBlocking {
        createConversationAndUser()
        createProfile()
        val result = engine(
            adapter = FakeAdapter { _, emit ->
                delay(150)
                emit(ProviderStreamEvent.Delta("slow success"))
                emit(ProviderStreamEvent.Completed())
            },
            limits = ConversationEngineLimits(
                maxProviderDurationMillis = 1_000,
                concurrencyWaitMillis = 25,
            ),
        ).execute(input())

        assertTrue(result is ConversationEngineResult.Succeeded)
        assertEquals(ConversationRunState.SUCCEEDED, database.conversationRunDao().run("run-1")!!.state)
    }

    @Test
    fun permitReturnsWhenTimeoutWinsAfterAcquisitionBeforeResultDelivery() = runBlocking {
        val permitRecorded = CompletableDeferred<Unit>()
        val holdResultDelivery = CompletableDeferred<Unit>()
        val acquisitionCount = AtomicInteger(0)
        val limiter = ConversationRunLimiter(maximumConcurrentRuns = 1) {
            if (acquisitionCount.getAndIncrement() == 0) {
                permitRecorded.complete(Unit)
                holdResultDelivery.await()
            }
        }
        val timedOut = async {
            limiter.withPermitOrNull(timeoutMillis = 50) {
                error("timed-out acquisition must not run its block")
            }
        }
        withTimeout(1_000) { permitRecorded.await() }

        assertNull(withTimeout(1_000) { timedOut.await() })
        val later = withTimeout(1_000) {
            limiter.withPermitOrNull(timeoutMillis = 500) { "later request ran" }
        }

        assertEquals("later request ran", later)
        assertEquals(2, acquisitionCount.get())
    }

    @Test
    fun saturatedPermitTimesOutQueuedRunWithoutInvokingItsAdapter() = runBlocking {
        createConversationAndUser()
        createProfile()
        createConversationAndUser("conversation-2", "user-2")
        createProfile("profile-2")
        val limiter = ConversationRunLimiter(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstAdapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("first"))
            emit(ProviderStreamEvent.Completed())
        }
        val first = async {
            engine(
                adapter = firstAdapter,
                limiter = limiter,
                limits = ConversationEngineLimits(concurrencyWaitMillis = 1_000),
            ).execute(input())
        }
        entered.await()
        val queuedAdapter = FakeAdapter { _, _ -> error("queued adapter must not be invoked") }
        val queued = engine(
            adapter = queuedAdapter,
            limiter = limiter,
            limits = ConversationEngineLimits(concurrencyWaitMillis = 50),
        ).execute(
            input().copy(
                runId = "run-queued",
                idempotencyKey = "key-queued",
                conversationId = "conversation-2",
                userMessageId = "user-2",
                assistantMessageId = "assistant-queued",
                profileId = "profile-2",
            ),
        ) as ConversationEngineResult.Failed

        assertEquals(ConversationEngineErrorCode.CONCURRENCY_LIMIT, queued.code)
        assertEquals(0, queuedAdapter.invocationCount.get())
        assertEquals(ConversationRunState.FAILED, database.conversationRunDao().run("run-queued")!!.state)
        assertNull(database.conversationRunDao().activeRun("conversation-2"))
        release.complete(Unit)
        assertTrue(first.await() is ConversationEngineResult.Succeeded)
    }

    @Test
    fun fourActiveEightQueuedAndFourFollowUpRunsLeaveNoSlotsOrPermitsLeaked() = runBlocking {
        repeat(16) { index ->
            createConversationAndUser("pressure-conversation-$index", "pressure-user-$index")
        }
        createProfile()
        val limiter = ConversationRunLimiter(4)
        val activeEntered = AtomicInteger(0)
        val allActiveEntered = CompletableDeferred<Unit>()
        val releaseActive = CompletableDeferred<Unit>()
        val activeAdapter = FakeAdapter { _, emit ->
            if (activeEntered.incrementAndGet() == 4) allActiveEntered.complete(Unit)
            releaseActive.await()
            emit(ProviderStreamEvent.Delta("active success"))
            emit(ProviderStreamEvent.Completed())
        }
        fun pressureInput(index: Int, prefix: String) = input().copy(
            runId = "$prefix-run-$index",
            idempotencyKey = "$prefix-key-$index",
            conversationId = "pressure-conversation-$index",
            userMessageId = "pressure-user-$index",
            assistantMessageId = "$prefix-assistant-$index",
        )
        val active = (0 until 4).map { index ->
            async {
                engine(
                    adapter = activeAdapter,
                    limiter = limiter,
                    limits = ConversationEngineLimits(concurrencyWaitMillis = 1_000),
                ).execute(pressureInput(index, "active"))
            }
        }
        withTimeout(5_000) { allActiveEntered.await() }

        val cancelledAdapter = FakeAdapter { _, _ -> error("cancelled queued adapter must not run") }
        val cancelled = (4 until 8).map { index ->
            async {
                engine(
                    adapter = cancelledAdapter,
                    limiter = limiter,
                    limits = ConversationEngineLimits(concurrencyWaitMillis = 5_000),
                ).execute(pressureInput(index, "cancelled"))
            }
        }
        withTimeout(5_000) {
            while ((4 until 8).any { database.conversationRunDao().run("cancelled-run-$it") == null }) yield()
        }
        cancelled.forEach { it.cancel() }
        cancelled.forEach { execution ->
            var propagated = false
            try {
                execution.await()
            } catch (_: CancellationException) {
                propagated = true
            }
            assertTrue(propagated)
        }

        val timedOutAdapter = FakeAdapter { _, _ -> error("timed-out queued adapter must not run") }
        val timedOut = (8 until 12).map { index ->
            async {
                engine(
                    adapter = timedOutAdapter,
                    limiter = limiter,
                    limits = ConversationEngineLimits(concurrencyWaitMillis = 50),
                ).execute(pressureInput(index, "timed"))
            }
        }.map { it.await() as ConversationEngineResult.Failed }
        assertTrue(timedOut.all { it.code == ConversationEngineErrorCode.CONCURRENCY_LIMIT })
        assertEquals(0, cancelledAdapter.invocationCount.get())
        assertEquals(0, timedOutAdapter.invocationCount.get())

        releaseActive.complete(Unit)
        assertTrue(active.all { it.await() is ConversationEngineResult.Succeeded })
        val followUpEntered = AtomicInteger(0)
        val allFollowUpsEntered = CompletableDeferred<Unit>()
        val releaseFollowUps = CompletableDeferred<Unit>()
        val followUpAdapter = FakeAdapter { _, emit ->
            if (followUpEntered.incrementAndGet() == 4) allFollowUpsEntered.complete(Unit)
            releaseFollowUps.await()
            emit(ProviderStreamEvent.Delta("follow-up success"))
            emit(ProviderStreamEvent.Completed())
        }
        val followUps = (12 until 16).map { index ->
            async {
                engine(
                    adapter = followUpAdapter,
                    limiter = limiter,
                    limits = ConversationEngineLimits(concurrencyWaitMillis = 1_000),
                ).execute(pressureInput(index, "follow-up"))
            }
        }
        withTimeout(5_000) { allFollowUpsEntered.await() }
        releaseFollowUps.complete(Unit)
        assertTrue(followUps.all { it.await() is ConversationEngineResult.Succeeded })
        assertEquals(4, activeAdapter.invocationCount.get())
        assertEquals(4, followUpAdapter.invocationCount.get())
        (0 until 16).forEach { index ->
            assertNull(database.conversationRunDao().activeRun("pressure-conversation-$index"))
        }
    }

    @Test
    fun productionStreamCharacterAndEventBoundariesFailClosedOnePastTheLimit() = runBlocking {
        repeat(3) { index -> createConversationAndUser("stream-conversation-$index", "stream-user-$index") }
        createProfile()
        val limits = ConversationEngineLimits(
            maxOutputChars = 262_144,
            maxDeltaChars = 16_384,
            maxStreamEvents = 4_096,
            maxProviderDurationMillis = 30_000,
        )
        fun streamInput(index: Int) = input().copy(
            runId = "stream-run-$index",
            idempotencyKey = "stream-key-$index",
            conversationId = "stream-conversation-$index",
            userMessageId = "stream-user-$index",
            assistantMessageId = "stream-assistant-$index",
        )
        val exact = engine(
            adapter = FakeAdapter { _, emit ->
                repeat(4_094) { emit(ProviderStreamEvent.Delta("x".repeat(64))) }
                emit(ProviderStreamEvent.Delta("y".repeat(128)))
                emit(ProviderStreamEvent.Completed("exact-boundary"))
            },
            limits = limits,
        ).execute(streamInput(0))
        assertTrue(exact is ConversationEngineResult.Succeeded)
        assertEquals(262_144, database.conversationTimelineDao().message("stream-assistant-0")!!.content.length)

        val extraCharacter = engine(
            adapter = FakeAdapter { _, emit ->
                repeat(4_094) { emit(ProviderStreamEvent.Delta("x".repeat(64))) }
                emit(ProviderStreamEvent.Delta("y".repeat(129)))
            },
            limits = limits,
        ).execute(streamInput(1)) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.OUTPUT_LIMIT, extraCharacter.code)
        assertEquals("", database.conversationTimelineDao().message("stream-assistant-1")!!.content)

        val extraEvent = engine(
            adapter = FakeAdapter { _, emit ->
                repeat(4_096) { emit(ProviderStreamEvent.Delta("z")) }
                emit(ProviderStreamEvent.Completed("one-event-too-many"))
            },
            limits = limits,
        ).execute(streamInput(2)) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.OUTPUT_LIMIT, extraEvent.code)
        assertEquals("", database.conversationTimelineDao().message("stream-assistant-2")!!.content)
    }

    @Test
    fun recoveryInterruptsOneHundredTwentyEightAbandonedRunsWithoutReplayingFourLiveRuns() = runBlocking {
        val persistence = ConversationRunPersistence(database, ephemeral)
        val liveTokens = (0 until 4).map { "pressure-live-owner-$it" }
        liveTokens.forEach(ConversationEngineOwnerRegistry::register)
        try {
            repeat(132) { index ->
                createConversationAndUser("recovery-conversation-$index", "recovery-user-$index")
                val owner = if (index < 128) "pressure-abandoned-owner-$index" else liveTokens[index - 128]
                val reservation = persistence.reserve(
                    input().copy(
                        runId = "recovery-run-${index.toString().padStart(3, '0')}",
                        idempotencyKey = "recovery-key-$index",
                        conversationId = "recovery-conversation-$index",
                        userMessageId = "recovery-user-$index",
                        assistantMessageId = "recovery-assistant-$index",
                    ),
                    owner,
                )
                assertTrue(reservation is ReserveConversationRunResult.Reserved)
            }
            val adapter = FakeAdapter { _, _ -> error("recovery must not replay provider work") }
            val recovery = engine(adapter)
            val first = recovery.recoverInterruptedRuns()
            assertEquals(128, first.interruptedRunIds.size)
            assertEquals("recovery-run-000", first.interruptedRunIds.first())
            assertEquals("recovery-run-127", first.interruptedRunIds.last())
            assertEquals(0, adapter.invocationCount.get())
            (128 until 132).forEach { index ->
                assertEquals(
                    ConversationRunState.PREPARING,
                    database.conversationRunDao().run("recovery-run-$index")!!.state,
                )
            }

            liveTokens.forEach(ConversationEngineOwnerRegistry::unregister)
            assertEquals(
                (128 until 132).map { "recovery-run-$it" },
                recovery.recoverInterruptedRuns().interruptedRunIds,
            )
            assertEquals(0, adapter.invocationCount.get())
        } finally {
            liveTokens.forEach(ConversationEngineOwnerRegistry::unregister)
        }
    }

    @Test
    fun cancellingQueuedRunDurablyClearsSlotAndAllowsRetry() = runBlocking {
        createConversationAndUser()
        createProfile()
        createConversationAndUser("conversation-2", "user-2")
        createProfile("profile-2")
        val limiter = ConversationRunLimiter(1)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            engine(
                adapter = FakeAdapter { _, emit ->
                    entered.complete(Unit)
                    release.await()
                    emit(ProviderStreamEvent.Delta("first"))
                    emit(ProviderStreamEvent.Completed())
                },
                limiter = limiter,
                limits = ConversationEngineLimits(concurrencyWaitMillis = 5_000),
            ).execute(input())
        }
        entered.await()
        val queuedAdapter = FakeAdapter { _, _ -> error("cancelled queued adapter must not run") }
        val queued = async {
            engine(
                adapter = queuedAdapter,
                limiter = limiter,
                limits = ConversationEngineLimits(concurrencyWaitMillis = 5_000),
            ).execute(
                input().copy(
                    runId = "run-queued",
                    idempotencyKey = "key-queued",
                    conversationId = "conversation-2",
                    userMessageId = "user-2",
                    assistantMessageId = "assistant-queued",
                    profileId = "profile-2",
                ),
            )
        }
        withTimeout(1_000) {
            while (database.conversationRunDao().run("run-queued") == null) yield()
        }
        assertEquals(ConversationRunState.PREPARING, database.conversationRunDao().run("run-queued")!!.state)
        queued.cancel()
        val cancellationPropagated = try {
            queued.await()
            false
        } catch (_: CancellationException) {
            true
        }

        assertTrue(cancellationPropagated)
        assertEquals(0, queuedAdapter.invocationCount.get())
        assertEquals(ConversationRunState.CANCELLED, database.conversationRunDao().run("run-queued")!!.state)
        assertNull(database.conversationRunDao().activeRun("conversation-2"))
        assertEquals(
            MessageDeliveryState.CANCELLED,
            database.conversationTimelineDao().message("assistant-queued")!!.deliveryState,
        )
        release.complete(Unit)
        assertTrue(first.await() is ConversationEngineResult.Succeeded)

        val retryRevision = database.conversationTimelineDao().timelineHead("conversation-2")!!.timelineRevision
        val retry = engine(
            adapter = FakeAdapter { _, emit ->
                emit(ProviderStreamEvent.Delta("retry succeeded"))
                emit(ProviderStreamEvent.Completed())
            },
            limiter = limiter,
        ).execute(
            input().copy(
                runId = "run-retry-after-queue-cancel",
                idempotencyKey = "key-retry-after-queue-cancel",
                conversationId = "conversation-2",
                userMessageId = "user-2",
                assistantMessageId = "assistant-retry-after-queue-cancel",
                profileId = "profile-2",
                trigger = ConversationRunTrigger.RETRY,
                retryOfRunId = "run-queued",
                expectedTimelineRevision = retryRevision,
            ),
        )
        assertTrue(retry is ConversationEngineResult.Succeeded)
    }

    @Test
    fun persistedCancellationWinsAgainstLateNonCancellableCompletion() = runBlocking {
        createConversationAndUser()
        createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                emit(ProviderStreamEvent.Delta("too late"))
                emit(ProviderStreamEvent.Completed("late-request"))
            }
        }
        val engine = engine(adapter)
        val execution = async { engine.execute(input()) }
        entered.await()

        val cancelled = engine.cancel("run-1")
        assertTrue(cancelled is ConversationRunControlResult.Updated)
        release.complete(Unit)
        runCatching { execution.await() }

        val run = database.conversationRunDao().run("run-1")!!
        assertEquals(ConversationRunState.CANCELLED, run.state)
        assertEquals(MessageDeliveryState.CANCELLED, database.conversationTimelineDao().message("assistant-1")!!.deliveryState)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun liveOwnerIsNotRecoveredButClosedOwnerIsInterruptedWithoutReplay() = runBlocking {
        createConversationAndUser()
        createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                emit(ProviderStreamEvent.Delta("late"))
                emit(ProviderStreamEvent.Completed())
            }
        }
        val owner = engine(adapter)
        val execution = async { owner.execute(input()) }
        entered.await()
        val recovery = engine(adapter)

        assertTrue(recovery.recoverInterruptedRuns().interruptedRunIds.isEmpty())
        owner.close()
        assertEquals(listOf("run-1"), recovery.recoverInterruptedRuns().interruptedRunIds)
        release.complete(Unit)
        runCatching { execution.await() }

        assertEquals(ConversationRunState.INTERRUPTED, database.conversationRunDao().run("run-1")!!.state)
        assertEquals(1, adapter.invocationCount.get())
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun regenerationRequestExcludesOldAnswerAndFailurePreservesSelectedHead() = runBlocking {
        createConversationAndUser()
        createProfile()
        val first = FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("old answer"))
            emit(ProviderStreamEvent.Completed())
        }
        val firstResult = engine(first).execute(input()) as ConversationEngineResult.Succeeded
        assertEquals("assistant-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        val regenerate = FakeAdapter { request, emit ->
            assertFalse(request.context.any { it.content.contains("old answer") })
            assertEquals("assistant-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
            emit(ProviderStreamEvent.Failure(ProviderFailureCode.UNAVAILABLE))
        }

        val result = engine(regenerate).execute(
            input().copy(
                runId = "run-regen",
                idempotencyKey = "key-regen",
                assistantMessageId = "assistant-regen",
                trigger = ConversationRunTrigger.REGENERATE,
                regenerateOfMessageId = "assistant-1",
                expectedTimelineRevision = firstResult.timelineRevision,
            ),
        ) as ConversationEngineResult.Failed

        assertEquals(ConversationEngineErrorCode.PROVIDER_FAILURE, result.code)
        assertEquals("assistant-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertEquals(MessageDeliveryState.FAILED, database.conversationTimelineDao().message("assistant-regen")!!.deliveryState)
    }

    @Test
    fun retryCreatesANewSiblingAttemptWithoutDuplicatingTheUserExperience() = runBlocking {
        createConversationAndUser()
        createProfile()
        val failed = engine(FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Failure(ProviderFailureCode.UNAVAILABLE))
        }).execute(input()) as ConversationEngineResult.Failed
        val retryRevision = database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.timelineRevision
        val retried = engine(FakeAdapter { request, emit ->
            assertFalse(request.context.any { it.content.contains("assistant-1") })
            emit(ProviderStreamEvent.Delta("recovered"))
            emit(ProviderStreamEvent.Completed("retry-request"))
        }).execute(
            input().copy(
                runId = "run-retry",
                idempotencyKey = "key-retry",
                assistantMessageId = "assistant-retry",
                trigger = ConversationRunTrigger.RETRY,
                retryOfRunId = failed.run!!.runId,
                expectedTimelineRevision = retryRevision,
            ),
        ) as ConversationEngineResult.Succeeded

        assertEquals(ConversationRunTrigger.RETRY, retried.run.trigger)
        assertEquals("run-1", retried.run.retryOfRunId)
        assertEquals("assistant-retry", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertEquals("user-1", database.conversationTimelineDao().parentEdge("assistant-1")!!.parentMessageId)
        assertEquals("user-1", database.conversationTimelineDao().parentEdge("assistant-retry")!!.parentMessageId)
        database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM experience_message_sources WHERE message_id = ?",
            arrayOf("user-1"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun profileMutationWhileProviderIsRunningRejectsBufferedOutputBeforeCommit() = runBlocking {
        createConversationAndUser()
        val profile = createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("must not persist"))
            emit(ProviderStreamEvent.Completed("stale-profile-request"))
        }
        val execution = async { engine(adapter).execute(input()) }
        entered.await()
        assertTrue(
            profileService.update(
                UpdateProviderProfileInput(
                    profileId = profile.profileId,
                    expectedRevision = profile.revision,
                    displayName = profile.displayName,
                    adapterId = profile.adapterId,
                    endpointBaseUrl = profile.endpointBaseUrl,
                    modelId = "model-after-dispatch",
                    credentialSlotId = profile.credentialSlotId,
                    isEnabled = true,
                    capabilities = profile.capabilities.toList(),
                    occurredAt = 200,
                ),
            ) is UpdateProviderProfileResult.Success,
        )
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
    }

    @Test
    fun rewindWhileProviderIsRunningMakesTheResultStaleAndPreservesTheRewoundHead() = runBlocking {
        createConversationAndUser()
        createProfile()
        assertTrue(
            timeline.appendMessage(
                AppendTimelineMessageInput(
                    conversationId = CONVERSATION_ID,
                    message = NewTimelineMessageInput(
                        messageId = "user-2",
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = "A follow-up",
                        createdAt = 3,
                        updatedAt = 3,
                    ),
                    expectedTimelineRevision = 1,
                    occurredAt = 3,
                ),
            ) is TimelineWriteResult.MessageAppended,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("late result"))
            emit(ProviderStreamEvent.Completed())
        }
        val execution = async {
            engine(adapter).execute(
                input().copy(
                    userMessageId = "user-2",
                    expectedTimelineRevision = 2,
                ),
            )
        }
        entered.await()
        assertTrue(
            timeline.rewindTo(
                RewindTimelineInput(
                    conversationId = CONVERSATION_ID,
                    targetMessageId = "user-1",
                    expectedTimelineRevision = 3,
                    occurredAt = 20,
                ),
            ) is TimelineWriteResult.Rewound,
        )
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
    }

    @Test
    fun deletingThePendingAssistantWhileProviderRunsMakesLateCallbacksHarmless() = runBlocking {
        createConversationAndUser()
        createProfile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("orphaned callback"))
            emit(ProviderStreamEvent.Completed())
        }
        val execution = async { engine(adapter).execute(input()) }
        entered.await()
        val deleted = SafeDeleteService(database).deleteLeafMessage(
            DeleteTimelineMessageInput(
                conversationId = CONVERSATION_ID,
                messageId = "assistant-1",
                expectedTimelineRevision = 2,
                occurredAt = 20,
            ),
        )
        assertTrue(deleted is TimelineDeleteResult.Deleted)
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CANCELLED, result.code)
        assertNull(database.conversationRunDao().run("run-1"))
        assertNull(database.conversationTimelineDao().message("assistant-1"))
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
    }

    @Test
    fun recallMutationWhileProviderIsRunningInvalidatesTheReadReceipt() = runBlocking {
        createConversationAndUser()
        createProfile()
        val fence = database.validationRecallCorpusFence()
        val recallSource = object : RivenContextSource {
            override val descriptor = HoldingSource.DESCRIPTOR.copy(
                sourceId = "TEST_RECALL",
                layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
                provenanceClass = RivenContextProvenanceClass.RETRIEVED_MEMORY,
                orderWithinLayer = 100,
            )

            override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
                val generation = fence.snapshot()
                return RivenContextSourceResult.Success(
                    payloads = listOf(RivenContextPayload("memory", "remembered", observedAt = request.now)),
                    freshnessReceipts = setOf(
                        RivenContextFreshnessReceipt.ConversationalRecall(
                            databaseSessionId = generation.databaseSessionId,
                            algorithmVersion = generation.algorithmVersion,
                            corpusGeneration = generation.corpusGeneration,
                        ),
                    ),
                )
            }
        }
        val receiptValidator = ConversationalRecallReceiptValidator { receipt ->
            fence.matches(
                ValidationRecallGeneration(
                    databaseSessionId = receipt.databaseSessionId,
                    corpusGeneration = receipt.corpusGeneration,
                    algorithmVersion = receipt.algorithmVersion,
                ),
            )
        }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            entered.complete(Unit)
            release.await()
            emit(ProviderStreamEvent.Delta("stale memory answer"))
            emit(ProviderStreamEvent.Completed())
        }
        val execution = async {
            engine(adapter, listOf(recallSource), receiptValidator).execute(input())
        }
        entered.await()
        fence.withCanonicalMutation(change = { ValidationRecallCorpusChange.Unknown }) { Unit }
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
    }

    @Test
    fun canonicalProviderExperienceCandidateValidationMemoryRecallForgetAndDeleteChain() = runBlocking {
        createConversationAndUser()
        createProfile()
        val providerResult = engine(FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("Forget source. Delete source."))
            emit(ProviderStreamEvent.Completed("chain-source-request"))
        }).execute(input())
        assertTrue(providerResult is ConversationEngineResult.Succeeded)
        val experience = (
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") as
                ConversationExperienceLookupResult.Found
            ).experience
        database.experienceAttentionDao().insertAssessment(
            ExperienceAttentionAssessmentEntity(
                experienceId = experience.id,
                outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                revision = 1,
                createdAt = 200,
                updatedAt = 200,
            ),
        )
        database.experienceAttentionDao().insertSignal(
            ExperienceAttentionSignalEntity(
                experienceId = experience.id,
                signal = AttentionSignal.PREFERENCE,
                polarity = AttentionSignalPolarity.POSITIVE,
                createdAt = 200,
            ),
        )
        val candidateCounter = AtomicInteger(0)
        val extraction = CandidateExtractionService(
            database = database,
            extractor = CandidateMemoryExtractor { snapshot ->
                assertEquals(experience.id, snapshot.experienceId)
                val sourceClaims = candidateSourceClaims(snapshot.sourceContent)
                CandidateExtractionProposal(
                    listOf(
                        CandidateMemoryProposal(
                            proposedMeaning = "Hello Riven chain forget marker",
                            proposedKind = MemoryKind.SEMANTIC,
                            proposedScope = MemoryScope.SHAI,
                            proposedEpistemicBasis = EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                            proposedCertainty = MemoryCertainty.CERTAIN,
                            proposedState = CandidateMemoryState.READY_FOR_VALIDATION,
                            proposedSensitivity = SensitivityLevel.STANDARD,
                            sourceClaimId = sourceClaims[0].id,
                        ),
                        CandidateMemoryProposal(
                            proposedMeaning = "Hello Riven chain delete marker",
                            proposedKind = MemoryKind.SEMANTIC,
                            proposedScope = MemoryScope.SHAI,
                            proposedEpistemicBasis = EpistemicBasis.DIRECT_RIVEN_EXPERIENCE,
                            proposedCertainty = MemoryCertainty.CERTAIN,
                            proposedState = CandidateMemoryState.READY_FOR_VALIDATION,
                            proposedSensitivity = SensitivityLevel.STANDARD,
                            sourceClaimId = sourceClaims[1].id,
                        ),
                    ),
                )
            },
            candidateIdGenerator = CandidateIdGenerator { "chain-candidate-${candidateCounter.incrementAndGet()}" },
        ).extract(ExtractCandidateMemoriesInput(experience.id, extractedAt = 210)) as CandidateExtractionResult.Extracted
        assertEquals(2, extraction.createdCandidateIds.size)

        val memoryCounter = AtomicInteger(0)
        val validation = CandidateValidationService(
            database = database,
            retriever = ValidationMemoryRetriever {
                ValidationMemoryRetrieval(
                    readiness = ValidationRecallReadiness.READY,
                    generation = database.validationRecallCorpusFence().snapshot(),
                )
            },
            decider = CandidateValidationDecider {
                CandidateValidationDecision(
                    outcome = CandidateValidationOutcome.ACCEPT_NEW,
                    admission = ValidationAdmissionMetadata(TemporalState.CURRENT),
                )
            },
            memoryIdGenerator = ValidatedMemoryIdGenerator { "chain-memory-${memoryCounter.incrementAndGet()}" },
        )
        try {
            extraction.createdCandidateIds.forEachIndexed { index, candidateId ->
                val result = validation.validate(ValidateCandidateInput(candidateId, validatedAt = 220L + index))
                assertTrue(result is CandidateValidationResult.AcceptedNew)
            }
        } finally {
            validation.close()
        }

        val recall = TargetedConversationalMemoryRetriever(database)
        try {
            val initiallyRecalled = recall.retrieve(ConversationalMemoryQuery("Hello Riven chain", now = 230))
            assertEquals(setOf("chain-memory-1", "chain-memory-2"), initiallyRecalled.memories.map { it.memoryId }.toSet())
            val recallSource = ConversationalMemoryContextSource(recall)

            createConversationAndUser("chain-forget-conversation", "chain-forget-user")
            val forgetEntered = CompletableDeferred<Unit>()
            val releaseForget = CompletableDeferred<Unit>()
            val forgetAdapter = FakeAdapter { request, emit ->
                assertTrue(request.context.any { it.content.contains("chain forget marker") })
                forgetEntered.complete(Unit)
                releaseForget.await()
                emit(ProviderStreamEvent.Delta("must not persist after forget"))
                emit(ProviderStreamEvent.Completed("forget-race"))
            }
            val forgetExecution = async {
                engine(
                    adapter = forgetAdapter,
                    extraSources = listOf(recallSource),
                    recallValidator = recall,
                ).execute(
                    input().copy(
                        runId = "chain-forget-run",
                        idempotencyKey = "chain-forget-key",
                        conversationId = "chain-forget-conversation",
                        userMessageId = "chain-forget-user",
                        assistantMessageId = "chain-forget-assistant",
                    ),
                )
            }
            withTimeout(5_000) { forgetEntered.await() }
            assertTrue(
                MemoryTransactionService(database).forget(
                    MemoryStateTransitionInput("chain-memory-1", occurredAt = 240, triggeringExperienceId = experience.id),
                ) is MemoryWriteResult.Success,
            )
            releaseForget.complete(Unit)
            val forgotten = forgetExecution.await() as ConversationEngineResult.Failed
            assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, forgotten.code)
            assertEquals("", database.conversationTimelineDao().message("chain-forget-assistant")!!.content)
            assertTrue(
                ConversationExperienceService(database).conversationExperienceForMessage("chain-forget-assistant") is
                    ConversationExperienceLookupResult.NotRecorded,
            )
            assertEquals(
                listOf("chain-memory-2"),
                recall.retrieve(ConversationalMemoryQuery("Hello Riven chain", now = 250)).memories.map { it.memoryId },
            )

            createConversationAndUser("chain-delete-conversation", "chain-delete-user")
            val deleteEntered = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<Unit>()
            val deleteAdapter = FakeAdapter { request, emit ->
                assertTrue(request.context.any { it.content.contains("chain delete marker") })
                deleteEntered.complete(Unit)
                releaseDelete.await()
                emit(ProviderStreamEvent.Delta("must not persist after delete"))
                emit(ProviderStreamEvent.Completed("delete-race"))
            }
            val deleteExecution = async {
                engine(
                    adapter = deleteAdapter,
                    extraSources = listOf(recallSource),
                    recallValidator = recall,
                ).execute(
                    input().copy(
                        runId = "chain-delete-run",
                        idempotencyKey = "chain-delete-key",
                        conversationId = "chain-delete-conversation",
                        userMessageId = "chain-delete-user",
                        assistantMessageId = "chain-delete-assistant",
                    ),
                )
            }
            withTimeout(5_000) { deleteEntered.await() }
            assertTrue(
                SafeDeleteService(database).deleteMemory(
                    DeleteMemoryInput(memoryId = "chain-memory-2", occurredAt = 260),
                ) is MemoryDeleteResult.Deleted,
            )
            releaseDelete.complete(Unit)
            val deleted = deleteExecution.await() as ConversationEngineResult.Failed
            assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, deleted.code)
            assertEquals("", database.conversationTimelineDao().message("chain-delete-assistant")!!.content)
            assertTrue(
                ConversationExperienceService(database).conversationExperienceForMessage("chain-delete-assistant") is
                    ConversationExperienceLookupResult.NotRecorded,
            )
            assertTrue(
                recall.retrieve(ConversationalMemoryQuery("Hello Riven chain", now = 270)).memories.isEmpty(),
            )
        } finally {
            recall.close()
        }
    }

    @Test
    fun failedSuccessTransactionRollsBackMessageHeadExperienceAndRunTogether() = runBlocking {
        createConversationAndUser()
        createProfile()
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_succeeded_assistant
            BEFORE UPDATE ON messages
            WHEN NEW.delivery_state = 'SUCCEEDED'
            BEGIN
                SELECT RAISE(ABORT, 'forced success rollback');
            END
            """.trimIndent(),
        )
        val result = engine(FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("rolled back"))
            emit(ProviderStreamEvent.Completed())
        }).execute(input()) as ConversationEngineResult.Failed

        assertEquals(ConversationEngineErrorCode.STORAGE_FAILURE, result.code)
        assertEquals(MessageDeliveryState.FAILED, database.conversationTimelineDao().message("assistant-1")!!.deliveryState)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertEquals(ConversationRunState.FAILED, database.conversationRunDao().run("run-1")!!.state)
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun outputAndProviderDurationBoundsFailClosed() = runBlocking {
        createConversationAndUser()
        createProfile()
        val output = engine(
            adapter = FakeAdapter { _, emit -> emit(ProviderStreamEvent.Delta("toolong")) },
            limits = ConversationEngineLimits(maxOutputChars = 4, maxDeltaChars = 4),
        ).execute(input()) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.OUTPUT_LIMIT, output.code)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)

        createConversationAndUser("conversation-2", "user-2")
        createProfile("profile-2")
        val timeout = engine(
            adapter = FakeAdapter { _, _ -> delay(1_000) },
            limits = ConversationEngineLimits(maxProviderDurationMillis = 10),
        ).execute(
            input().copy(
                runId = "run-timeout",
                idempotencyKey = "key-timeout",
                conversationId = "conversation-2",
                userMessageId = "user-2",
                assistantMessageId = "assistant-timeout",
                profileId = "profile-2",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.PROVIDER_TIMEOUT, timeout.code)
        assertEquals("", database.conversationTimelineDao().message("assistant-timeout")!!.content)
    }

    @Test
    fun profileInstructionAndEphemeralChangesDuringPreparationBlockDispatch() = runBlocking {
        createConversationAndUser()
        val profile = createProfile()
        instructions.save(SaveShaiSystemInstructionsInput("Be precise", true, 0, 10))
        assertTrue(
            ephemeral.publish(
                PublishEphemeralAppStateInput(
                    stateId = "timer",
                    content = "running",
                    exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                    priority = 1,
                    expectedRevision = 0,
                    observedAt = 10,
                    validUntil = 1_000,
                ),
            ) is EphemeralAppStateWriteResult.Published,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = HoldingSource(entered, release)
        val adapter = FakeAdapter { _, _ -> error("must not be invoked") }
        val engine = engine(adapter, extraSources = listOf(blocker))
        val execution = async { engine.execute(input()) }
        entered.await()

        val updated = profileService.update(
            UpdateProviderProfileInput(
                profileId = profile.profileId,
                expectedRevision = profile.revision,
                displayName = profile.displayName,
                adapterId = profile.adapterId,
                endpointBaseUrl = profile.endpointBaseUrl,
                modelId = "model-b",
                credentialSlotId = profile.credentialSlotId,
                isEnabled = true,
                capabilities = profile.capabilities.toList(),
                occurredAt = 20,
            ),
        )
        assertTrue(updated is UpdateProviderProfileResult.Success)
        instructions.save(SaveShaiSystemInstructionsInput("Changed", true, 1, 20))
        ephemeral.publish(
            PublishEphemeralAppStateInput(
                stateId = "timer",
                content = "changed",
                exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                priority = 1,
                expectedRevision = 1,
                observedAt = 20,
                validUntil = 1_000,
            ),
        )
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals(0, adapter.invocationCount.get())
    }

    @Test
    fun rewindAndInstructionEditDuringQueuedProfileReadBlockDispatch() = runBlocking {
        createConversationAndUser()
        createProfile()
        assertTrue(
            instructions.save(SaveShaiSystemInstructionsInput("Initial", true, 0, 10)) is
                ShaiSystemInstructionsWriteResult.Saved,
        )
        assertTrue(
            timeline.appendMessage(
                AppendTimelineMessageInput(
                    conversationId = CONVERSATION_ID,
                    message = NewTimelineMessageInput(
                        messageId = "user-2",
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = "Second turn",
                        createdAt = 3,
                        updatedAt = 3,
                    ),
                    expectedTimelineRevision = 1,
                    occurredAt = 3,
                ),
            ) is TimelineWriteResult.MessageAppended,
        )
        val profileReadEntered = CompletableDeferred<Unit>()
        val releaseProfileRead = CompletableDeferred<Unit>()
        val heldProfileRead = ProviderProfileReceiptValidator {
            profileReadEntered.complete(Unit)
            releaseProfileRead.await()
            true
        }
        val adapter = FakeAdapter { _, _ -> error("stale context must not be dispatched") }
        val execution = async {
            engine(
                adapter = adapter,
                profileReceiptValidator = heldProfileRead,
            ).execute(
                input().copy(
                    userMessageId = "user-2",
                    expectedTimelineRevision = 2,
                ),
            )
        }
        profileReadEntered.await()
        assertTrue(
            timeline.rewindTo(
                RewindTimelineInput(
                    conversationId = CONVERSATION_ID,
                    targetMessageId = "user-1",
                    expectedTimelineRevision = 3,
                    occurredAt = 20,
                ),
            ) is TimelineWriteResult.Rewound,
        )
        assertTrue(
            instructions.save(SaveShaiSystemInstructionsInput("Edited", true, 1, 20)) is
                ShaiSystemInstructionsWriteResult.Saved,
        )
        releaseProfileRead.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals(0, adapter.invocationCount.get())
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
    }

    @Test
    fun ephemeralExpiryDuringQueuedProfileReadBlocksDispatchWithoutStoreMutation() = runBlocking {
        createConversationAndUser()
        createProfile()
        ephemeral.publish(
            PublishEphemeralAppStateInput(
                stateId = "short",
                content = "temporary",
                exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                priority = 1,
                expectedRevision = 0,
                observedAt = 100,
                validUntil = 150,
            ),
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val heldProfileRead = ProviderProfileReceiptValidator {
            entered.complete(Unit)
            release.await()
            true
        }
        val adapter = FakeAdapter { _, _ -> error("must not be invoked") }
        val execution = async {
            engine(
                adapter = adapter,
                profileReceiptValidator = heldProfileRead,
            ).execute(input())
        }
        entered.await()
        clock.set(151)
        release.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals(0, adapter.invocationCount.get())
    }

    @Test
    fun ephemeralExpiryWhileFinalRoomTransactionIsQueuedRejectsBufferedOutput() = runBlocking {
        createConversationAndUser()
        createProfile()
        assertTrue(
            ephemeral.publish(
                PublishEphemeralAppStateInput(
                    stateId = "commit-window",
                    content = "temporary",
                    exposure = EphemeralAppStateExposure.RIVEN_CONTEXT,
                    priority = 1,
                    expectedRevision = 0,
                    observedAt = 100,
                    validUntil = 1_000,
                ),
            ) is EphemeralAppStateWriteResult.Published,
        )
        val transactionQueued = CompletableDeferred<Unit>()
        val releaseTransaction = CompletableDeferred<Unit>()
        val adapter = FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("must not commit"))
            emit(ProviderStreamEvent.Completed("expired-before-commit"))
        }
        val execution = async {
            engine(
                adapter = adapter,
                beforeFinalRoomTransaction = {
                    transactionQueued.complete(Unit)
                    releaseTransaction.await()
                },
            ).execute(input())
        }
        transactionQueued.await()
        clock.set(1_001)
        releaseTransaction.complete(Unit)

        val result = execution.await() as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_STALE, result.code)
        assertEquals(1, adapter.invocationCount.get())
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
        assertEquals("user-1", database.conversationTimelineDao().timelineHead(CONVERSATION_ID)!!.activeHeadMessageId)
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun malformedAndUnboundedStreamsFailClosedWithoutPartialPersistence() = runBlocking {
        createConversationAndUser()
        createProfile()
        val adapter = FakeAdapter { _, emit ->
            emit(ProviderStreamEvent.Delta("ok"))
            emit(ProviderStreamEvent.Completed())
            emit(ProviderStreamEvent.Completed())
        }
        val result = engine(adapter).execute(input()) as ConversationEngineResult.Failed

        assertEquals(ConversationEngineErrorCode.PROVIDER_PROTOCOL, result.code)
        assertEquals("", database.conversationTimelineDao().message("assistant-1")!!.content)
        assertTrue(
            ConversationExperienceService(database).conversationExperienceForMessage("assistant-1") is
                ConversationExperienceLookupResult.NotRecorded,
        )
    }

    @Test
    fun profileAdapterSystemAndAttachmentCapabilitiesFailBeforeProviderInvocation() = runBlocking {
        createConversationAndUser()
        createProfile(capabilities = listOf(ProviderCapability.TEXT_CHAT))
        val never = FakeAdapter { _, _ -> error("must not be invoked") }
        val profileCapability = engine(never).execute(input()) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.PROFILE_CAPABILITY_MISSING, profileCapability.code)
        assertEquals(0, never.invocationCount.get())

        createConversationAndUser("conversation-2", "user-2")
        createProfile("profile-2")
        val limitedAdapter = FakeAdapter(
            descriptor = ProviderAdapterDescriptor(
                adapterId = "fake.adapter",
                capabilities = setOf(ProviderCapability.TEXT_CHAT),
                systemContextMode = ProviderSystemContextMode.NATIVE_INSTRUCTIONS,
            ),
        ) { _, _ -> error("must not be invoked") }
        val adapterCapability = engine(limitedAdapter).execute(
            input().copy(
                runId = "run-2",
                idempotencyKey = "key-2",
                conversationId = "conversation-2",
                userMessageId = "user-2",
                assistantMessageId = "assistant-2",
                profileId = "profile-2",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.ADAPTER_CAPABILITY_MISSING, adapterCapability.code)
        assertEquals(0, limitedAdapter.invocationCount.get())

        createConversationAndUser("conversation-3", "user-3")
        createProfile("profile-3")
        instructions.save(SaveShaiSystemInstructionsInput("Never invent facts", true, 0, 10))
        val noSystem = FakeAdapter(
            descriptor = ProviderAdapterDescriptor(
                adapterId = "fake.adapter",
                capabilities = setOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
                systemContextMode = ProviderSystemContextMode.UNSUPPORTED,
            ),
        ) { _, _ -> error("must not be invoked") }
        val systemCapability = engine(noSystem).execute(
            input().copy(
                runId = "run-3",
                idempotencyKey = "key-3",
                conversationId = "conversation-3",
                userMessageId = "user-3",
                assistantMessageId = "assistant-3",
                profileId = "profile-3",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.SYSTEM_CONTEXT_UNSUPPORTED, systemCapability.code)
        assertEquals(0, noSystem.invocationCount.get())

        createConversationAndUser("conversation-4", "user-4")
        createProfile("profile-4")
        database.attachmentDao().insertAttachment(
            AttachmentEntity(
                id = "attachment-1",
                kind = AttachmentKind.IMAGE,
                mimeType = "image/png",
                state = AttachmentState.AVAILABLE,
                storageKey = "attachments/attachment-1.blob",
                byteSize = 1,
                contentSha256 = "0".repeat(64),
                source = AttachmentSource.SHAI_IMPORT,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.attachmentDao().insertMessageAttachment(
            MessageAttachmentEntity("user-4", "attachment-1", 0, 1),
        )
        val attachmentCapability = engine(never).execute(
            input().copy(
                runId = "run-4",
                idempotencyKey = "key-4",
                conversationId = "conversation-4",
                userMessageId = "user-4",
                assistantMessageId = "assistant-4",
                profileId = "profile-4",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.ATTACHMENTS_UNSUPPORTED, attachmentCapability.code)
        assertEquals(0, never.invocationCount.get())

        createConversationAndUser("conversation-5", "user-5")
        createProfile("profile-5", credentialSlotId = "missing-slot")
        val missingCredentialStore = object : ProviderCredentialStore by EmptyCredentialStore {
            override fun readCredential(credentialSlotId: String) = ReadProviderCredentialResult.Failure(
                ProviderCredentialError.MissingCredential(credentialSlotId),
            )
        }
        val credentialCapability = engine(
            adapter = never,
            credentialStore = missingCredentialStore,
        ).execute(
            input().copy(
                runId = "run-5",
                idempotencyKey = "key-5",
                conversationId = "conversation-5",
                userMessageId = "user-5",
                assistantMessageId = "assistant-5",
                profileId = "profile-5",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CREDENTIAL_MISSING, credentialCapability.code)
        assertEquals(0, never.invocationCount.get())
    }

    @Test
    fun missingAdapterAndRequiredContextFailureNeverInvokeProvider() = runBlocking {
        createConversationAndUser()
        createProfile(adapterId = "missing.adapter")
        val adapter = FakeAdapter { _, _ -> error("must not be invoked") }
        val missing = engine(adapter).execute(input()) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.ADAPTER_MISSING, missing.code)
        assertEquals(0, adapter.invocationCount.get())

        // A fresh conversation isolates the required-context failure after the first terminal run.
        createConversationAndUser("conversation-2", "user-2")
        createProfile(profileId = "profile-2")
        val failing = object : RivenContextSource {
            override val descriptor = HoldingSource.DESCRIPTOR.copy(
                sourceId = "REQUIRED_FAILURE",
                criticality = RivenContextSourceCriticality.REQUIRED,
            )
            override suspend fun read(request: RivenContextReadRequest) = RivenContextSourceResult.Failure(
                RivenContextSourceError.ReadFailure("ExpectedFailure"),
            )
        }
        val contextFailure = engine(adapter, listOf(failing)).execute(
            input().copy(
                runId = "run-2",
                idempotencyKey = "key-2",
                conversationId = "conversation-2",
                userMessageId = "user-2",
                assistantMessageId = "assistant-2",
                profileId = "profile-2",
            ),
        ) as ConversationEngineResult.Failed
        assertEquals(ConversationEngineErrorCode.CONTEXT_ASSEMBLY_FAILED, contextFailure.code)
        assertEquals(0, adapter.invocationCount.get())
    }

    private fun engine(
        adapter: ConversationProviderAdapter,
        extraSources: List<RivenContextSource> = emptyList(),
        recallValidator: ConversationalRecallReceiptValidator =
            ConversationalRecallReceiptValidator { true },
        credentialStore: ProviderCredentialStore = EmptyCredentialStore,
        limits: ConversationEngineLimits = ConversationEngineLimits(
            maxOutputChars = 64,
            maxDeltaChars = 32,
            maxStreamEvents = 8,
            maxProviderDurationMillis = 5_000,
            concurrencyWaitMillis = 1_000,
        ),
        limiter: ConversationRunLimiter = ConversationRunLimiterPool.forMaximum(limits.maxConcurrentRuns),
        profileReceiptValidator: ProviderProfileReceiptValidator? = null,
        beforeFinalRoomTransaction: suspend () -> Unit = {},
    ): ProviderNeutralConversationEngine {
        val registry = RivenContextSourceRegistry(
            listOf(
                ShaiSystemInstructionsContextSource(instructions),
                EphemeralAppStateContextSource(ephemeral),
                ActiveConversationContextSource(timeline),
            ) + extraSources,
        )
        return ProviderNeutralConversationEngine(
            database = database,
            contextAssembler = ConversationalContextAssembler(registry),
            recallValidator = recallValidator,
            profileService = profileService,
            profileResolver = ProviderRuntimeProfileResolver(profileService, credentialStore),
            instructionsService = instructions,
            ephemeralStateStore = ephemeral,
            adapterRegistry = ProviderAdapterRegistry(listOf(adapter)),
            limits = limits,
            clock = clock::incrementAndGet,
            limiter = limiter,
            profileReceiptValidator = profileReceiptValidator,
            beforeFinalRoomTransaction = beforeFinalRoomTransaction,
        ).also(engines::add)
    }

    private suspend fun createConversationAndUser(
        conversationId: String = CONVERSATION_ID,
        userMessageId: String = "user-1",
    ) {
        assertTrue(
            timeline.createConversationWithTimeline(
                CreateTimelineConversationInput(
                    conversationId = conversationId,
                    createdAt = 1,
                    updatedAt = 1,
                    status = ConversationStatus.ACTIVE,
                ),
            ) is TimelineWriteResult.ConversationCreated,
        )
        assertTrue(
            timeline.appendMessage(
                AppendTimelineMessageInput(
                    conversationId = conversationId,
                    message = NewTimelineMessageInput(
                        messageId = userMessageId,
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = "Hello Riven",
                        createdAt = 2,
                        updatedAt = 2,
                    ),
                    expectedTimelineRevision = 0,
                    occurredAt = 2,
                ),
            ) is TimelineWriteResult.MessageAppended,
        )
    }

    private suspend fun createProfile(
        profileId: String = PROFILE_ID,
        adapterId: String = "fake.adapter",
        credentialSlotId: String? = null,
        capabilities: List<ProviderCapability> =
            listOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
    ) = (profileService.create(
        CreateProviderProfileInput(
            profileId = profileId,
            displayName = "Fake",
            adapterId = adapterId,
            endpointBaseUrl = "https://example.invalid",
            modelId = "model-a",
            credentialSlotId = credentialSlotId,
            capabilities = capabilities,
            occurredAt = 1,
        ),
    ) as CreateProviderProfileResult.Success).profile

    private fun input() = StartConversationRunInput(
        runId = "run-1",
        idempotencyKey = "key-1",
        conversationId = CONVERSATION_ID,
        userMessageId = "user-1",
        assistantMessageId = "assistant-1",
        profileId = PROFILE_ID,
        expectedTimelineRevision = 1,
        occurredAt = 3,
    )

    private class FakeAdapter(
        override val descriptor: ProviderAdapterDescriptor = ProviderAdapterDescriptor(
            adapterId = "fake.adapter",
            capabilities = setOf(ProviderCapability.TEXT_CHAT, ProviderCapability.STREAMING),
            systemContextMode = ProviderSystemContextMode.NATIVE_INSTRUCTIONS,
        ),
        private val script: suspend (
            ProviderConversationRequest,
            suspend (ProviderStreamEvent) -> Unit,
        ) -> Unit,
    ) : ConversationProviderAdapter {
        val invocationCount = AtomicInteger()

        override suspend fun stream(
            request: ProviderConversationRequest,
            emit: suspend (ProviderStreamEvent) -> Unit,
        ) {
            invocationCount.incrementAndGet()
            script(request, emit)
        }
    }

    private class HoldingSource(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : RivenContextSource {
        override val descriptor = DESCRIPTOR

        override suspend fun read(request: RivenContextReadRequest): RivenContextSourceResult {
            entered.complete(Unit)
            release.await()
            return RivenContextSourceResult.Success(
                listOf(RivenContextPayload("hold", "held", observedAt = request.now)),
            )
        }

        companion object {
            val DESCRIPTOR = RivenContextSourceDescriptor(
                sourceId = "HOLDING_SOURCE",
                layer = RivenContextLayer.ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
                provenanceClass = RivenContextProvenanceClass.OTHER_GROUNDED,
                criticality = RivenContextSourceCriticality.OPTIONAL,
                orderWithinLayer = 10,
                maxFragments = 1,
                maxCharsPerFragment = 32,
                maxAggregateChars = 32,
                budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
                contentAuthority = RivenContextContentAuthority.UNTRUSTED_DATA,
            )
        }
    }

    private object EmptyCredentialStore : ProviderCredentialStore {
        override fun putCredential(credentialSlotId: String, secret: ProviderSecret) =
            PutProviderCredentialResult.Success
        override fun readCredential(credentialSlotId: String) = error("Credential read was unexpected")
        override fun hasCredential(credentialSlotId: String) = HasProviderCredentialResult.Success(false)
        override fun deleteCredential(credentialSlotId: String) = DeleteProviderCredentialResult.Success
        override fun clearAllCredentials() = ClearProviderCredentialsResult.Success(0)
    }

    private companion object {
        const val CONVERSATION_ID = "conversation-1"
        const val PROFILE_ID = "profile-1"
    }
}
