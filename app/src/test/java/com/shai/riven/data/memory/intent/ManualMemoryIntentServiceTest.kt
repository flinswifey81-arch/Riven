package com.shai.riven.data.memory.intent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.background.DerivedArtifactRepairService
import com.shai.riven.data.background.ProvenanceRepairService
import com.shai.riven.data.background.RepairJobHandlerResult
import com.shai.riven.data.background.RivenBackgroundClock
import com.shai.riven.data.background.RivenBackgroundScheduleError
import com.shai.riven.data.background.RivenBackgroundScheduleResult
import com.shai.riven.data.background.RivenBackgroundWorkScheduler
import com.shai.riven.data.deletion.SafeDeleteService
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteIdGenerator
import com.shai.riven.data.memory.ConsolidationProposal
import com.shai.riven.data.memory.ConsolidationSnapshot
import com.shai.riven.data.memory.MemoryConsolidationDecider
import com.shai.riven.data.memory.MemoryConsolidationResult
import com.shai.riven.data.memory.MemoryConsolidationService
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.DerivedArtifactState
import com.shai.riven.data.persistence.model.DerivedArtifactType
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
import com.shai.riven.data.persistence.model.ExperienceType
import com.shai.riven.data.persistence.model.MemoryAuditAction
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
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import com.shai.riven.data.recall.ConversationalMemoryQuery
import com.shai.riven.data.recall.TargetedConversationalMemoryRetriever
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class ManualMemoryIntentServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var scheduler: RecordingScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingScheduler()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun rememberCreatesDirectValidatedMemory() = runBlocking {
        val result = service().remember(rememberInput("memory-1", "experience-1", "Shai likes green."))

        assertRemembered(result)
        val experience = database.memoryDao().experience("experience-1")
        assertNotNull(experience)
        assertEquals(ExperienceType.MANUAL_MEMORY_INTENT, experience?.experienceType)
        assertEquals(ExperienceActor.SHAI, experience?.actor)
        assertEquals(ExperienceAvailability.AVAILABLE, experience?.availability)
        assertEquals("Shai likes green.", experience?.sourceContent)
        val memory = database.memoryDao().memory("memory-1")
        assertEquals(EpistemicBasis.DIRECT_USER_STATEMENT, memory?.epistemicBasis)
        assertEquals(MemoryTruthState.SUPPORTED, memory?.truthState)
        assertEquals(MemoryRetentionState.ACTIVE, memory?.retentionState)
        assertEquals(MemoryLifecycleState.VALIDATED, memory?.lifecycleState)
        val evidence = database.memoryDao().evidenceForMemory("memory-1").single()
        assertEquals(EvidenceRole.SUPPORTS, evidence.role)
        assertEquals(EpistemicBasis.DIRECT_USER_STATEMENT, evidence.epistemicBasis)
        assertEquals("MANUAL_INTENT:experience-1", evidence.lineageKey)
        assertFalse(evidence.lineageKey.contains("green"))
        assertEquals(MemoryAuditAction.CREATED, database.maintenanceDao().memoryAuditHistory("memory-1").single().action)
        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun rememberPreservesExactMeaning() = runBlocking {
        val meaning = "  Shai keeps intentional whitespace.  "

        assertRemembered(service().remember(rememberInput("memory-1", "experience-1", meaning)))

        assertEquals(meaning, database.memoryDao().memory("memory-1")?.meaning)
        assertEquals(meaning, database.memoryDao().experience("experience-1")?.sourceContent)
    }

    @Test
    fun rememberAtomicRollbackRemovesExperienceMemoryEvidenceAndAudit() = runBlocking {
        val failing = service(afterExperienceInserted = { error("controlled") })

        val error = assertFailure(failing.remember(rememberInput("memory-1", "experience-1")))

        assertEquals(ManualMemoryIntentError.StorageFailure(ManualMemoryIntentKind.REMEMBER), error)
        assertNull(database.memoryDao().experience("experience-1"))
        assertNull(database.memoryDao().memory("memory-1"))
        assertEquals(0, database.memoryDao().memoryEvidenceCount("memory-1"))
        assertTrue(database.maintenanceDao().memoryAuditHistory("memory-1").isEmpty())
    }

    @Test
    fun eventOrderAllocatesAfterExistingMaximumAndCanonicalStructuredLinksWork() = runBlocking {
        insertExperience("seed-experience", 41)
        insertMemory("target-memory", "seed-experience")
        insertEntity("entity-1")
        val input = rememberInput("memory-1", "experience-1").copy(
            entityLinks = listOf(ManualMemoryEntityLinkInput("entity-1", EntityLinkRole.ABOUT)),
            relationships = listOf(
                ManualMemoryRelationshipInput("target-memory", MemoryRelationshipType.RELATED_TO),
            ),
        )

        assertRemembered(service().remember(input))

        assertEquals(42L, database.memoryDao().experience("experience-1")?.eventOrder)
        assertEquals(1, database.memoryDao().memoryEntityLinkCount("memory-1"))
        assertEquals(
            1,
            database.memoryDao().memoryRelationshipCount(
                "memory-1",
                "target-memory",
                MemoryRelationshipType.RELATED_TO,
            ),
        )
    }

    @Test
    fun eventOrderStartsAtOneWhenNoExperienceExists() = runBlocking {
        assertRemembered(service().remember(rememberInput("memory-1", "experience-1")))

        assertEquals(1L, database.memoryDao().experience("experience-1")?.eventOrder)
    }

    @Test
    fun concurrentManualIntentsAllocateUniqueEventOrders() = runBlocking {
        val results = (1..16).map { index ->
            async(Dispatchers.IO) {
                service().remember(
                    rememberInput(
                        memoryId = "memory-$index",
                        experienceId = "experience-$index",
                        meaning = "Meaning $index.",
                    ),
                )
            }
        }.awaitAll()

        results.forEach(::assertRemembered)
        val orders = (1..16).map { index ->
            database.memoryDao().experience("experience-$index")!!.eventOrder
        }
        assertEquals(16, orders.toSet().size)
        assertEquals((1L..16L).toList(), orders.sorted())
    }

    @Test
    fun eventOrderOverflowIsTypedAndMutatesNothing() = runBlocking {
        insertExperience("maximum", Long.MAX_VALUE)

        val error = assertFailure(service().remember(rememberInput("memory-1", "experience-1")))

        assertEquals(ManualMemoryIntentError.EventOrderOverflow, error)
        assertNull(database.memoryDao().experience("experience-1"))
        assertNull(database.memoryDao().memory("memory-1"))
    }

    @Test
    fun blankAndOverMaximumMeaningsAreRejectedWithoutMutation() = runBlocking {
        val blank = service().remember(rememberInput("memory-1", "experience-1", "   \n"))
        val over = service().remember(
            rememberInput("memory-2", "experience-2", "x".repeat(MAX_MANUAL_MEMORY_MEANING_CHARS + 1)),
        )

        assertEquals(
            ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.BLANK),
            assertFailure(blank),
        )
        assertEquals(
            ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.TOO_LONG),
            assertFailure(over),
        )
        assertEquals(0, database.memoryDao().experienceCount())
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test
    fun invalidTemporalWindowIsRejected() = runBlocking {
        val result = service().remember(
            rememberInput("memory-1", "experience-1").copy(validFrom = 10, validUntil = 10),
        )

        assertEquals(ManualMemoryIntentError.InvalidTemporalWindow, assertFailure(result))
        assertEquals(0, database.memoryDao().experienceCount())
    }

    @Test
    fun invalidAndOverlongIdsAreRejectedBeforeMutation() = runBlocking {
        val blankMemoryId = service().remember(rememberInput("   ", "experience-1"))
        val overlongExperienceId = service().remember(
            rememberInput("memory-2", "x".repeat(MAX_MANUAL_MEMORY_ID_CHARS + 1)),
        )

        assertEquals(
            ManualMemoryIntentError.InvalidId(ManualMemoryIdField.MEMORY_ID),
            assertFailure(blankMemoryId),
        )
        assertEquals(
            ManualMemoryIntentError.InvalidId(ManualMemoryIdField.EXPERIENCE_ID),
            assertFailure(overlongExperienceId),
        )
        assertEquals(0, database.memoryDao().experienceCount())
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test
    fun correctionReplacementMeaningUsesTheSameValidationWithoutMutation() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")

        val blank = service().correct(correctInput().copy(replacementMeaning = "  "))
        val overlong = service().correct(
            correctInput().copy(replacementMeaning = "x".repeat(MAX_MANUAL_MEMORY_MEANING_CHARS + 1)),
        )

        assertEquals(
            ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.BLANK),
            assertFailure(blank),
        )
        assertEquals(
            ManualMemoryIntentError.InvalidMeaning(InvalidManualMeaningReason.TOO_LONG),
            assertFailure(overlong),
        )
        assertNull(database.memoryDao().experience("experience-new"))
        assertNull(database.memoryDao().memory("memory-new"))
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory("memory-old")?.truthState)
    }

    @Test
    fun suppliedIdCollisionIsTypedAndLeavesNoPartialState() = runBlocking {
        insertExperience("old-source", 1)
        insertMemory("memory-1", "old-source")

        val result = service().remember(rememberInput("memory-1", "experience-new"))

        assertEquals(ManualMemoryIntentError.MemoryAlreadyExists("memory-1"), assertFailure(result))
        assertNull(database.memoryDao().experience("experience-new"))
        assertEquals(1, database.memoryDao().memoryCount())
    }

    @Test
    fun manualCorrectionCreatesReplacementCorrectionLineageAndRepair() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old", "Incorrect meaning.")
        insertArtifact("artifact-old", memoryId = "memory-old")

        val result = service().correct(correctInput())

        val corrected = assertCorrected(result)
        assertEquals(ManualMemoryMaintenanceScheduling.ENQUEUED, corrected.maintenanceScheduling)
        val experience = database.memoryDao().experience("experience-new")
        assertEquals(ExperienceType.MANUAL_MEMORY_INTENT, experience?.experienceType)
        assertEquals(ExperienceActor.SHAI, experience?.actor)
        assertEquals("Accurate meaning.", experience?.sourceContent)
        val replacement = database.memoryDao().memory("memory-new")
        assertEquals("Accurate meaning.", replacement?.meaning)
        assertEquals(EpistemicBasis.EXPLICIT_CORRECTION, replacement?.epistemicBasis)
        val evidence = database.memoryDao().evidenceForMemory("memory-new").single()
        assertEquals("experience-new", evidence.experienceId)
        assertEquals(EvidenceRole.CORRECTS, evidence.role)
        assertEquals(EpistemicBasis.EXPLICIT_CORRECTION, evidence.epistemicBasis)
        assertEquals(MemoryTruthState.CORRECTED_FALSE, database.memoryDao().memory("memory-old")?.truthState)
        assertEquals(
            1,
            database.memoryDao().memoryRelationshipCount(
                "memory-new",
                "memory-old",
                MemoryRelationshipType.CORRECTS,
            ),
        )
        assertEquals(
            listOf(MemoryAuditAction.CORRECTED),
            database.maintenanceDao().memoryAuditHistory("memory-old").map { it.action },
        )
        assertEquals(DerivedArtifactState.STALE, database.maintenanceDao().derivedArtifact("artifact-old")?.state)
        assertTrue(database.maintenanceDao().repairJobs("MEMORY", "memory-old").isNotEmpty())
    }

    @Test
    fun correctedReplacementSurvivesObsoleteDeleteAndFeedsRecallConsolidationAndSearchDocument() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old", "Incorrect meaning.")
        assertCorrected(service().correct(correctInput()))
        insertArtifact(
            artifactId = "artifact-replacement",
            memoryId = "memory-new",
            artifactType = DerivedArtifactType.SEARCH_DOCUMENT,
        )

        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-old", 20)))
        assertEquals(
            RepairJobHandlerResult.Success,
            ProvenanceRepairService(database, RivenBackgroundClock { 21L }).repair(
                "MEMORY",
                "memory-new",
            ),
        )

        val replacement = checkNotNull(database.memoryDao().memory("memory-new"))
        assertEquals(MemoryTruthState.SUPPORTED, replacement.truthState)
        assertEquals(MemoryLifecycleState.VALIDATED, replacement.lifecycleState)
        assertEquals(EvidenceRole.CORRECTS, database.memoryDao().evidenceForMemory("memory-new").single().role)

        val recall = TargetedConversationalMemoryRetriever(database)
        val recalled = recall.retrieve(ConversationalMemoryQuery("accurate meaning", 22L))
        recall.close()
        assertEquals(listOf("memory-new"), recalled.memories.map { it.memoryId })

        insertExperience("experience-peer", 23)
        insertMemory("memory-peer", "experience-peer", "Independent peer meaning.")
        var observedSnapshot: ConsolidationSnapshot? = null
        assertEquals(
            MemoryConsolidationResult.NoConsolidation,
            MemoryConsolidationService(
                database = database,
                decider = object : MemoryConsolidationDecider {
                    override suspend fun proposeConsolidation(snapshot: ConsolidationSnapshot): ConsolidationProposal {
                        observedSnapshot = snapshot
                        return ConsolidationProposal.NoConsolidation
                    }
                },
                profileId = "profile",
                clock = { 24L },
            ).consolidate(),
        )
        assertTrue(checkNotNull(observedSnapshot).sources.any { it.memoryId == "memory-new" })

        assertEquals(
            RepairJobHandlerResult.Success,
            DerivedArtifactRepairService(database, RivenBackgroundClock { 25L }).repair(
                "DERIVED_ARTIFACT",
                "artifact-replacement",
            ),
        )
        assertTrue(
            checkNotNull(database.memoryLifecycleDao().derivedPayload("artifact-replacement"))
                .content.contains("Accurate meaning."),
        )
    }

    @Test
    fun correctionDoesNotCopyFalseEvidence() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-old-2", 2)
        insertMemory("memory-old", "experience-old")
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = "memory-old",
                experienceId = "experience-old-2",
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.INFERENCE,
                sourceCertainty = MemoryCertainty.UNCERTAIN,
                lineageKey = "old-lineage-2",
                createdAt = 2,
            ),
        )

        assertCorrected(service().correct(correctInput()))

        val replacementEvidence = database.memoryDao().evidenceForMemory("memory-new")
        assertEquals(1, replacementEvidence.size)
        assertEquals("experience-new", replacementEvidence.single().experienceId)
    }

    @Test
    fun correctionPreservesStructuralMetadataAndSafeEntityLinks() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        val old = database.memoryDao().memory("memory-old")!!
        database.memoryDao().updateMemory(
            old.copy(
                kind = MemoryKind.RELATIONSHIP,
                scope = MemoryScope.SHARED,
                certainty = MemoryCertainty.PROBABLE,
                sensitivity = SensitivityLevel.HIGHLY_SENSITIVE,
                autobiographicalSignificance = SignificanceLevel.HIGH,
                relationshipSignificance = SignificanceLevel.CORE,
                emotionalSignificance = SignificanceLevel.MODERATE,
                practicalSignificance = SignificanceLevel.LOW,
                identitySignificance = SignificanceLevel.HIGH,
                validFrom = 5,
                validUntil = 500,
            ),
        )
        insertEntity("entity-1")
        database.memoryDao().insertMemoryEntityLink(
            MemoryEntityLinkEntity("memory-old", "entity-1", EntityLinkRole.INVOLVES, 1),
        )

        assertCorrected(service().correct(correctInput()))

        val replacement = database.memoryDao().memory("memory-new")!!
        assertEquals(MemoryKind.RELATIONSHIP, replacement.kind)
        assertEquals(MemoryScope.SHARED, replacement.scope)
        assertEquals(MemoryCertainty.PROBABLE, replacement.certainty)
        assertEquals(SensitivityLevel.HIGHLY_SENSITIVE, replacement.sensitivity)
        assertEquals(SignificanceLevel.HIGH, replacement.autobiographicalSignificance)
        assertEquals(SignificanceLevel.CORE, replacement.relationshipSignificance)
        assertEquals(SignificanceLevel.MODERATE, replacement.emotionalSignificance)
        assertEquals(SignificanceLevel.LOW, replacement.practicalSignificance)
        assertEquals(SignificanceLevel.HIGH, replacement.identitySignificance)
        assertEquals(5L, replacement.validFrom)
        assertEquals(500L, replacement.validUntil)
        assertEquals(
            database.memoryDao().entityLinksForMemory("memory-old").map { it.entityId to it.role },
            database.memoryDao().entityLinksForMemory("memory-new").map { it.entityId to it.role },
        )
    }

    @Test
    fun correctionAtomicRollbackLeavesOldMemoryAndNoOrphanExperienceOrRepair() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        val original = database.memoryDao().memory("memory-old")
        val failingTransactions = MemoryTransactionService(
            database,
            MemoryWriteIdGenerator { "duplicate-audit-id" },
        )

        val result = service(memoryTransactions = failingTransactions).correct(correctInput())

        assertTrue(assertFailure(result) is ManualMemoryIntentError.MemoryWriteFailure)
        assertEquals(original, database.memoryDao().memory("memory-old"))
        assertNull(database.memoryDao().experience("experience-new"))
        assertNull(database.memoryDao().memory("memory-new"))
        assertEquals(
            0,
            database.memoryDao().memoryRelationshipCount(
                "memory-new",
                "memory-old",
                MemoryRelationshipType.CORRECTS,
            ),
        )
        assertTrue(database.maintenanceDao().memoryAuditHistory("memory-old").isEmpty())
        assertTrue(database.maintenanceDao().repairJobs("MEMORY", "memory-old").isEmpty())
    }

    @Test
    fun correctionInvalidTargetIsTypedAndCreatesNoExperience() = runBlocking {
        val result = service().correct(correctInput())

        assertEquals(ManualMemoryIntentError.MemoryNotFound("memory-old"), assertFailure(result))
        assertNull(database.memoryDao().experience("experience-new"))
        assertNull(database.memoryDao().memory("memory-new"))
    }

    @Test
    fun correctionIllegalTargetStateIsTyped() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        val old = database.memoryDao().memory("memory-old")!!
        database.memoryDao().updateMemory(old.copy(retentionState = MemoryRetentionState.FORGOTTEN))

        val result = service().correct(correctInput())

        assertEquals(ManualMemoryIntentError.IllegalMemoryState("memory-old"), assertFailure(result))
        assertNull(database.memoryDao().experience("experience-new"))
        assertNull(database.memoryDao().memory("memory-new"))
    }

    @Test
    fun manualForgetRetainsMemoryEvidenceAndExperienceWhileSuppressingAndInvalidating() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        insertArtifact("artifact-old", memoryId = "memory-old")

        val result = service().forget(ManualForgetMemoryInput("memory-old", 10))

        assertForgotten(result)
        assertEquals(MemoryRetentionState.FORGOTTEN, database.memoryDao().memory("memory-old")?.retentionState)
        assertEquals(1, database.memoryDao().memoryEvidenceCount("memory-old"))
        assertNotNull(database.memoryDao().experience("experience-old"))
        val tombstone = database.maintenanceDao().suppressionTombstones().single()
        assertEquals(SuppressionKind.FORGET, tombstone.kind)
        assertTrue(tombstone.isActive)
        assertEquals(DerivedArtifactState.STALE, database.maintenanceDao().derivedArtifact("artifact-old")?.state)
        assertTrue(database.maintenanceDao().repairJobs("MEMORY", "memory-old").isNotEmpty())
        assertEquals(1, database.memoryDao().experienceCount())
    }

    @Test
    fun forgetNeverDowngradesActiveDeleteTombstone() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        database.maintenanceDao().insertSuppressionTombstone(
            SuppressionTombstoneEntity(
                id = "delete-tombstone",
                kind = SuppressionKind.DELETE,
                sourceLineageHash = lineageHash("experience-old", "lineage-experience-old"),
                isActive = true,
                createdAt = 1,
                formatVersion = 1,
            ),
        )

        assertForgotten(service().forget(ManualForgetMemoryInput("memory-old", 10)))

        val tombstone = database.maintenanceDao().suppressionTombstones().single()
        assertEquals("delete-tombstone", tombstone.id)
        assertEquals(SuppressionKind.DELETE, tombstone.kind)
        assertTrue(tombstone.isActive)
    }

    @Test
    fun forgetDoesNotDeleteSourceTranscript() = runBlocking {
        insertSourceMessage("experience-old", "message-1")
        insertMemory("memory-old", "experience-old")

        assertForgotten(service().forget(ManualForgetMemoryInput("memory-old", 10)))

        assertEquals(listOf("message-1"), database.conversationDao().messagesForConversation("conversation-1").map { it.id })
    }

    @Test
    fun forgetSchedulesRepairSweepBestEffort() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")

        val result = assertForgotten(service().forget(ManualForgetMemoryInput("memory-old", 10)))

        assertEquals(ManualMemoryMaintenanceScheduling.ENQUEUED, result.maintenanceScheduling)
        assertEquals(1, scheduler.repairSweepCalls)
    }

    @Test
    fun schedulerFailureDoesNotRollBackForget() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        scheduler.repairResult = RivenBackgroundScheduleResult.Failure(
            RivenBackgroundScheduleError.SchedulerFailure("enqueueRepairSweep", "Controlled"),
        )

        val result = assertForgotten(service().forget(ManualForgetMemoryInput("memory-old", 10)))

        assertEquals(ManualMemoryMaintenanceScheduling.FAILED, result.maintenanceScheduling)
        assertEquals(MemoryRetentionState.FORGOTTEN, database.memoryDao().memory("memory-old")?.retentionState)
    }

    @Test
    fun manualDeleteRoutesThroughSafeDeleteAndRetainsProvenanceAndTranscript() = runBlocking {
        insertSourceMessage("experience-old", "message-1")
        insertMemory("memory-old", "experience-old")
        insertArtifact(
            artifactId = "artifact-old",
            memoryId = "memory-old",
            experienceId = "experience-old",
            messageId = "message-1",
        )

        val result = assertDeleted(service().delete(ManualDeleteMemoryInput("memory-old", 20)))

        assertNull(database.memoryDao().memory("memory-old"))
        assertNotNull(database.memoryDao().experience("experience-old"))
        assertEquals(1, database.conversationDao().messagesForConversation("conversation-1").size)
        assertEquals(1, result.suppressionTombstoneIds.size)
        assertEquals(SuppressionKind.DELETE, database.maintenanceDao().suppressionTombstones().single().kind)
        assertEquals(DerivedArtifactState.INVALIDATED, database.maintenanceDao().derivedArtifact("artifact-old")?.state)
        assertTrue(database.maintenanceDao().repairJobs("MEMORY", "memory-old").isNotEmpty())
        assertEquals(ManualMemoryMaintenanceScheduling.ENQUEUED, result.maintenanceScheduling)
    }

    @Test
    fun forgetThenDeleteUpgradesAndReusesTombstone() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        assertForgotten(service().forget(ManualForgetMemoryInput("memory-old", 10)))
        val forgottenTombstone = database.maintenanceDao().suppressionTombstones().single()

        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-old", 20)))

        val deleteTombstone = database.maintenanceDao().suppressionTombstones().single()
        assertEquals(forgottenTombstone.id, deleteTombstone.id)
        assertEquals(SuppressionKind.DELETE, deleteTombstone.kind)
        assertTrue(deleteTombstone.isActive)
    }

    @Test
    fun deleteDoesNotCreateManualExperience() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        val countBefore = database.memoryDao().experienceCount()

        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-old", 20)))

        assertEquals(countBefore, database.memoryDao().experienceCount())
        assertEquals(ExperienceType.SHARED_EVENT, database.memoryDao().experience("experience-old")?.experienceType)
    }

    @Test
    fun deleteSchedulerFailureIsAdvisoryAfterCommittedHardDelete() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")
        scheduler.throwOnRepairSweep = true

        val result = assertDeleted(service().delete(ManualDeleteMemoryInput("memory-old", 20)))

        assertEquals(ManualMemoryMaintenanceScheduling.FAILED, result.maintenanceScheduling)
        assertNull(database.memoryDao().memory("memory-old"))
        assertEquals(SuppressionKind.DELETE, database.maintenanceDao().suppressionTombstones().single().kind)
    }

    @Test
    fun sameMeaningCanBeRememberedAgainAfterForgetWithIndependentLineage() = runBlocking {
        val first = assertRemembered(service().remember(rememberInput("memory-a", "experience-a", "Same meaning.")))
        assertForgotten(service().forget(ManualForgetMemoryInput(first.memoryId, 10)))
        val oldTombstone = database.maintenanceDao().suppressionTombstones().single()

        val second = assertRemembered(service().remember(rememberInput("memory-b", "experience-b", "Same meaning.")))

        assertNotEquals(first.memoryId, second.memoryId)
        assertNotEquals(first.experienceId, second.experienceId)
        assertEquals("MANUAL_INTENT:experience-b", database.memoryDao().evidenceForMemory("memory-b").single().lineageKey)
        assertEquals(oldTombstone, database.maintenanceDao().suppressionTombstones().single())
    }

    @Test
    fun sameMeaningCanBeRememberedAgainAfterDeleteWithIndependentLineage() = runBlocking {
        val first = assertRemembered(service().remember(rememberInput("memory-a", "experience-a", "Same meaning.")))
        assertDeleted(service().delete(ManualDeleteMemoryInput(first.memoryId, 10)))
        val oldTombstone = database.maintenanceDao().suppressionTombstones().single()

        val second = assertRemembered(service().remember(rememberInput("memory-b", "experience-b", "Same meaning.")))

        assertNotEquals(first.memoryId, second.memoryId)
        assertNotEquals(first.experienceId, second.experienceId)
        assertEquals("MANUAL_INTENT:experience-b", database.memoryDao().evidenceForMemory("memory-b").single().lineageKey)
        assertEquals(SuppressionKind.DELETE, oldTombstone.kind)
        assertEquals(oldTombstone, database.maintenanceDao().suppressionTombstones().single())
    }

    @Test
    fun rememberDoesNotCreateCandidateMemory() = runBlocking {
        assertRemembered(service().remember(rememberInput("memory-1", "experience-1")))

        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun correctDoesNotCreateCandidateMemory() = runBlocking {
        insertExperience("experience-old", 1)
        insertMemory("memory-old", "experience-old")

        assertCorrected(service().correct(correctInput()))

        assertEquals(0, database.memoryDao().candidateMemoryCount())
    }

    @Test
    fun forgetAndDeleteControlActionsCreateNoSemanticExperiences() = runBlocking {
        insertExperience("experience-forget", 1)
        insertMemory("memory-forget", "experience-forget")
        insertExperience("experience-delete", 2)
        insertMemory("memory-delete", "experience-delete")
        val before = database.memoryDao().experienceCount()

        assertForgotten(service().forget(ManualForgetMemoryInput("memory-forget", 10)))
        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-delete", 11)))

        assertEquals(before, database.memoryDao().experienceCount())
    }

    @Test
    fun allFourOperationsHaveNoConversationDependency() = runBlocking {
        assertRemembered(service().remember(rememberInput("memory-a", "experience-a")))
        assertCorrected(
            service().correct(
                ManualCorrectMemoryInput(
                    memoryId = "memory-a",
                    replacementMeaning = "Corrected.",
                    occurredAt = 2,
                    replacementMemoryId = "memory-b",
                    experienceId = "experience-b",
                ),
            ),
        )
        assertForgotten(service().forget(ManualForgetMemoryInput("memory-b", 3)))
        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-b", 4)))

        assertEquals(0L, tableCount("conversations"))
        assertEquals(0L, tableCount("messages"))
    }

    @Test
    fun allFourOperationsHaveNoProviderOrCredentialDependency() = runBlocking {
        assertRemembered(service().remember(rememberInput("memory-a", "experience-a")))
        assertCorrected(
            service().correct(
                ManualCorrectMemoryInput(
                    memoryId = "memory-a",
                    replacementMeaning = "Corrected.",
                    occurredAt = 2,
                    replacementMemoryId = "memory-b",
                    experienceId = "experience-b",
                ),
            ),
        )
        assertForgotten(service().forget(ManualForgetMemoryInput("memory-b", 3)))
        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-b", 4)))

        assertEquals(0L, tableCount("provider_profiles"))
        assertEquals(0L, tableCount("provider_profile_capabilities"))
    }

    @Test
    fun correctForgetDeleteInvokeOnlyOpaqueRepairSweepScheduling() = runBlocking {
        assertRemembered(service().remember(rememberInput("memory-a", "experience-a", "Secret semantic text.")))
        assertCorrected(
            service().correct(
                ManualCorrectMemoryInput(
                    memoryId = "memory-a",
                    replacementMeaning = "New secret semantic text.",
                    occurredAt = 2,
                    replacementMemoryId = "memory-b",
                    experienceId = "experience-b",
                ),
            ),
        )
        assertForgotten(service().forget(ManualForgetMemoryInput("memory-b", 3)))
        assertDeleted(service().delete(ManualDeleteMemoryInput("memory-b", 4)))

        assertEquals(3, scheduler.repairSweepCalls)
        assertTrue(scheduler.targetedIds.isEmpty())
    }

    @Test
    fun rememberDoesNotScheduleRepairSweep() = runBlocking {
        val result = assertRemembered(service().remember(rememberInput("memory-1", "experience-1")))

        assertEquals(ManualMemoryMaintenanceScheduling.NOT_REQUIRED, result.maintenanceScheduling)
        assertEquals(0, scheduler.repairSweepCalls)
    }

    private fun service(
        afterExperienceInserted: (ManualMemoryIntentKind) -> Unit = {},
        memoryTransactions: MemoryTransactionService = MemoryTransactionService(database),
        safeDeleteService: SafeDeleteService = SafeDeleteService(database),
    ) = ManualMemoryIntentService(
        database = database,
        backgroundScheduler = scheduler,
        idGenerator = object : ManualMemoryIntentIdGenerator {
            override fun nextExperienceId(): String = "generated-experience"

            override fun nextMemoryId(): String = "generated-memory"
        },
        memoryTransactions = memoryTransactions,
        safeDeleteService = safeDeleteService,
        afterExperienceInserted = afterExperienceInserted,
    )

    private fun rememberInput(
        memoryId: String,
        experienceId: String,
        meaning: String = "Remembered meaning.",
    ) = ManualRememberMemoryInput(
        meaning = meaning,
        kind = MemoryKind.SEMANTIC,
        scope = MemoryScope.SHAI,
        certainty = MemoryCertainty.CERTAIN,
        sensitivity = SensitivityLevel.STANDARD,
        occurredAt = 1,
        memoryId = memoryId,
        experienceId = experienceId,
    )

    private fun correctInput() = ManualCorrectMemoryInput(
        memoryId = "memory-old",
        replacementMeaning = "Accurate meaning.",
        occurredAt = 10,
        replacementMemoryId = "memory-new",
        experienceId = "experience-new",
    )

    private fun insertExperience(id: String, order: Long) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = order,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = "Source for $id.",
                occurredAt = order,
                recordedAt = order,
                sensitivity = SensitivityLevel.STANDARD,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
    }

    private fun insertMemory(
        memoryId: String,
        experienceId: String,
        meaning: String = "Old meaning.",
    ) {
        database.memoryDao().insertMemory(
            MemoryEntity(
                id = memoryId,
                kind = MemoryKind.SEMANTIC,
                scope = MemoryScope.SHAI,
                meaning = meaning,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                certainty = MemoryCertainty.CERTAIN,
                truthState = MemoryTruthState.SUPPORTED,
                retentionState = MemoryRetentionState.ACTIVE,
                lifecycleState = MemoryLifecycleState.VALIDATED,
                temporalState = TemporalState.CURRENT,
                learnedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = memoryId,
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "lineage-$experienceId",
                createdAt = 1,
            ),
        )
    }

    private fun insertEntity(entityId: String) {
        database.memoryDao().insertEntity(
            KnownEntityEntity(
                id = entityId,
                kind = EntityKind.PERSON,
                displayName = entityId,
                normalizedName = entityId,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun insertSourceMessage(experienceId: String, messageId: String) {
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = "conversation-1",
                createdAt = 1,
                updatedAt = 1,
                status = ConversationStatus.ACTIVE,
            ),
        )
        database.conversationDao().insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = "conversation-1",
                sequenceNumber = 1,
                role = MessageRole.USER,
                deliveryState = MessageDeliveryState.PERSISTED,
                content = "Retained transcript.",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        insertExperience(experienceId, 1)
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = experienceId,
                messageId = messageId,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )
    }

    private fun insertArtifact(
        artifactId: String,
        memoryId: String? = null,
        experienceId: String? = null,
        messageId: String? = null,
        artifactType: DerivedArtifactType = DerivedArtifactType.SUMMARY,
    ) {
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = artifactId,
                artifactType = artifactType,
                state = DerivedArtifactState.CURRENT,
                producerVersion = "test",
                sourceRevision = 1,
                createdAt = 1,
            ),
        )
        memoryId?.let {
            database.maintenanceDao().insertDerivedArtifactMemoryDependency(
                DerivedArtifactMemoryDependencyEntity(artifactId, it, 1),
            )
        }
        experienceId?.let {
            database.maintenanceDao().insertDerivedArtifactExperienceDependency(
                DerivedArtifactExperienceDependencyEntity(artifactId, it, 1),
            )
        }
        messageId?.let {
            database.maintenanceDao().insertDerivedArtifactMessageDependency(
                DerivedArtifactMessageDependencyEntity(artifactId, it, 1),
            )
        }
    }

    private fun lineageHash(experienceId: String, lineageKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$experienceId:$lineageKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun tableCount(table: String): Long = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertRemembered(result: ManualMemoryIntentResult): ManualMemoryIntentResult.Remembered {
        assertTrue("Expected Remembered but was $result", result is ManualMemoryIntentResult.Remembered)
        return result as ManualMemoryIntentResult.Remembered
    }

    private fun assertCorrected(result: ManualMemoryIntentResult): ManualMemoryIntentResult.Corrected {
        assertTrue("Expected Corrected but was $result", result is ManualMemoryIntentResult.Corrected)
        return result as ManualMemoryIntentResult.Corrected
    }

    private fun assertForgotten(result: ManualMemoryIntentResult): ManualMemoryIntentResult.Forgotten {
        assertTrue("Expected Forgotten but was $result", result is ManualMemoryIntentResult.Forgotten)
        return result as ManualMemoryIntentResult.Forgotten
    }

    private fun assertDeleted(result: ManualMemoryIntentResult): ManualMemoryIntentResult.Deleted {
        assertTrue("Expected Deleted but was $result", result is ManualMemoryIntentResult.Deleted)
        return result as ManualMemoryIntentResult.Deleted
    }

    private fun assertFailure(result: ManualMemoryIntentResult): ManualMemoryIntentError {
        assertTrue("Expected Failure but was $result", result is ManualMemoryIntentResult.Failure)
        return (result as ManualMemoryIntentResult.Failure).error
    }

    private class RecordingScheduler : RivenBackgroundWorkScheduler {
        var repairSweepCalls = 0
        var repairResult: RivenBackgroundScheduleResult = enqueued("repair-sweep")
        var throwOnRepairSweep = false
        val targetedIds = mutableListOf<String>()

        override fun enqueueAttachmentCleanup(attachmentId: String): RivenBackgroundScheduleResult {
            targetedIds += attachmentId
            return enqueued("attachment")
        }

        override fun enqueueAttachmentMaintenanceSweep(): RivenBackgroundScheduleResult =
            enqueued("attachment-sweep")

        override fun enqueueRepairJob(repairJobId: String): RivenBackgroundScheduleResult {
            targetedIds += repairJobId
            return enqueued("repair-job")
        }

        override fun enqueueRepairSweep(): RivenBackgroundScheduleResult {
            repairSweepCalls += 1
            if (throwOnRepairSweep) error("controlled scheduler failure")
            return repairResult
        }

        override fun ensurePeriodicMaintenance(): RivenBackgroundScheduleResult = enqueued("periodic")

        private companion object {
            fun enqueued(name: String) = RivenBackgroundScheduleResult.Enqueued(listOf(name))
        }
    }
}
