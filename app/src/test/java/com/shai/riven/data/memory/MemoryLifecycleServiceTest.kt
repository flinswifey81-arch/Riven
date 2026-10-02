package com.shai.riven.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.DerivedArtifactRepairService
import com.shai.riven.data.background.RepairJobHandlerResult
import com.shai.riven.data.background.RivenBackgroundClock
import com.shai.riven.data.background.ProvenanceRepairService
import com.shai.riven.data.deletion.DeleteMemoryInput
import com.shai.riven.data.deletion.MemoryDeleteResult
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MemoryRelationshipEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRelationshipType
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.MessageDeliveryState
import com.shai.riven.data.persistence.model.MessageRole
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.recall.ConversationalMemoryQuery
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import java.util.ArrayDeque
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
class MemoryLifecycleServiceTest {
    private lateinit var database: RivenDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun independentSourcesConsolidateOnceAndNeverInflateCertaintyOnReplay() = runBlocking {
        insertExperience("e1", 1)
        insertExperience("e2", 2)
        insertMemory("m1", "e1", "Shai likes quiet mornings.", MemoryCertainty.CERTAIN)
        insertMemory("m2", "e2", "Shai works best before noon.", MemoryCertainty.PROBABLE)
        var calls = 0
        val decider = object : MemoryConsolidationDecider {
            override suspend fun proposeConsolidation(snapshot: ConsolidationSnapshot): ConsolidationProposal {
                calls += 1
                return ConsolidationProposal.Create(
                    sourceMemoryIds = listOf("m1", "m2"),
                    meaning = "Shai tends to prefer focused, quiet mornings.",
                    kind = MemoryKind.SEMANTIC,
                    scope = MemoryScope.SHAI,
                    certainty = MemoryCertainty.PROBABLE,
                    sensitivity = SensitivityLevel.STANDARD,
                )
            }
        }
        val ids = ArrayDeque(listOf("consolidated", "checkpoint"))
        val service = MemoryConsolidationService(
            database,
            decider,
            profileId = "profile-a",
            idGenerator = MemoryLifecycleIdGenerator { ids.removeFirst() },
            clock = { 100L },
        )

        assertEquals(MemoryConsolidationResult.Created("consolidated"), service.consolidate())
        val replay = service.consolidate()

        assertEquals(MemoryConsolidationResult.AlreadyProcessed("consolidated"), replay)
        assertEquals(1, calls)
        val consolidated = database.memoryDao().memory("consolidated")!!
        assertEquals(EpistemicBasis.CONSOLIDATION, consolidated.epistemicBasis)
        assertEquals(MemoryCertainty.PROBABLE, consolidated.certainty)
        assertEquals(setOf("e1", "e2"), database.memoryDao().evidenceForMemory("consolidated")
            .map { it.experienceId }.toSet())
        assertEquals(2, database.memoryDao().directRelationshipsForMemory("consolidated").size)
    }

    @Test
    fun agingChangesAccessibilityAndReinforcementReactivatesWithoutChangingTruth() = runBlocking {
        insertExperience("old-source", 1)
        insertMemory("old", "old-source", "A low-significance old detail.", learnedAt = 1L)
        insertExperience("core-source", 2)
        insertMemory(
            "core",
            "core-source",
            "A core identity fact.",
            learnedAt = 1L,
            identitySignificance = SignificanceLevel.CORE,
        )
        val now = MemoryAgingService.DORMANCY_AFTER_MS + 2L
        insertExperience("limited-source", 3)
        insertMemory(
            "limited",
            "limited-source",
            "A low-significance detail entering limited access.",
            learnedAt = now - MemoryAgingService.LIMITED_AFTER_MS - 1L,
        )

        val aging = MemoryAgingService(database, clock = { now }, batchLimit = 1)
        val firstBatch = aging.sweep()
        val secondBatch = aging.sweep()
        val aged = aging.sweep()

        assertEquals(1, firstBatch.evaluatedCount)
        assertTrue(firstBatch.hasMore)
        assertEquals(1, secondBatch.evaluatedCount)
        assertTrue(secondBatch.hasMore)
        assertEquals(1, aged.evaluatedCount)
        assertFalse(aged.hasMore)
        assertEquals(MemoryRetentionState.DORMANT, database.memoryDao().memory("old")!!.retentionState)
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory("old")!!.truthState)
        assertEquals(MemoryAccessibilityBand.DORMANT, database.memoryLifecycleDao().accessibility("old")!!.band)
        assertEquals(MemoryRetentionState.ACTIVE, database.memoryDao().memory("core")!!.retentionState)
        assertEquals(MemoryRetentionState.ACTIVE, database.memoryDao().memory("limited")!!.retentionState)
        assertEquals(MemoryAccessibilityBand.LIMITED, database.memoryLifecycleDao().accessibility("limited")!!.band)
        insertExperience("new-source", 4)
        val reinforced = MemoryTransactionService(database).reinforce(
            ReinforceMemoryInput(
                memoryId = "old",
                evidence = ReinforcementEvidenceInput(
                    experienceId = "new-source",
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = "new-lineage",
                ),
                confirmedAt = now + 1,
                occurredAt = now + 1,
            ),
        )
        assertTrue(reinforced is MemoryWriteResult.Success)
        assertEquals(MemoryRetentionState.ACTIVE, database.memoryDao().memory("old")!!.retentionState)
    }

    @Test
    fun agingUsesDurableCursorAcrossMoreThanTwoBatchesAndServiceRestart() = runBlocking {
        val sweepAt = MemoryAgingService.DORMANCY_AFTER_MS + 10_000L
        repeat(205) { index ->
            val suffix = index.toString().padStart(3, '0')
            insertExperience("bulk-e-$suffix", 1_000L + index)
            insertMemory(
                memoryId = "bulk-m-$suffix",
                experienceId = "bulk-e-$suffix",
                meaning = "Durable aging item $suffix",
                learnedAt = 1L,
                identitySignificance = SignificanceLevel.CORE,
            )
        }
        var advancingClock = sweepAt
        val firstService = MemoryAgingService(
            database,
            clock = { advancingClock++ },
            batchLimit = 80,
        )

        assertTrue(firstService.sweep().hasMore)
        assertTrue(firstService.sweep().hasMore)
        val restarted = MemoryAgingService(
            database,
            clock = { advancingClock += 1_000L; advancingClock },
            batchLimit = 80,
        )
        val completed = restarted.sweep()

        assertEquals(45, completed.evaluatedCount)
        assertFalse(completed.hasMore)
        assertEquals(205L, rowCount("memory_accessibility"))
        assertEquals(1L, singleLong("SELECT COUNT(DISTINCT evaluated_at) FROM memory_accessibility"))
        assertNull(database.memoryLifecycleDao().agingSweepCheckpoint(MemoryAgingService.CHECKPOINT_ID))
    }

    @Test
    fun agingRecomputesInsideFenceAfterDormancyAndLimitedReinforcementInterleavings() = runBlocking {
        val sweepAt = MemoryAgingService.DORMANCY_AFTER_MS + 20_000L
        insertExperience("dormant-old", 1)
        insertMemory("dormant-race", "dormant-old", "Dormancy race", learnedAt = 1L)
        insertExperience("limited-old", 2)
        insertMemory(
            "limited-race",
            "limited-old",
            "Limited race",
            learnedAt = sweepAt - MemoryAgingService.LIMITED_AFTER_MS - 1L,
        )
        var eventOrder = 10L
        val reinforced = linkedSetOf<String>()
        val aging = MemoryAgingService(
            database,
            clock = { sweepAt },
            batchLimit = 2,
            beforeEvaluate = { memoryId ->
                if (reinforced.add(memoryId)) {
                    val experienceId = "fresh-$memoryId"
                    insertExperience(experienceId, eventOrder++)
                    val result = MemoryTransactionService(database).reinforce(
                        ReinforceMemoryInput(
                            memoryId = memoryId,
                            evidence = ReinforcementEvidenceInput(
                                experienceId,
                                EpistemicBasis.DIRECT_USER_STATEMENT,
                                MemoryCertainty.CERTAIN,
                                "fresh-lineage-$memoryId",
                            ),
                            confirmedAt = sweepAt + 1L,
                            occurredAt = sweepAt + 1L,
                        ),
                    )
                    assertTrue(result is MemoryWriteResult.Success)
                }
            },
        )

        val result = aging.sweep()

        assertEquals(0, result.dormantCount)
        listOf("dormant-race", "limited-race").forEach { memoryId ->
            assertEquals(MemoryRetentionState.ACTIVE, database.memoryDao().memory(memoryId)?.retentionState)
            assertEquals(MemoryAccessibilityBand.ORDINARY, database.memoryLifecycleDao().accessibility(memoryId)?.band)
        }
    }

    @Test
    fun consolidationAdvancesAcrossIndependentPatternsAndPreservesSupportRoles() = runBlocking {
        (1..5).forEach { insertExperience("pattern-e$it", it.toLong()) }
        (1..4).forEach { insertMemory("pattern-m$it", "pattern-e$it", "Pattern fact $it") }
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = "pattern-m1",
                experienceId = "pattern-e5",
                role = EvidenceRole.CONTRADICTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.UNCERTAIN,
                lineageKey = "contradiction",
                createdAt = 5,
            ),
        )
        val ids = ArrayDeque(listOf("pattern-c1", "pattern-cp1", "pattern-c2", "pattern-cp2", "pattern-cp3"))
        val service = MemoryConsolidationService(
            database = database,
            decider = object : MemoryConsolidationDecider {
                override suspend fun proposeConsolidation(snapshot: ConsolidationSnapshot): ConsolidationProposal {
                    val processed = snapshot.processedSourceSets.map { it.toSet() }
                    val sourceIds = when {
                        setOf("pattern-m1", "pattern-m2") !in processed -> listOf("pattern-m1", "pattern-m2")
                        setOf("pattern-m3", "pattern-m4") !in processed -> listOf("pattern-m3", "pattern-m4")
                        else -> return ConsolidationProposal.NoConsolidation
                    }
                    return ConsolidationProposal.Create(
                        sourceMemoryIds = sourceIds,
                        meaning = "Combined ${sourceIds.joinToString()}",
                        kind = MemoryKind.SEMANTIC,
                        scope = MemoryScope.SHAI,
                        certainty = MemoryCertainty.CERTAIN,
                        sensitivity = SensitivityLevel.STANDARD,
                    )
                }
            },
            profileId = "provider-profile",
            idGenerator = MemoryLifecycleIdGenerator { ids.removeFirst() },
            clock = { 100L },
        )

        assertEquals(MemoryConsolidationResult.Created("pattern-c1"), service.consolidate())
        assertEquals(MemoryConsolidationResult.Created("pattern-c2"), service.consolidate())
        assertEquals(MemoryConsolidationResult.NoConsolidation, service.consolidate())
        val firstEvidence = database.memoryDao().evidenceForMemory("pattern-c1")
        assertEquals(setOf("pattern-e1", "pattern-e2"), firstEvidence.map { it.experienceId }.toSet())
        assertTrue(firstEvidence.all { it.role == EvidenceRole.SUPPORTS })
        assertFalse(firstEvidence.any { it.experienceId == "pattern-e5" })
    }

    @Test
    fun consolidationCheckpointChangesWhenSupportingEvidenceRevisionChanges() = runBlocking {
        insertExperience("revision-e1", 1)
        insertExperience("revision-e2", 2)
        insertMemory("revision-m1", "revision-e1", "Revision fact one")
        insertMemory("revision-m2", "revision-e2", "Revision fact two")
        val ids = ArrayDeque(listOf("revision-c1", "revision-cp1", "revision-c2", "revision-cp2"))
        val decider = object : MemoryConsolidationDecider {
            override suspend fun proposeConsolidation(snapshot: ConsolidationSnapshot) = ConsolidationProposal.Create(
                sourceMemoryIds = listOf("revision-m1", "revision-m2"),
                meaning = "Revision-aware combination",
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                certainty = MemoryCertainty.CERTAIN,
                sensitivity = SensitivityLevel.STANDARD,
            )
        }
        val service = MemoryConsolidationService(
            database,
            decider,
            "provider-profile",
            MemoryLifecycleIdGenerator { ids.removeFirst() },
            clock = { 200L },
        )
        assertEquals(MemoryConsolidationResult.Created("revision-c1"), service.consolidate())
        insertExperience("revision-e3", 3)
        assertTrue(MemoryTransactionService(database).reinforce(
            ReinforceMemoryInput(
                "revision-m1",
                ReinforcementEvidenceInput(
                    "revision-e3",
                    EpistemicBasis.DIRECT_USER_STATEMENT,
                    MemoryCertainty.CERTAIN,
                    "revision-extra",
                ),
                201L,
                201L,
            ),
        ) is MemoryWriteResult.Success)

        assertEquals(MemoryConsolidationResult.Created("revision-c2"), service.consolidate())
        assertEquals(2L, rowCount("consolidation_checkpoints"))
    }

    @Test
    fun openLoopPassIsIdempotentAndSourceInvalidationWinsTheCommitRace() = runBlocking {
        insertExperience("loop-source", 1)
        insertForwardAttention("loop-source")
        var calls = 0
        val ids = ArrayDeque(listOf("loop-1", "audit-1"))
        val service = OpenLoopLifecycleService(
            database = database,
            decider = object : OpenLoopLifecycleDecider {
                override suspend fun proposeOpenLoop(snapshot: OpenLoopLifecycleSnapshot): OpenLoopLifecycleProposal {
                    calls += 1
                    return OpenLoopLifecycleProposal.Create(
                        title = "Send the draft",
                        state = OpenLoopState.ACTIVE,
                        sensitivity = SensitivityLevel.STANDARD,
                    )
                }
            },
            idGenerator = MemoryLifecycleIdGenerator { ids.removeFirst() },
            clock = { 50L },
        )

        assertEquals(OpenLoopLifecycleResult.Applied("loop-1"), service.process("loop-source"))
        assertEquals(OpenLoopLifecycleResult.AlreadyProcessed("loop-source"), service.process("loop-source"))
        assertEquals(1, calls)

        insertExperience("racing-source", 2)
        insertForwardAttention("racing-source")
        val racing = OpenLoopLifecycleService(
            database = database,
            decider = object : OpenLoopLifecycleDecider {
                override suspend fun proposeOpenLoop(snapshot: OpenLoopLifecycleSnapshot): OpenLoopLifecycleProposal {
                    database.memoryDao().updateExperienceAvailability(
                        "racing-source",
                        ExperienceAvailability.DELETED,
                    )
                    return OpenLoopLifecycleProposal.Create(
                        title = "Must not resurrect",
                        sensitivity = SensitivityLevel.STANDARD,
                    )
                }
            },
        ).process("racing-source")
        assertTrue(racing is OpenLoopLifecycleResult.Failure)
        assertNull(database.memoryLifecycleDao().openLoopCheckpoint("racing-source"))
        assertEquals(1, database.openLoopDao().conversationalContextCandidates(listOf(OpenLoopState.ACTIVE), 10).size)
    }

    @Test
    fun openLoopProcessingFindsRelevantOlderLoopAndExcludesUnrelatedSensitiveDetails() = runBlocking {
        insertExperience(
            "older-loop-source",
            1,
            content = "The ancient zebra draft is complete now.",
        )
        insertForwardAttention("older-loop-source")
        repeat(70) { index ->
            val isRelevant = index == 0
            val isSensitive = index == 1
            database.openLoopDao().insertOpenLoop(
                OpenLoopEntity(
                    id = when {
                        isRelevant -> "loop-ancient-zebra"
                        isSensitive -> "loop-private-vault"
                        else -> "loop-${index.toString().padStart(3, '0')}"
                    },
                    creationExperienceId = "older-loop-source",
                    title = when {
                        isRelevant -> "Ancient zebra draft"
                        isSensitive -> "Private vault passphrase rotation"
                        else -> "Unrelated task $index"
                    },
                    description = if (isSensitive) "Unrelated highly sensitive loop detail" else null,
                    state = OpenLoopState.ACTIVE,
                    openedAt = index.toLong(),
                    sensitivity = if (isSensitive) {
                        SensitivityLevel.HIGHLY_SENSITIVE
                    } else {
                        SensitivityLevel.STANDARD
                    },
                    createdAt = index.toLong(),
                    updatedAt = if (isRelevant) 1L else 100L + index,
                ),
            )
        }
        var observed: OpenLoopLifecycleSnapshot? = null
        val service = OpenLoopLifecycleService(
            database = database,
            decider = object : OpenLoopLifecycleDecider {
                override suspend fun proposeOpenLoop(snapshot: OpenLoopLifecycleSnapshot): OpenLoopLifecycleProposal {
                    observed = snapshot
                    return OpenLoopLifecycleProposal.Transition(
                        "loop-ancient-zebra",
                        OpenLoopState.COMPLETED,
                    )
                }
            },
            idGenerator = MemoryLifecycleIdGenerator { "older-loop-audit" },
            clock = { 1_000L },
        )

        assertEquals(OpenLoopLifecycleResult.Applied("loop-ancient-zebra"), service.process("older-loop-source"))
        val supplied = checkNotNull(observed).currentLoops
        assertEquals(64, supplied.size)
        assertTrue(supplied.any { it.openLoopId == "loop-ancient-zebra" })
        assertFalse(supplied.any { it.openLoopId == "loop-private-vault" })
        assertEquals(
            SensitivityLevel.STANDARD,
            supplied.single { it.openLoopId == "loop-ancient-zebra" }.sensitivity,
        )
    }

    @Test
    fun sourceCorrectionForgetAndDeleteImmediatelyFenceAndThenResolveConsolidatedDependents() = runBlocking {
        val operations = listOf("correct", "forget", "delete")
        operations.forEachIndexed { index, operation ->
            val aExperience = "$operation-a-e"
            val bExperience = "$operation-b-e"
            insertExperience(aExperience, 20L + index * 10L)
            insertExperience(bExperience, 21L + index * 10L)
            insertMemory("$operation-a", aExperience, "$operation source alpha")
            insertMemory("$operation-b", bExperience, "$operation source beta")
            insertConsolidatedMemory(
                "$operation-conclusion",
                "$operation durable conclusion",
                listOf("$operation-a", "$operation-b"),
                listOf(aExperience, bExperience),
            )
        }
        insertExperience("unaffected-a-e", 80)
        insertExperience("unaffected-b-e", 81)
        insertMemory("unaffected-a", "unaffected-a-e", "unaffected alpha")
        insertMemory("unaffected-b", "unaffected-b-e", "unaffected beta")
        insertConsolidatedMemory(
            "unaffected-conclusion",
            "unaffected durable conclusion",
            listOf("unaffected-a", "unaffected-b"),
            listOf("unaffected-a-e", "unaffected-b-e"),
        )
        listOf("survivor-a", "survivor-b", "survivor-c").forEachIndexed { index, id ->
            insertExperience("$id-e", 90L + index)
            insertMemory(id, "$id-e", "$id support")
        }
        insertConsolidatedMemory(
            "survivor-conclusion",
            "survivor durable conclusion",
            listOf("survivor-a", "survivor-b", "survivor-c"),
            listOf("survivor-a-e", "survivor-b-e", "survivor-c-e"),
        )

        insertExperience("correct-replacement-e", 100)
        val corrected = MemoryTransactionService(database).correct(
            CorrectMemoryInput(
                inaccurateMemoryId = "correct-a",
                replacement = ValidatedMemoryInput(
                    memoryId = "correct-replacement",
                    kind = MemoryKind.SEMANTIC,
                    scope = MemoryScope.SHAI,
                    meaning = "corrected alpha",
                    epistemicBasis = EpistemicBasis.EXPLICIT_CORRECTION,
                    certainty = MemoryCertainty.CERTAIN,
                    learnedAt = 100,
                    sensitivity = SensitivityLevel.STANDARD,
                    evidence = listOf(
                        MemoryEvidenceInput(
                            "correct-replacement-e",
                            EvidenceRole.CORRECTS,
                            EpistemicBasis.EXPLICIT_CORRECTION,
                            MemoryCertainty.CERTAIN,
                            "correct-replacement-lineage",
                        ),
                    ),
                ),
                occurredAt = 100,
                triggeringExperienceId = "correct-replacement-e",
            ),
        )
        assertTrue(corrected is MemoryWriteResult.Success)
        assertTrue(MemoryTransactionService(database).forget(
            MemoryStateTransitionInput("forget-a", 101),
        ) is MemoryWriteResult.Success)
        assertTrue(
            SafeDeleteService(database).deleteMemory(DeleteMemoryInput("delete-a", 102)) is
                MemoryDeleteResult.Deleted,
        )
        assertTrue(MemoryTransactionService(database).forget(
            MemoryStateTransitionInput("survivor-a", 103),
        ) is MemoryWriteResult.Success)

        val repair = ProvenanceRepairService(database, RivenBackgroundClock { 200L }) { "audit-${System.nanoTime()}" }
        operations.forEach { operation ->
            val conclusionId = "$operation-conclusion"
            assertEquals(
                MemoryLifecycleState.REASSESSMENT_PENDING,
                database.memoryDao().memory(conclusionId)?.lifecycleState,
            )
            assertTrue(database.memoryDao().conversationalRecallMemoryRows(listOf(conclusionId), 1_000).isEmpty())
            assertEquals(RepairJobHandlerResult.Success, repair.repair("MEMORY", conclusionId))
            val after = checkNotNull(database.memoryDao().memory(conclusionId))
            assertEquals(MemoryTruthState.UNSUPPORTED, after.truthState)
            assertEquals(MemoryLifecycleState.RESOLVED, after.lifecycleState)
            assertTrue(database.memoryDao().conversationalRecallMemoryRows(listOf(conclusionId), 1_000).isEmpty())
            assertEquals(1, database.memoryDao().evidenceForMemory(conclusionId).size)
            assertEquals(1, database.memoryDao().directRelationshipsForMemory(conclusionId).size)
        }
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory("unaffected-conclusion")?.truthState)
        assertEquals(
            MemoryLifecycleState.VALIDATED,
            database.memoryDao().memory("unaffected-conclusion")?.lifecycleState,
        )
        assertEquals(
            RepairJobHandlerResult.Success,
            repair.repair("MEMORY", "survivor-conclusion"),
        )
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory("survivor-conclusion")?.truthState)
        assertEquals(
            MemoryLifecycleState.VALIDATED,
            database.memoryDao().memory("survivor-conclusion")?.lifecycleState,
        )
        assertEquals(2, database.memoryDao().evidenceForMemory("survivor-conclusion").size)
        assertEquals(2, database.memoryDao().directRelationshipsForMemory("survivor-conclusion").size)
    }

    @Test
    fun candidateProvenanceRepairRejectsCandidateWithoutASeedInsteadOfEmptySuccess() = runBlocking {
        insertExperience("candidate-support", 1)
        database.memoryDao().insertCandidateMemory(
            CandidateMemoryEntity(
                id = "candidate",
                proposedKind = MemoryKind.SEMANTIC,
                proposedScope = MemoryScope.SHAI,
                proposedMeaning = "An unsupported candidate",
                proposedEpistemicBasis = EpistemicBasis.INFERENCE,
                proposedCertainty = MemoryCertainty.UNCERTAIN,
                state = CandidateMemoryState.TENTATIVE,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(
                candidateMemoryId = "candidate",
                experienceId = "candidate-support",
                evidenceOrder = 0,
                role = CandidateEvidenceRole.SUPPORTING,
                lineageKey = "candidate-support-lineage",
                createdAt = 1,
            ),
        )

        assertEquals(
            RepairJobHandlerResult.Success,
            ProvenanceRepairService(database, RivenBackgroundClock { 2L }).repair(
                "CANDIDATE_MEMORY",
                "candidate",
            ),
        )
        assertEquals(CandidateMemoryState.REJECTED, database.memoryDao().candidateMemory("candidate")?.state)
    }

    @Test
    fun deleteInvalidationRepairAndContextNeverResurrectSuppressedMeaningAndKeepSibling() = runBlocking {
        insertExperience("repair-deleted-source", 1)
        insertMemory("repair-deleted", "repair-deleted-source", "Deleted comet preference.")
        insertExperience("repair-sibling-source", 2)
        insertMemory("repair-sibling", "repair-sibling-source", "Sibling aurora preference.")
        database.conversationDao().insertConversation(
            ConversationEntity("repair-conversation", 1, 1, ConversationStatus.ACTIVE),
        )
        database.conversationDao().insertMessage(
            MessageEntity(
                "repair-message",
                "repair-conversation",
                1,
                MessageRole.USER,
                MessageDeliveryState.PERSISTED,
                "Raw deleted comet source",
                1,
                1,
            ),
        )
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                "repair-deleted-source",
                "repair-message",
                0,
                ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )
        database.openLoopDao().insertOpenLoop(
            OpenLoopEntity(
                id = "repair-loop",
                creationExperienceId = "repair-deleted-source",
                relatedMemoryId = "repair-deleted",
                title = "Deleted comet follow-up",
                state = OpenLoopState.ACTIVE,
                openedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        listOf("artifact", "artifact-message", "artifact-loop").forEach { artifactId ->
            database.maintenanceDao().insertDerivedArtifact(
                DerivedArtifactEntity(
                    id = artifactId,
                    artifactType = DerivedArtifactType.SEARCH_DOCUMENT,
                    state = DerivedArtifactState.INVALIDATED,
                    producerVersion = "test",
                    sourceRevision = 0,
                    createdAt = 1,
                    invalidatedAt = 2,
                ),
            )
            database.maintenanceDao().insertDerivedArtifactMemoryDependency(
                DerivedArtifactMemoryDependencyEntity(artifactId, "repair-sibling", 1),
            )
        }
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity("artifact", "repair-deleted", 1),
        )
        database.maintenanceDao().insertDerivedArtifactExperienceDependency(
            DerivedArtifactExperienceDependencyEntity("artifact", "repair-deleted-source", 1),
        )
        database.maintenanceDao().insertDerivedArtifactMessageDependency(
            DerivedArtifactMessageDependencyEntity("artifact-message", "repair-message", 1),
        )
        database.maintenanceDao().insertDerivedArtifactOpenLoopDependency(
            DerivedArtifactOpenLoopDependencyEntity("artifact-loop", "repair-loop", 1),
        )
        val repair = DerivedArtifactRepairService(database, RivenBackgroundClock { 10L })

        // A memory-target repair must visit the Experience-dependent artifact too.
        assertEquals(RepairJobHandlerResult.Success, repair.repair("MEMORY", "repair-deleted"))
        val initialPayload = checkNotNull(database.memoryLifecycleDao().derivedPayload("artifact")).content
        assertTrue(initialPayload.contains("Deleted comet preference."))
        assertTrue(initialPayload.contains("Sibling aurora preference."))
        assertFalse(initialPayload.contains("Source repair-deleted-source"))
        assertNotNull(database.memoryLifecycleDao().derivedPayload("artifact-message"))
        assertNotNull(database.memoryLifecycleDao().derivedPayload("artifact-loop"))
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("artifact")!!.state)

        val deleted = SafeDeleteService(database).deleteMemory(DeleteMemoryInput("repair-deleted", 11L))
        assertTrue(deleted is MemoryDeleteResult.Deleted)
        assertNull(database.memoryLifecycleDao().derivedPayload("artifact"))
        listOf("artifact", "artifact-message", "artifact-loop").forEach { artifactId ->
            assertNull(database.memoryLifecycleDao().derivedPayload(artifactId))
            assertEquals(RepairJobHandlerResult.Success, repair.repair("DERIVED_ARTIFACT", artifactId))
        }
        val rebuilt = checkNotNull(database.memoryLifecycleDao().derivedPayload("artifact")).content
        assertFalse(rebuilt.contains("Deleted comet preference."))
        assertFalse(rebuilt.contains("Source repair-deleted-source"))
        assertTrue(rebuilt.contains("Sibling aurora preference."))

        val recall = TargetedConversationalMemoryRetriever(database)
        val context = recall.retrieve(ConversationalMemoryQuery("comet aurora preference", 12L))
        recall.close()
        assertEquals(listOf("repair-sibling"), context.memories.map { it.memoryId })
    }

    @Test
    fun artifactKindsWithoutARealProducerFailWithoutBeingMarkedCurrent() = runBlocking {
        insertExperience("unsupported-source", 1)
        insertMemory("unsupported-memory", "unsupported-source", "Unsupported summary source")
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = "unsupported-summary",
                artifactType = DerivedArtifactType.SUMMARY,
                state = DerivedArtifactState.INVALIDATED,
                producerVersion = "missing",
                sourceRevision = 0,
                createdAt = 1,
                invalidatedAt = 2,
            ),
        )
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity("unsupported-summary", "unsupported-memory", 1),
        )

        assertEquals(
            RepairJobHandlerResult.PermanentFailure("UNSUPPORTED_ARTIFACT_TYPE_SUMMARY"),
            DerivedArtifactRepairService(database).repair("DERIVED_ARTIFACT", "unsupported-summary"),
        )
        assertEquals(
            DerivedArtifactState.INVALIDATED,
            database.maintenanceDao().derivedArtifact("unsupported-summary")?.state,
        )
        assertNull(database.memoryLifecycleDao().derivedPayload("unsupported-summary"))
    }

    private fun insertExperience(
        id: String,
        order: Long,
        content: String = "Source $id",
        sensitivity: SensitivityLevel = SensitivityLevel.STANDARD,
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = order,
                experienceType = ExperienceType.OTHER,
                actor = ExperienceActor.SHAI,
                sourceContent = content,
                occurredAt = order,
                recordedAt = order,
                sensitivity = sensitivity,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
    }

    private fun insertForwardAttention(experienceId: String) {
        database.experienceAttentionDao().insertAssessment(
            ExperienceAttentionAssessmentEntity(
                experienceId = experienceId,
                outcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                revision = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.experienceAttentionDao().insertSignal(
            ExperienceAttentionSignalEntity(
                experienceId = experienceId,
                signal = AttentionSignal.OPEN_LOOP,
                polarity = AttentionSignalPolarity.POSITIVE,
                createdAt = 1,
            ),
        )
    }

    private fun insertMemory(
        memoryId: String,
        experienceId: String,
        meaning: String,
        certainty: MemoryCertainty = MemoryCertainty.CERTAIN,
        learnedAt: Long = 1L,
        identitySignificance: SignificanceLevel? = null,
    ) {
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = certainty,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = learnedAt,
                identitySignificance = identitySignificance,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = learnedAt,
                updatedAt = learnedAt,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memoryId,
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = certainty,
                lineageKey = "lineage-$experienceId",
                createdAt = learnedAt,
            ),
        )
    }

    private fun insertConsolidatedMemory(
        memoryId: String,
        meaning: String,
        sourceMemoryIds: List<String>,
        experienceIds: List<String>,
    ) {
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                epistemicBasis = EpistemicBasis.CONSOLIDATION,
                certainty = MemoryCertainty.CERTAIN,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = 10,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        experienceIds.forEachIndexed { index, experienceId ->
            database.memoryDao().insertMemoryEvidence(
                MemoryEvidenceEntity(
                    memoryId = memoryId,
                    experienceId = experienceId,
                    role = EvidenceRole.SUPPORTS,
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = "consolidation-$memoryId-$index",
                    createdAt = 10,
                ),
            )
        }
        sourceMemoryIds.forEach { sourceMemoryId ->
            database.memoryDao().insertMemoryRelationship(
                MemoryRelationshipEntity(
                    sourceMemoryId = memoryId,
                    targetMemoryId = sourceMemoryId,
                    relationshipType = MemoryRelationshipType.DERIVED_FROM,
                    createdByExperienceId = null,
                    createdAt = 10,
                ),
            )
        }
    }

    private fun rowCount(table: String): Long = singleLong("SELECT COUNT(*) FROM $table")

    private fun singleLong(sql: String): Long = database.openHelper.readableDatabase.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
