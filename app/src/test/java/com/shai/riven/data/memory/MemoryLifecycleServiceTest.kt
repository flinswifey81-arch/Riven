package com.shai.riven.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.DerivedArtifactRepairService
import com.shai.riven.data.background.RepairJobHandlerResult
import com.shai.riven.data.background.RivenBackgroundClock
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryAccessibilityBand
import com.shai.riven.data.persistence.model.MemoryCertainty
import com.shai.riven.data.persistence.model.MemoryKind
import com.shai.riven.data.persistence.model.MemoryLifecycleState
import com.shai.riven.data.persistence.model.MemoryRetentionState
import com.shai.riven.data.persistence.model.MemoryScope
import com.shai.riven.data.persistence.model.MemoryTruthState
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.TemporalState
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
    fun derivedArtifactRebuildUsesCurrentSourcesAndForgetRemovesPayloadBeforeRetry() = runBlocking {
        insertExperience("repair-source", 1)
        insertMemory("repair-memory", "repair-source", "Current canonical meaning.")
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = "artifact",
                artifactType = DerivedArtifactType.SUMMARY,
                state = DerivedArtifactState.INVALIDATED,
                producerVersion = "test",
                sourceRevision = 0,
                createdAt = 1,
                invalidatedAt = 2,
            ),
        )
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity("artifact", "repair-memory", 1),
        )
        val repair = DerivedArtifactRepairService(database, RivenBackgroundClock { 10L })

        assertEquals(RepairJobHandlerResult.Success, repair.repair("DERIVED_ARTIFACT", "artifact"))
        assertNotNull(database.memoryLifecycleDao().derivedPayload("artifact"))
        assertEquals(DerivedArtifactState.CURRENT, database.maintenanceDao().derivedArtifact("artifact")!!.state)

        val forgotten = MemoryTransactionService(database).forget(
            MemoryStateTransitionInput("repair-memory", occurredAt = 11L),
        )
        assertTrue(forgotten is MemoryWriteResult.Success)
        assertNull(database.memoryLifecycleDao().derivedPayload("artifact"))
        assertEquals(
            RepairJobHandlerResult.PermanentFailure("NO_CURRENT_VALID_SOURCES"),
            repair.repair("DERIVED_ARTIFACT", "artifact"),
        )
        assertNull(database.memoryLifecycleDao().derivedPayload("artifact"))
    }

    private fun insertExperience(id: String, order: Long) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = order,
                experienceType = ExperienceType.OTHER,
                actor = ExperienceActor.SHAI,
                sourceContent = "Source $id",
                occurredAt = order,
                recordedAt = order,
                sensitivity = SensitivityLevel.STANDARD,
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
}
