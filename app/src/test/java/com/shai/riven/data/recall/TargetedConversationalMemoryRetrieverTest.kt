package com.shai.riven.data.recall

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.context.ActiveConversationReceiptValidator
import com.shai.riven.data.context.ConversationalContextAssembler
import com.shai.riven.data.context.ConversationalContextAssemblyInput
import com.shai.riven.data.context.ConversationalContextFreshnessValidator
import com.shai.riven.data.context.RivenContextBudgetBehavior
import com.shai.riven.data.context.RivenContextCollectionResult
import com.shai.riven.data.context.RivenContextContentAuthority
import com.shai.riven.data.context.RivenContextFreshnessReceipt
import com.shai.riven.data.context.RivenContextFreshnessValidation
import com.shai.riven.data.context.RivenContextLayer
import com.shai.riven.data.context.RivenContextPayload
import com.shai.riven.data.context.RivenContextProvenanceClass
import com.shai.riven.data.context.RivenContextReadRequest
import com.shai.riven.data.context.RivenContextSnapshot
import com.shai.riven.data.context.RivenContextSource
import com.shai.riven.data.context.RivenContextSourceCriticality
import com.shai.riven.data.context.RivenContextSourceDescriptor
import com.shai.riven.data.context.RivenContextSourceRegistry
import com.shai.riven.data.context.RivenContextSourceResult
import com.shai.riven.data.context.RivenConversationContextRequest
import com.shai.riven.data.context.RivenCurrentInteraction
import com.shai.riven.data.conversation.AppendTimelineMessageInput
import com.shai.riven.data.conversation.ConversationTimelineService
import com.shai.riven.data.conversation.CreateTimelineConversationInput
import com.shai.riven.data.conversation.NewTimelineMessageInput
import com.shai.riven.data.conversation.TimelineReadResult
import com.shai.riven.data.conversation.TimelineWriteResult
import com.shai.riven.data.context.RivenGroundedRecallCues
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.memory.MemoryStateTransitionInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.dao.ConversationalRecallMemoryRow
import com.shai.riven.data.persistence.dao.ValidationRecallEntityRow
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.validation.ValidationRecallCorpusChange
import com.shai.riven.data.validation.validationRecallCorpusFence
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetedConversationalMemoryRetrieverTest {
    private lateinit var database: RivenDatabase
    private val closeables = mutableListOf<AutoCloseable>()
    private var clock = 1L

    @Before
    fun setUp() {
        database = newDatabase()
    }

    @After
    fun tearDown() {
        closeables.reversed().forEach(AutoCloseable::close)
        database.close()
    }

    @Test
    fun ordinaryRecallReturnsSmallGroundedPackageWithProvenance() = runBlocking {
        insertMemory("rain", "Shai enjoys rainy afternoons.")

        val result = retriever().retrieve(query("Can we plan another rainy afternoon?"))

        assertReady(result)
        assertEquals(listOf("rain"), result.memories.map { it.memoryId })
        assertEquals(EpistemicBasis.DIRECT_USER_STATEMENT, result.memories.single().epistemicBasis)
        assertTrue(ConversationalSelectionReason.LEXICAL_RELEVANCE in result.memories.single().selectionReasons)
    }

    @Test
    fun correctedFalseAndForgottenNeverReturnEvenWithEveryCue() = runBlocking {
        insertMemory("false", "Shai lives in Atlantis.", truth = MemoryTruthState.CORRECTED_FALSE)
        insertMemory("forgotten", "Shai collected rare stamps.", retention = MemoryRetentionState.FORGOTTEN)
        val cues = RivenGroundedRecallCues(
            directlyRelevantMemoryIds = setOf("false", "forgotten"),
            historicalMemoryIds = setOf("false", "forgotten"),
            dormantMemoryIds = setOf("false", "forgotten"),
            disputedMemoryIds = setOf("false", "forgotten"),
            timeBoundMemoryIds = setOf("false", "forgotten"),
            sensitiveMemoryIds = setOf("false", "forgotten"),
            activeOpenLoopMemoryIds = setOf("false", "forgotten"),
        )

        val result = retriever().retrieve(query("Atlantis stamps", cues))

        assertReady(result)
        assertTrue(result.memories.isEmpty())
    }

    @Test
    fun restrictedLifecycleStatesRequireTheirExactStructuredCue() = runBlocking {
        insertMemory("dormant", "Shai used to practice archery.", retention = MemoryRetentionState.DORMANT)
        insertMemory("superseded", "Shai lived near the harbor.", lifecycle = MemoryLifecycleState.SUPERSEDED)
        insertMemory("resolved", "Shai finished the cedar project.", lifecycle = MemoryLifecycleState.RESOLVED)
        insertMemory("historical", "Shai attended the old academy.", temporal = TemporalState.HISTORICAL)
        insertMemory(
            "disputed",
            "Shai might prefer the north route.",
            truth = MemoryTruthState.DISPUTED,
            certainty = MemoryCertainty.DISPUTED,
        )
        insertMemory(
            "bounded",
            "Shai is visiting Oslo this week.",
            temporal = TemporalState.TIME_BOUNDED,
            validFrom = 10,
            validUntil = 20,
        )
        val recall = retriever()

        listOf(
            "dormant" to "archery",
            "superseded" to "harbor",
            "resolved" to "cedar project",
            "historical" to "old academy",
            "disputed" to "north route",
            "bounded" to "Oslo",
        ).forEach { (id, text) ->
            assertFalse(id in recall.retrieve(query(text, now = 15)).memories.map { it.memoryId })
        }

        assertEquals(listOf("dormant"), recall.retrieve(query("archery", RivenGroundedRecallCues(dormantMemoryIds = setOf("dormant")))).memories.map { it.memoryId })
        assertEquals(listOf("superseded"), recall.retrieve(query("harbor", RivenGroundedRecallCues(historicalMemoryIds = setOf("superseded")))).memories.map { it.memoryId })
        assertEquals(listOf("resolved"), recall.retrieve(query("cedar", RivenGroundedRecallCues(historicalMemoryIds = setOf("resolved")))).memories.map { it.memoryId })
        assertEquals(listOf("historical"), recall.retrieve(query("academy", RivenGroundedRecallCues(historicalMemoryIds = setOf("historical")))).memories.map { it.memoryId })
        assertEquals(listOf("disputed"), recall.retrieve(query("route", RivenGroundedRecallCues(disputedMemoryIds = setOf("disputed")))).memories.map { it.memoryId })
        assertEquals(listOf("bounded"), recall.retrieve(query("Oslo", RivenGroundedRecallCues(timeBoundMemoryIds = setOf("bounded")), now = 15)).memories.map { it.memoryId })
    }

    @Test
    fun expiredTimeBoundMemoryNeedsHistoricalCueNotMerelyTimeBoundCue() = runBlocking {
        insertMemory(
            "trip",
            "Shai is visiting Oslo this week.",
            temporal = TemporalState.TIME_BOUNDED,
            validFrom = 10,
            validUntil = 20,
        )
        val recall = retriever()

        val staleCurrent = recall.retrieve(
            query("Oslo", RivenGroundedRecallCues(timeBoundMemoryIds = setOf("trip")), now = 30),
        )
        val history = recall.retrieve(
            query("Oslo", RivenGroundedRecallCues(historicalMemoryIds = setOf("trip")), now = 30),
        )

        assertTrue(staleCurrent.memories.isEmpty())
        assertEquals(listOf("trip"), history.memories.map { it.memoryId })
    }

    @Test
    fun sensitiveMemoryIgnoresBroadSimilarityAndPersonEntityUntilDirectlyGrounded() = runBlocking {
        insertMemory("medical", "Private medical detail about Rowan.", sensitivity = SensitivityLevel.SENSITIVE)
        val recall = retriever()

        val ambiguous = recall.retrieve(
            query(
                "Tell me about Rowan and that medical detail.",
                RivenGroundedRecallCues(groundedEntityIds = setOf("rowan")),
            ),
        )
        val grounded = recall.retrieve(
            query(
                "Tell me about the medical detail.",
                RivenGroundedRecallCues(sensitiveMemoryIds = setOf("medical")),
            ),
        )

        assertTrue(ambiguous.memories.isEmpty())
        assertEquals(listOf("medical"), grounded.memories.map { it.memoryId })
    }

    @Test
    fun disputedUncertaintyIsPreservedInReturnedItem() = runBlocking {
        insertMemory(
            "route",
            "Shai might prefer the north route.",
            truth = MemoryTruthState.DISPUTED,
            certainty = MemoryCertainty.DISPUTED,
        )

        val item = retriever().retrieve(
            query("north route", RivenGroundedRecallCues(disputedMemoryIds = setOf("route"))),
        ).memories.single()

        assertEquals(MemoryTruthState.DISPUTED, item.truthState)
        assertEquals(MemoryCertainty.DISPUTED, item.certainty)
    }

    @Test
    fun correctionLineageAndNearDuplicatesDoNotCrowdPackage() = runBlocking {
        insertMemory("old", "Shai likes dark roast coffee in the morning.")
        insertMemory("new", "Shai likes dark roast coffee most mornings.")
        insertMemory("music", "Shai listens to ambient music while working.")
        insertRelationship("new", "old", MemoryRelationshipType.REFINES)

        val result = retriever().retrieve(query("What does Shai like in the morning while working?"))

        assertReady(result)
        assertTrue(result.memories.any { it.memoryId == "music" })
        assertTrue(result.memories.count { it.memoryId == "old" || it.memoryId == "new" } <= 1)
    }

    @Test
    fun emptyOrStopwordQueryIsUnselectiveRatherThanClaimingHistoryAbsent() = runBlocking {
        insertMemory("rain", "Shai enjoys rain.")
        val recall = retriever()

        assertEquals(ConversationalRecallReadiness.QUERY_UNSELECTIVE, recall.retrieve(query("the and is")).readiness)
    }

    @Test
    fun forgetAndDeleteRemoveWarmConversationalContent() = runBlocking {
        insertMemory("forget", "Shai enjoys cardamom tea.")
        insertMemory("delete", "Shai keeps a blue notebook.")
        val recall = retriever()
        assertEquals(listOf("forget"), recall.retrieve(query("cardamom tea")).memories.map { it.memoryId })
        assertEquals(listOf("delete"), recall.retrieve(query("blue notebook")).memories.map { it.memoryId })

        val forgotten = MemoryTransactionService(database).forget(MemoryStateTransitionInput("forget", 100))
        val deleted = SafeDeleteService(database).deleteMemory(DeleteMemoryInput("delete", 101))

        assertTrue(forgotten is MemoryWriteResult.Success)
        assertTrue(deleted is MemoryDeleteResult.Deleted)
        assertTrue(recall.retrieve(query("cardamom tea")).memories.isEmpty())
        assertTrue(recall.retrieve(query("blue notebook")).memories.isEmpty())
    }

    @Test
    fun capacityFailureIsTypedAndNeverReturnsPartialCorpus() = runBlocking {
        insertMemory("one", "topic one")
        insertMemory("two", "topic two")

        val result = retriever(ConversationalRecallLimits(maxMemories = 1)).retrieve(query("topic"))

        assertEquals(ConversationalRecallReadiness.CAPACITY_EXCEEDED, result.readiness)
        assertTrue(result.memories.isEmpty())
    }

    @Test
    fun excludedRowsStillConsumeTheBoundedCorpusReadBudget() = runBlocking {
        insertMemory("forgotten-a", "old topic a", retention = MemoryRetentionState.FORGOTTEN)
        insertMemory("forgotten-b", "old topic b", retention = MemoryRetentionState.FORGOTTEN)

        val result = retriever(ConversationalRecallLimits(pageSize = 1, maxMemories = 1))
            .retrieve(query("topic"))

        assertEquals(ConversationalRecallReadiness.CAPACITY_EXCEEDED, result.readiness)
        assertTrue(result.memories.isEmpty())
    }

    @Test
    fun recallReceiptBecomesStaleAfterForgetBeforeContextConsumption() = runBlocking {
        insertMemory("tea", "Shai enjoys cardamom tea.")
        val recall = retriever()
        val receipt = recall.retrieve(query("cardamom tea")).generation!!
        assertTrue(recall.isCurrent(receipt))

        assertTrue(MemoryTransactionService(database).forget(MemoryStateTransitionInput("tea", 100)) is MemoryWriteResult.Success)

        assertFalse(recall.isCurrent(receipt))
    }

    @Test
    fun registryOrderRecallReceiptIsRecheckedAfterQueuedTimelineReadAndConcurrentForget() = runBlocking {
        insertMemory("tea", "Shai enjoys cardamom tea.")
        val recall = retriever()
        val recallGeneration = recall.retrieve(query("cardamom tea")).generation!!
        val timeline = ConversationTimelineService(database)
        assertTrue(
            timeline.createConversationWithTimeline(
                CreateTimelineConversationInput(
                    conversationId = "freshness-conversation",
                    createdAt = 1,
                    updatedAt = 1,
                    status = ConversationStatus.ACTIVE,
                ),
            ) is TimelineWriteResult.ConversationCreated,
        )
        assertTrue(
            timeline.appendMessage(
                AppendTimelineMessageInput(
                    conversationId = "freshness-conversation",
                    message = NewTimelineMessageInput(
                        messageId = "freshness-user",
                        role = MessageRole.USER,
                        deliveryState = MessageDeliveryState.PERSISTED,
                        content = "cardamom tea",
                        createdAt = 2,
                        updatedAt = 2,
                    ),
                    expectedTimelineRevision = 0,
                    occurredAt = 2,
                ),
            ) is TimelineWriteResult.MessageAppended,
        )
        val recallReceipt = RivenContextFreshnessReceipt.ConversationalRecall(
            databaseSessionId = recallGeneration.databaseSessionId,
            algorithmVersion = recallGeneration.algorithmVersion,
            corpusGeneration = recallGeneration.corpusGeneration,
        )
        val timelineReceipt = RivenContextFreshnessReceipt.ActiveConversation(
            conversationId = "freshness-conversation",
            timelineRevision = 1,
        )
        val snapshot = RivenContextSnapshot(
            fragments = emptyList(),
            optionalFailures = emptyList(),
            freshnessReceipts = linkedSetOf(recallReceipt, timelineReceipt),
        )
        val timelineReadStarted = CompletableDeferred<Unit>()
        val releaseTimelineRead = CompletableDeferred<Unit>()
        val validator = ConversationalContextFreshnessValidator(
            activeConversationValidator = ActiveConversationReceiptValidator { receipt ->
                timelineReadStarted.complete(Unit)
                releaseTimelineRead.await()
                val read = timeline.activeTimelineTail(receipt.conversationId, 1)
                read is TimelineReadResult.Success && read.timelineRevision == receipt.timelineRevision
            },
            recallValidator = recall,
        )

        val validation = async { validator.validate(snapshot) }
        timelineReadStarted.await()
        assertTrue(
            MemoryTransactionService(database).forget(MemoryStateTransitionInput("tea", 100)) is
                MemoryWriteResult.Success,
        )
        releaseTimelineRead.complete(Unit)

        val result = validation.await() as RivenContextFreshnessValidation.Stale
        assertEquals(setOf(recallReceipt), result.receipts)
    }

    @Test
    fun inFlightCanonicalMutationMakesConversationalReceiptStaleUntilOutcomeSettles() = runBlocking {
        insertMemory("rain", "Shai enjoys rain.")
        val recall = retriever()
        assertReady(recall.retrieve(query("rain")))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val mutation = async(Dispatchers.IO) {
            database.validationRecallCorpusFence().withCanonicalMutation(
                change = { ValidationRecallCorpusChange.memoryIds(emptySet()) },
            ) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        val during = recall.retrieve(query("rain"))
        release.complete(Unit)
        mutation.await()
        val after = recall.retrieve(query("rain"))

        assertEquals(ConversationalRecallReadiness.STALE, during.readiness)
        assertReady(after)
    }

    @Test
    fun cancellationPropagatesAndCloseDetachesLifecycleOwner() {
        insertMemory("rain", "rain memory")
        val before = database.validationRecallCorpusFence().liveInvalidationListenerCount()
        val recall = TargetedConversationalMemoryRetriever(database)
        assertEquals(before + 1, database.validationRecallCorpusFence().liveInvalidationListenerCount())
        val job = Job().apply { cancel() }
        var cancelled = false
        try {
            runBlocking(EmptyCoroutineContext + job) { recall.retrieve(query("rain")) }
        } catch (_: CancellationException) {
            cancelled = true
        }
        recall.close()

        assertTrue(cancelled)
        assertEquals(before, database.validationRecallCorpusFence().liveInvalidationListenerCount())
    }

    @Test
    fun databaseReplacementGetsNewGenerationSession() = runBlocking {
        insertMemory("rain", "rain memory")
        val first = retriever().retrieve(query("rain")).generation!!
        val replacement = newDatabase()
        val secondRetriever = TargetedConversationalMemoryRetriever(replacement)
        try {
            val second = secondRetriever.retrieve(query("rain")).generation!!
            assertNotEquals(first.databaseSessionId, second.databaseSessionId)
        } finally {
            secondRetriever.close()
            replacement.close()
        }
    }

    @Test
    fun tenThousandMemoryIncrementalUpdateTouchesOnlyChangedIsolatedVertex() = runBlocking {
        val reader = LargeConversationalCorpusReader(memoryCount = 10_000, graphVertices = 4_000)
        val recall = TargetedConversationalMemoryRetriever(
            database,
            Dispatchers.IO,
            ConversationalRecallLimits(),
            reader,
        )
        closeables += recall
        assertEquals(listOf("isolated"), recall.retrieve(query("isolatedbefore")).memories.map { it.memoryId })
        assertEquals(1, reader.memoryPagePasses)

        reader.updateIsolated("isolatedafter")
        database.validationRecallCorpusFence().withCanonicalMutation(
            change = { ValidationRecallCorpusChange.memoryIds(setOf("isolated")) },
        ) { Unit }
        val updated = recall.retrieve(query("isolatedafter"))

        assertEquals(listOf("isolated"), updated.memories.map { it.memoryId })
        assertEquals(1, reader.memoryPagePasses)
        assertEquals(1, reader.memoryRowsCalls)
        assertEquals(ConversationalRecallIncrementalWork(1, 0, 1), recall.incrementalWorkSnapshot())
    }

    @Test
    fun tenThousandMemoryContextAssemblyKeepsSixtyFourIncrementalAndBoundsSixtyFiveFallback() = runBlocking {
        val reader = LargeConversationalCorpusReader(memoryCount = 10_000, graphVertices = 4_000)
        val recall = TargetedConversationalMemoryRetriever(
            database,
            Dispatchers.IO,
            ConversationalRecallLimits(),
            reader,
        )
        closeables += recall
        assertReady(recall.retrieve(query("topic0")))
        assertEquals(1, reader.memoryPagePasses)

        val sixtyFour = (0 until 64).map { "memory-${it.toString().padStart(5, '0')}" }.toSet()
        reader.updateMeanings(sixtyFour, "batch64marker")
        database.validationRecallCorpusFence().withCanonicalMutation(
            change = { ValidationRecallCorpusChange.memoryIds(sixtyFour) },
        ) { Unit }
        val incremental = recall.retrieve(query("batch64marker0"))
        assertReady(incremental)
        assertEquals(listOf("memory-00000"), incremental.memories.map { it.memoryId })
        assertEquals(1, reader.memoryPagePasses)
        assertEquals(1, reader.memoryRowsCalls)

        val sixtyFive = (100 until 165).map { "memory-${it.toString().padStart(5, '0')}" }.toSet()
        reader.updateMeanings(sixtyFive, "batch65marker")
        database.validationRecallCorpusFence().withCanonicalMutation(
            change = { ValidationRecallCorpusChange.memoryIds(sixtyFive) },
        ) { Unit }
        val bounded = recall.retrieve(query("batch65marker0"))
        assertEquals(ConversationalRecallReadiness.CAPACITY_EXCEEDED, bounded.readiness)
        assertTrue(bounded.memories.isEmpty())
        assertEquals(ConversationalRecallReadiness.READY, recall.prepare())
        val rebuilt = recall.retrieve(query("batch65marker0"))
        assertReady(rebuilt)
        assertEquals(listOf("memory-00100"), rebuilt.memories.map { it.memoryId })
        assertEquals(2, reader.memoryPagePasses)

        val transcript = object : RivenContextSource {
            override val descriptor = RivenContextSourceDescriptor(
                sourceId = "STRESS_TRANSCRIPT",
                layer = RivenContextLayer.ACTIVE_CANONICAL_CONVERSATION_AND_CURRENT_INTERACTION,
                provenanceClass = RivenContextProvenanceClass.ACTIVE_CONVERSATION,
                criticality = RivenContextSourceCriticality.REQUIRED,
                orderWithinLayer = 0,
                maxFragments = 32,
                maxCharsPerFragment = 1_024,
                maxAggregateChars = 32_768,
                budgetBehavior = RivenContextBudgetBehavior.REQUIRED,
            )

            override suspend fun read(request: RivenContextReadRequest) = RivenContextSourceResult.Success(
                (0 until 32).map { index ->
                    RivenContextPayload("transcript-$index", "role=USER\n" + "t".repeat(700), observedAt = request.now)
                },
            )
        }
        val openLoops = object : RivenContextSource {
            override val descriptor = RivenContextSourceDescriptor(
                sourceId = "STRESS_OPEN_LOOPS",
                layer = RivenContextLayer.RETRIEVED_DYNAMIC_MEMORY_OPEN_LOOPS_AND_TOOL_CONTEXT,
                provenanceClass = RivenContextProvenanceClass.OPEN_LOOP,
                criticality = RivenContextSourceCriticality.OPTIONAL,
                orderWithinLayer = 20,
                maxFragments = 4,
                maxCharsPerFragment = 3_100,
                maxAggregateChars = 12_400,
                budgetBehavior = RivenContextBudgetBehavior.DROP_IF_NEEDED,
            )

            override suspend fun read(request: RivenContextReadRequest) = RivenContextSourceResult.Success(
                (0 until 4).map { index ->
                    RivenContextPayload("loop-$index", "type=OPEN_LOOP\n" + "o".repeat(3_000), observedAt = request.now)
                },
            )
        }
        val assembled = ConversationalContextAssembler(
            RivenContextSourceRegistry(
                listOf(ConversationalMemoryContextSource(recall), openLoops, transcript),
            ),
        ).assemble(
            ConversationalContextAssemblyInput(
                now = 500,
                conversation = RivenConversationContextRequest(
                    conversationId = "stress-conversation",
                    expectedTimelineRevision = 1,
                    currentInteraction = RivenCurrentInteraction(content = "batch65marker0"),
                ),
            ),
        ) as RivenContextCollectionResult.Success
        val snapshot = assembled.snapshot
        assertTrue(snapshot.fragments.size <= 48)
        assertTrue(snapshot.fragments.sumOf { it.content.length } <= 32_768)
        assertEquals(32, snapshot.fragments.count { it.sourceId == "STRESS_TRANSCRIPT" })
        assertTrue(snapshot.fragments.any { it.sourceId == ConversationalMemoryContextSource.SOURCE_ID })
        assertTrue(snapshot.budgetOmissions.any { it.sourceId == "STRESS_OPEN_LOOPS" })
        assertTrue(
            snapshot.fragments.filter { it.sourceId == ConversationalMemoryContextSource.SOURCE_ID }
                .all { it.contentAuthority == RivenContextContentAuthority.UNTRUSTED_DATA },
        )
    }

    private fun retriever(limits: ConversationalRecallLimits = ConversationalRecallLimits()) =
        TargetedConversationalMemoryRetriever(database, limits = limits).also { closeables += it }

    private fun query(
        text: String,
        cues: RivenGroundedRecallCues = RivenGroundedRecallCues(),
        now: Long = 100,
    ) = ConversationalMemoryQuery(text, now, cues)

    private fun insertMemory(
        id: String,
        meaning: String,
        truth: MemoryTruthState = MemoryTruthState.SUPPORTED,
        retention: MemoryRetentionState = MemoryRetentionState.ACTIVE,
        lifecycle: MemoryLifecycleState = MemoryLifecycleState.VALIDATED,
        temporal: TemporalState = TemporalState.CURRENT,
        certainty: MemoryCertainty = MemoryCertainty.PROBABLE,
        sensitivity: SensitivityLevel = SensitivityLevel.STANDARD,
        validFrom: Long? = null,
        validUntil: Long? = null,
    ) {
        val experienceId = "experience-$id"
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = experienceId,
                eventOrder = clock++,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = meaning,
                occurredAt = clock,
                recordedAt = clock,
                sensitivity = sensitivity,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = id,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = certainty,
                truthState = truth,
                retentionState = retention,
                lifecycleState = lifecycle,
                temporalState = temporal,
                learnedAt = clock,
                validFrom = validFrom,
                validUntil = validUntil,
                lastConfirmedAt = clock,
                autobiographicalSignificance = SignificanceLevel.MODERATE,
                sensitivity = sensitivity,
                createdAt = clock,
                updatedAt = clock++,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = id,
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = certainty,
                lineageKey = "lineage-$id",
                createdAt = clock++,
            ),
        )
    }

    private fun insertRelationship(source: String, target: String, type: MemoryRelationshipType) {
        database.memoryDao().insertMemoryRelationship(
            MemoryRelationshipEntity(source, target, type, createdByExperienceId = null, createdAt = clock++),
        )
    }

    private fun assertReady(result: ConversationalMemoryRetrieval) {
        assertEquals(ConversationalRecallReadiness.READY, result.readiness)
        assertEquals(CONVERSATIONAL_RECALL_ALGORITHM_VERSION, result.generation?.algorithmVersion)
        assertTrue(result.memories.size <= 8)
    }

    private fun newDatabase(): RivenDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private class LargeConversationalCorpusReader(
        memoryCount: Int,
        graphVertices: Int,
    ) : ConversationalRecallCorpusReader {
        private val rows = buildMap<String, ConversationalRecallMemoryRow> {
            repeat(memoryCount - 1) { index ->
                val id = "memory-${index.toString().padStart(5, '0')}"
                put(id, row(id, "topic$index"))
            }
            put("isolated", row("isolated", "isolatedbefore"))
        }.toSortedMap()
        private val relationships = (0 until graphVertices - 1).map { index ->
            MemoryRelationshipEntity(
                sourceMemoryId = "memory-${index.toString().padStart(5, '0')}",
                targetMemoryId = "memory-${(index + 1).toString().padStart(5, '0')}",
                relationshipType = MemoryRelationshipType.REFINES,
                createdByExperienceId = null,
                createdAt = index.toLong(),
            )
        }
        var memoryPagePasses = 0
        var memoryRowsCalls = 0

        fun updateIsolated(meaning: String) {
            rows["isolated"] = row("isolated", meaning)
        }

        fun updateMeanings(memoryIds: Set<String>, prefix: String) {
            memoryIds.sorted().forEachIndexed { index, memoryId ->
                rows[memoryId] = row(memoryId, "$prefix$index")
            }
        }

        override fun memoryPage(afterMemoryId: String, limit: Int, maxMeaningCharsPlusOne: Int): List<ConversationalRecallMemoryRow> {
            if (afterMemoryId.isEmpty()) {
                memoryPagePasses++
            }
            return rows.values.asSequence().filter { it.memoryId > afterMemoryId }.take(limit).toList()
        }

        override fun memoryRows(memoryIds: List<String>, maxMeaningCharsPlusOne: Int): List<ConversationalRecallMemoryRow> {
            memoryRowsCalls++
            return memoryIds.mapNotNull(rows::get)
        }

        override fun memoryEntityRows(memoryIds: List<String>, limit: Int): List<ValidationRecallEntityRow> = emptyList()

        override fun outgoingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity> {
            val ids = memoryIds.toSet()
            return relationships.asSequence().filter { it.sourceMemoryId in ids }.take(limit).toList()
        }

        override fun incomingRelationships(memoryIds: List<String>, limit: Int): List<MemoryRelationshipEntity> {
            val ids = memoryIds.toSet()
            return relationships.asSequence().filter { it.targetMemoryId in ids }.take(limit).toList()
        }

        private companion object {
            fun row(id: String, meaning: String) = ConversationalRecallMemoryRow(
                memoryId = id,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                meaningLength = meaning.length.toLong(),
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = MemoryCertainty.CERTAIN,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = 1,
                validFrom = null,
                validUntil = null,
                lastConfirmedAt = 1,
                autobiographicalSignificance = null,
                relationshipSignificance = null,
                emotionalSignificance = null,
                practicalSignificance = null,
                identitySignificance = null,
                sensitivity = SensitivityLevel.STANDARD,
                updatedAt = 1,
            )
        }
    }
}
