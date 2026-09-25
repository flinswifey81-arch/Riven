package com.shai.riven.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactExperienceDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMemoryDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactMessageDependencyEntity
import com.shai.riven.data.persistence.entity.DerivedArtifactOpenLoopDependencyEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.OpenLoopEntity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
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
import com.shai.riven.data.persistence.model.OpenLoopState
import com.shai.riven.data.persistence.model.RepairJobState
import com.shai.riven.data.persistence.model.SensitivityLevel
import com.shai.riven.data.persistence.model.SignificanceLevel
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import java.security.MessageDigest
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
class MemoryTransactionServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var service: MemoryTransactionService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = MemoryTransactionService(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun secondSeedEvidenceIsRejectedWhileSupportingEvidenceRemainsAllowed() = runBlocking {
        insertCandidate("candidate-1", CandidateMemoryState.TENTATIVE)
        insertExperience("experience-1", 1)
        insertExperience("experience-2", 2)
        insertExperience("experience-3", 3)

        assertSuccess(
            service.addCandidateEvidence(
                candidateEvidence("candidate-1", "experience-1", 0, CandidateEvidenceRole.SEED),
            ),
        )
        val secondSeed = service.addCandidateEvidence(
            candidateEvidence("candidate-1", "experience-2", 1, CandidateEvidenceRole.SEED),
        )
        assertEquals(
            MemoryWriteError.CandidateSeedAlreadyExists("candidate-1"),
            assertFailure(secondSeed),
        )
        assertSuccess(
            service.addCandidateEvidence(
                candidateEvidence("candidate-1", "experience-2", 1, CandidateEvidenceRole.SUPPORTING),
            ),
        )
        assertSuccess(
            service.addCandidateEvidence(
                candidateEvidence("candidate-1", "experience-3", 2, CandidateEvidenceRole.SUPPORTING),
            ),
        )

        assertEquals(
            1,
            database.memoryDao().candidateEvidenceRoleCount("candidate-1", CandidateEvidenceRole.SEED),
        )
        assertEquals(
            2,
            database.memoryDao().candidateEvidenceRoleCount("candidate-1", CandidateEvidenceRole.SUPPORTING),
        )
    }

    @Test
    fun candidateAdmissionCreatesValidatedMemoryWithProvenanceThenRemovesCandidate() = runBlocking {
        insertCandidate("candidate-1", CandidateMemoryState.READY_FOR_VALIDATION)
        insertExperience("experience-1", 1)
        val entity = KnownEntityEntity(
            id = "entity-1",
            kind = EntityKind.PERSON,
            displayName = "Shai",
            normalizedName = "shai",
            createdAt = 1,
            updatedAt = 1,
        )
        database.memoryDao().insertEntity(entity)
        assertSuccess(
            service.addCandidateEvidence(
                candidateEvidence("candidate-1", "experience-1", 0, CandidateEvidenceRole.SEED),
            ),
        )

        val result = service.admitCandidate(
            AdmitCandidateMemoryInput(
                candidateId = "candidate-1",
                memoryId = "memory-1",
                learnedAt = 10,
                occurredAt = 11,
                significance = IntrinsicSignificanceInput(
                    autobiographical = SignificanceLevel.HIGH,
                    identity = SignificanceLevel.CORE,
                ),
                entityLinks = listOf(MemoryEntityLinkInput(entity.id, EntityLinkRole.ABOUT)),
            ),
        )

        assertSuccess(result)
        val admitted = database.memoryDao().memory("memory-1")
        assertNotNull(admitted)
        assertEquals(MemoryTruthState.SUPPORTED, admitted?.truthState)
        assertEquals(MemoryRetentionState.ACTIVE, admitted?.retentionState)
        assertEquals(MemoryLifecycleState.VALIDATED, admitted?.lifecycleState)
        assertEquals(SignificanceLevel.HIGH, admitted?.autobiographicalSignificance)
        assertEquals(SignificanceLevel.CORE, admitted?.identitySignificance)
        assertEquals(1, database.memoryDao().memoryEvidenceCount("memory-1"))
        assertEquals("experience-1", database.memoryDao().evidenceForMemory("memory-1").single().experienceId)
        assertEquals(1, database.memoryDao().memoryEntityLinkCount("memory-1"))
        assertEquals(MemoryAuditAction.CREATED, database.maintenanceDao().memoryAuditHistory("memory-1").single().action)
        assertNull(database.memoryDao().candidateMemory("candidate-1"))
        assertEquals(0, database.memoryDao().candidateMemoryEvidenceCount("candidate-1"))
    }

    @Test
    fun zeroEvidenceAdmissionFailsAtomicallyAndLeavesCandidateIntact() = runBlocking {
        insertCandidate("candidate-1", CandidateMemoryState.READY_FOR_VALIDATION)

        val result = service.admitCandidate(
            AdmitCandidateMemoryInput("candidate-1", "memory-1", learnedAt = 1, occurredAt = 2),
        )

        assertEquals(MemoryWriteError.CandidateHasNoEvidence("candidate-1"), assertFailure(result))
        assertNotNull(database.memoryDao().candidateMemory("candidate-1"))
        assertNull(database.memoryDao().memory("memory-1"))
    }

    @Test
    fun failedAdmissionLeavesCandidateAndCandidateEvidenceIntact() = runBlocking {
        insertCandidate("candidate-1", CandidateMemoryState.READY_FOR_VALIDATION)
        insertExperience("experience-1", 1)
        assertSuccess(
            service.addCandidateEvidence(
                candidateEvidence("candidate-1", "experience-1", 0, CandidateEvidenceRole.SEED),
            ),
        )

        val result = service.admitCandidate(
            AdmitCandidateMemoryInput(
                candidateId = "candidate-1",
                memoryId = "memory-1",
                learnedAt = 1,
                occurredAt = 2,
                entityLinks = listOf(MemoryEntityLinkInput("missing-entity", EntityLinkRole.ABOUT)),
            ),
        )

        assertEquals(MemoryWriteError.EntityNotFound("missing-entity"), assertFailure(result))
        assertNotNull(database.memoryDao().candidateMemory("candidate-1"))
        assertEquals(1, database.memoryDao().candidateMemoryEvidenceCount("candidate-1"))
        assertNull(database.memoryDao().memory("memory-1"))
    }

    @Test
    fun reinforcementAddsIndependentEvidenceWithoutCreatingDuplicateMemory() = runBlocking {
        insertExperience("experience-1", 1)
        insertExperience("experience-2", 2)
        insertExperience("experience-3", 3)
        insertExperience("experience-4", 4)
        insertCanonicalMemory("memory-1", "experience-1")
        val beforeCount = database.memoryDao().memoryCount()

        val first = service.reinforce(
            ReinforceMemoryInput(
                memoryId = "memory-1",
                evidence = ReinforcementEvidenceInput(
                    experienceId = "experience-2",
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = "lineage-2",
                ),
                confirmedAt = 20,
                occurredAt = 21,
            ),
        )

        assertSuccess(first)
        assertEquals(beforeCount, database.memoryDao().memoryCount())
        assertEquals(2, database.memoryDao().memoryEvidenceCount("memory-1"))
        assertEquals(20L, database.memoryDao().memory("memory-1")?.lastConfirmedAt)
        assertEquals(MemoryAuditAction.REINFORCED, database.maintenanceDao().memoryAuditHistory("memory-1").single().action)

        val duplicate = service.reinforce(
            ReinforceMemoryInput(
                memoryId = "memory-1",
                evidence = ReinforcementEvidenceInput(
                    experienceId = "experience-2",
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = "lineage-2-copy",
                ),
                confirmedAt = 22,
                occurredAt = 22,
            ),
        )
        assertEquals(MemoryWriteError.DuplicateEvidence("memory-1", "experience-2"), assertFailure(duplicate))
        assertEquals(2, database.memoryDao().memoryEvidenceCount("memory-1"))

        val duplicateLineage = service.reinforce(
            ReinforceMemoryInput(
                memoryId = "memory-1",
                evidence = ReinforcementEvidenceInput(
                    experienceId = "experience-3",
                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                    sourceCertainty = MemoryCertainty.CERTAIN,
                    lineageKey = "lineage-2",
                ),
                confirmedAt = 23,
                occurredAt = 23,
            ),
        )
        assertEquals(
            MemoryWriteError.DuplicateEvidenceLineage("memory-1", "lineage-2"),
            assertFailure(duplicateLineage),
        )
        assertEquals(2, database.memoryDao().memoryEvidenceCount("memory-1"))

        assertSuccess(
            service.reinforce(
                ReinforceMemoryInput(
                    memoryId = "memory-1",
                    evidence = ReinforcementEvidenceInput(
                        experienceId = "experience-4",
                        epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                        sourceCertainty = MemoryCertainty.CERTAIN,
                        lineageKey = "lineage-4",
                    ),
                    confirmedAt = 24,
                    occurredAt = 24,
                ),
            ),
        )
        assertEquals(3, database.memoryDao().memoryEvidenceCount("memory-1"))
    }

    @Test
    fun correctionCreatesReplacementAndRelationshipWhilePreservingOldProvenance() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-new", 2)
        insertCanonicalMemory("memory-old", "experience-old")
        insertMessageSource("experience-old", "message-old")
        insertOpenLoop("open-loop-old", "experience-old", "memory-old")
        insertArtifactForMemory("artifact-memory", "memory-old")
        insertArtifactForExperience("artifact-experience", "experience-old")
        insertArtifactForMessage("artifact-message", "message-old")
        insertArtifactForOpenLoop("artifact-open-loop", "open-loop-old")

        val result = service.correct(
            CorrectMemoryInput(
                inaccurateMemoryId = "memory-old",
                replacement = validatedMemory("memory-new", "experience-new", "Accurate retained meaning."),
                occurredAt = 30,
                triggeringExperienceId = "experience-new",
            ),
        )

        assertSuccess(result)
        assertEquals(MemoryTruthState.CORRECTED_FALSE, database.memoryDao().memory("memory-old")?.truthState)
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory("memory-new")?.truthState)
        assertEquals(TemporalState.CURRENT, database.memoryDao().memory("memory-new")?.temporalState)
        assertEquals(1, database.memoryDao().memoryEvidenceCount("memory-old"))
        assertEquals(1, database.memoryDao().memoryEvidenceCount("memory-new"))
        assertEquals(
            1,
            database.memoryDao().memoryRelationshipCount(
                "memory-new",
                "memory-old",
                MemoryRelationshipType.CORRECTS,
            ),
        )
        listOf("artifact-memory", "artifact-experience", "artifact-message", "artifact-open-loop").forEach { artifactId ->
            assertEquals(DerivedArtifactState.STALE, database.maintenanceDao().derivedArtifact(artifactId)?.state)
            assertEquals(30L, database.maintenanceDao().derivedArtifact(artifactId)?.invalidatedAt)
        }
        assertEquals(RepairJobState.PENDING, database.maintenanceDao().repairJobs("MEMORY", "memory-old").single().state)
        assertEquals(MemoryAuditAction.CORRECTED, database.maintenanceDao().memoryAuditHistory("memory-old").single().action)
        assertEquals(MemoryAuditAction.CREATED, database.maintenanceDao().memoryAuditHistory("memory-new").single().action)
    }

    @Test
    fun failedCorrectionLeavesOriginalMemoryUnchanged() = runBlocking {
        insertExperience("experience-old", 1)
        insertCanonicalMemory("memory-old", "experience-old")
        val original = database.memoryDao().memory("memory-old")

        val result = service.correct(
            CorrectMemoryInput(
                inaccurateMemoryId = "memory-old",
                replacement = validatedMemory("memory-new", "missing-experience", "Accurate retained meaning."),
                occurredAt = 30,
            ),
        )

        assertEquals(MemoryWriteError.ExperienceNotFound("missing-experience"), assertFailure(result))
        assertEquals(original, database.memoryDao().memory("memory-old"))
        assertNull(database.memoryDao().memory("memory-new"))
        assertEquals(0, database.memoryDao().memoryRelationshipCount("memory-new", "memory-old", MemoryRelationshipType.CORRECTS))
    }

    @Test
    fun supersessionPreservesHistoricalTruthAndCreatesCurrentReplacement() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-new", 2)
        insertCanonicalMemory("memory-old", "experience-old")

        assertSuccess(
            service.supersede(
                SupersedeMemoryInput(
                    historicalMemoryId = "memory-old",
                    replacement = validatedMemory("memory-new", "experience-new", "New current preference."),
                    occurredAt = 40,
                    triggeringExperienceId = "experience-new",
                ),
            ),
        )

        val old = database.memoryDao().memory("memory-old")
        val current = database.memoryDao().memory("memory-new")
        assertEquals(MemoryTruthState.SUPPORTED, old?.truthState)
        assertEquals(TemporalState.HISTORICAL, old?.temporalState)
        assertEquals(MemoryLifecycleState.SUPERSEDED, old?.lifecycleState)
        assertEquals(MemoryTruthState.SUPPORTED, current?.truthState)
        assertEquals(TemporalState.CURRENT, current?.temporalState)
        assertEquals(1, database.memoryDao().memoryRelationshipCount("memory-new", "memory-old", MemoryRelationshipType.SUPERSEDES))
    }

    @Test
    fun refinementCanExplicitlySupersedeBroaderMemoryWithoutMarkingItFalse() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-new", 2)
        insertCanonicalMemory("memory-old", "experience-old")

        assertSuccess(
            service.refine(
                RefineMemoryInput(
                    broaderMemoryId = "memory-old",
                    refinement = validatedMemory("memory-new", "experience-new", "Precise contextual preference."),
                    disposition = RefinementDisposition.SUPERSEDE_BROADER,
                    occurredAt = 50,
                    triggeringExperienceId = "experience-new",
                ),
            ),
        )

        val old = database.memoryDao().memory("memory-old")
        assertEquals(MemoryTruthState.SUPPORTED, old?.truthState)
        assertFalse(old?.truthState == MemoryTruthState.CORRECTED_FALSE)
        assertEquals(TemporalState.HISTORICAL, old?.temporalState)
        assertEquals(MemoryLifecycleState.SUPERSEDED, old?.lifecycleState)
        assertNotNull(database.memoryDao().memory("memory-new"))
        assertEquals(1, database.memoryDao().memoryRelationshipCount("memory-new", "memory-old", MemoryRelationshipType.REFINES))
    }

    @Test
    fun refinementCanExplicitlyKeepBroaderMemoryCurrent() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-new", 2)
        insertCanonicalMemory("memory-old", "experience-old")
        val original = database.memoryDao().memory("memory-old")!!

        assertSuccess(
            service.refine(
                RefineMemoryInput(
                    broaderMemoryId = "memory-old",
                    refinement = validatedMemory("memory-new", "experience-new", "Additional contextual preference."),
                    disposition = RefinementDisposition.KEEP_BROADER_CURRENT,
                    occurredAt = 51,
                    triggeringExperienceId = "experience-new",
                ),
            ),
        )

        assertEquals(original, database.memoryDao().memory("memory-old"))
        assertEquals(TemporalState.CURRENT, database.memoryDao().memory("memory-new")?.temporalState)
        assertEquals(1, database.memoryDao().memoryRelationshipCount("memory-new", "memory-old", MemoryRelationshipType.REFINES))
        val audit = database.maintenanceDao().memoryAuditHistory("memory-old").single()
        assertEquals(MemoryAuditAction.REFINED, audit.action)
        assertEquals(original.truthState, audit.fromTruthState)
        assertEquals(original.truthState, audit.toTruthState)
        assertEquals(original.lifecycleState, audit.fromLifecycleState)
        assertEquals(original.lifecycleState, audit.toLifecycleState)
    }

    @Test
    fun disputePreservesBothCompetingMemoriesWithoutChoosingWinner() = runBlocking {
        insertExperience("experience-1", 1)
        insertExperience("experience-2", 2)
        insertCanonicalMemory("memory-1", "experience-1")
        insertCanonicalMemory("memory-2", "experience-2")

        assertSuccess(
            service.dispute(
                DisputeMemoryInput(
                    memoryId = "memory-1",
                    competingMemoryId = "memory-2",
                    triggeringExperienceId = "experience-2",
                    occurredAt = 60,
                ),
            ),
        )

        listOf("memory-1", "memory-2").forEach { memoryId ->
            val memory = database.memoryDao().memory(memoryId)
            assertEquals(MemoryTruthState.DISPUTED, memory?.truthState)
            assertEquals(MemoryCertainty.DISPUTED, memory?.certainty)
            assertEquals(1, database.memoryDao().memoryEvidenceCount(memoryId))
            assertEquals(MemoryAuditAction.DISPUTED, database.maintenanceDao().memoryAuditHistory(memoryId).single().action)
        }
        assertEquals(1, database.memoryDao().memoryRelationshipCount("memory-1", "memory-2", MemoryRelationshipType.CONTRADICTS))
    }

    @Test
    fun dormancyAndReactivationChangeRetentionOnly() = runBlocking {
        insertExperience("experience-1", 1)
        insertCanonicalMemory("memory-1", "experience-1")
        val original = database.memoryDao().memory("memory-1")!!

        assertSuccess(service.moveDormant(MemoryStateTransitionInput("memory-1", occurredAt = 70)))
        val dormant = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.DORMANT, dormant.retentionState)
        assertEquals(original.truthState, dormant.truthState)
        assertEquals(original.certainty, dormant.certainty)
        assertEquals(original.meaning, dormant.meaning)
        assertEquals(1, database.memoryDao().memoryEvidenceCount("memory-1"))

        assertSuccess(service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 71)))
        val active = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.ACTIVE, active.retentionState)
        assertEquals(original.truthState, active.truthState)
        assertEquals(original.certainty, active.certainty)
        assertEquals(original.meaning, active.meaning)
        assertEquals(
            listOf(MemoryAuditAction.DORMANT, MemoryAuditAction.REACTIVATED),
            database.maintenanceDao().memoryAuditHistory("memory-1").map { it.action },
        )
    }

    @Test
    fun supersededHistoricalMemoryCanCycleDormancyWithoutSemanticOrProvenanceMutation() = runBlocking {
        insertExperience("experience-1", 1)
        insertCanonicalMemory("memory-1", "experience-1")
        val historical = database.memoryDao().memory("memory-1")!!.copy(
            lifecycleState = MemoryLifecycleState.SUPERSEDED,
            temporalState = TemporalState.HISTORICAL,
            validUntil = 20,
        )
        database.memoryDao().updateMemory(historical)
        val provenance = database.memoryDao().evidenceForMemory("memory-1")

        assertSuccess(service.moveDormant(MemoryStateTransitionInput("memory-1", occurredAt = 70)))
        val dormant = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.DORMANT, dormant.retentionState)
        assertEquals(historical.truthState, dormant.truthState)
        assertEquals(historical.certainty, dormant.certainty)
        assertEquals(historical.lifecycleState, dormant.lifecycleState)
        assertEquals(historical.temporalState, dormant.temporalState)
        assertEquals(historical.meaning, dormant.meaning)
        assertEquals(provenance, database.memoryDao().evidenceForMemory("memory-1"))

        assertSuccess(service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 71)))
        val active = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.ACTIVE, active.retentionState)
        assertEquals(historical.truthState, active.truthState)
        assertEquals(historical.certainty, active.certainty)
        assertEquals(historical.lifecycleState, active.lifecycleState)
        assertEquals(historical.temporalState, active.temporalState)
        assertEquals(historical.meaning, active.meaning)
        assertEquals(provenance, database.memoryDao().evidenceForMemory("memory-1"))
    }

    @Test
    fun disputedMemoryCanCycleDormancyWithoutResolvingDispute() = runBlocking {
        insertExperience("experience-1", 1)
        insertCanonicalMemory("memory-1", "experience-1")
        val disputed = database.memoryDao().memory("memory-1")!!.copy(
            truthState = MemoryTruthState.DISPUTED,
            certainty = MemoryCertainty.DISPUTED,
        )
        database.memoryDao().updateMemory(disputed)
        val provenance = database.memoryDao().evidenceForMemory("memory-1")

        assertSuccess(service.moveDormant(MemoryStateTransitionInput("memory-1", occurredAt = 72)))
        assertSuccess(service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 73)))

        val active = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.ACTIVE, active.retentionState)
        assertEquals(MemoryTruthState.DISPUTED, active.truthState)
        assertEquals(MemoryCertainty.DISPUTED, active.certainty)
        assertEquals(disputed.lifecycleState, active.lifecycleState)
        assertEquals(disputed.temporalState, active.temporalState)
        assertEquals(disputed.meaning, active.meaning)
        assertEquals(provenance, database.memoryDao().evidenceForMemory("memory-1"))
    }

    @Test
    fun correctedFalseMemoryCanCycleDormancyWithoutTruthRestoration() = runBlocking {
        insertExperience("experience-1", 1)
        insertCanonicalMemory("memory-1", "experience-1")
        database.memoryDao().updateMemory(
            database.memoryDao().memory("memory-1")!!.copy(truthState = MemoryTruthState.CORRECTED_FALSE),
        )

        assertSuccess(service.moveDormant(MemoryStateTransitionInput("memory-1", occurredAt = 74)))
        assertSuccess(service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 75)))

        val active = database.memoryDao().memory("memory-1")!!
        assertEquals(MemoryRetentionState.ACTIVE, active.retentionState)
        assertEquals(MemoryTruthState.CORRECTED_FALSE, active.truthState)
    }

    @Test
    fun illegalRetentionTransitionReturnsTypedFailureWithoutMutation() = runBlocking {
        insertExperience("experience-1", 1)
        insertCanonicalMemory("memory-1", "experience-1")

        val result = service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 71))

        assertTrue(assertFailure(result) is MemoryWriteError.IllegalStateTransition)
        assertEquals(MemoryRetentionState.ACTIVE, database.memoryDao().memory("memory-1")?.retentionState)
        assertTrue(database.maintenanceDao().memoryAuditHistory("memory-1").isEmpty())
    }

    @Test
    fun forgettingCreatesNonSemanticSuppressionAndPreservesTranscriptEvidence() = runBlocking {
        val conversation = ConversationEntity(
            id = "conversation-1",
            createdAt = 1,
            updatedAt = 1,
            status = ConversationStatus.ACTIVE,
        )
        val message = MessageEntity(
            id = "message-1",
            conversationId = conversation.id,
            sequenceNumber = 1,
            role = MessageRole.USER,
            deliveryState = MessageDeliveryState.PERSISTED,
            content = "Historical transcript content remains intact.",
            createdAt = 1,
            updatedAt = 1,
        )
        database.conversationDao().insertConversation(conversation)
        database.conversationDao().insertMessage(message)
        insertExperience("experience-1", 1)
        insertExperience("experience-2", 2)
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId = "experience-1",
                messageId = message.id,
                sourceOrder = 0,
                sourceRole = ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )
        insertCanonicalMemory("memory-1", "experience-1", meaning = "Semantic meaning must not enter tombstone.")
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                memoryId = "memory-1",
                experienceId = "experience-2",
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "lineage-experience-2",
                createdAt = 2,
            ),
        )
        insertOpenLoop("open-loop-1", "experience-1", "memory-1")
        insertArtifactForMemory("artifact-memory", "memory-1")
        insertArtifactForExperience("artifact-experience", "experience-1")
        insertArtifactForMessage("artifact-message", "message-1")
        insertArtifactForOpenLoop("artifact-open-loop", "open-loop-1")

        assertSuccess(service.forget(MemoryStateTransitionInput("memory-1", occurredAt = 80)))

        assertEquals(MemoryRetentionState.FORGOTTEN, database.memoryDao().memory("memory-1")?.retentionState)
        assertEquals(2, database.memoryDao().memoryEvidenceCount("memory-1"))
        assertEquals(1, database.memoryDao().messageSourcesForExperience("experience-1").size)
        val tombstones = database.maintenanceDao().suppressionTombstones()
        assertEquals(2, tombstones.size)
        val expectedHashes = setOf(
            sha256("experience-1:lineage-experience-1"),
            sha256("experience-2:lineage-experience-2"),
        )
        assertEquals(expectedHashes, tombstones.mapTo(mutableSetOf()) { it.sourceLineageHash })
        tombstones.forEach { tombstone ->
            assertEquals(64, tombstone.sourceLineageHash.length)
            assertEquals(SuppressionKind.FORGET, tombstone.kind)
            assertTrue(tombstone.isActive)
            assertEquals(1, tombstone.formatVersion)
            assertFalse(tombstone.sourceLineageHash.contains("Semantic", ignoreCase = true))
            assertFalse(tombstone.sourceLineageHash.contains(message.content, ignoreCase = true))
        }
        assertFalse(sha256("future-experience:future-lineage") in expectedHashes)
        listOf("artifact-memory", "artifact-experience", "artifact-message", "artifact-open-loop").forEach { artifactId ->
            assertEquals(DerivedArtifactState.STALE, database.maintenanceDao().derivedArtifact(artifactId)?.state)
            assertEquals(80L, database.maintenanceDao().derivedArtifact(artifactId)?.invalidatedAt)
        }
        assertEquals(RepairJobState.PENDING, database.maintenanceDao().repairJobs("MEMORY", "memory-1").single().state)
        assertEquals(MemoryAuditAction.FORGOTTEN, database.maintenanceDao().memoryAuditHistory("memory-1").single().action)

        val reactivation = service.reactivate(MemoryStateTransitionInput("memory-1", occurredAt = 81))
        assertTrue(assertFailure(reactivation) is MemoryWriteError.IllegalStateTransition)
        assertEquals(MemoryRetentionState.FORGOTTEN, database.memoryDao().memory("memory-1")?.retentionState)
    }

    @Test
    fun controlledMidTransactionFailureRollsBackCorrectionCompletely() = runBlocking {
        insertExperience("experience-old", 1)
        insertExperience("experience-new", 2)
        insertCanonicalMemory("memory-old", "experience-old")
        val original = database.memoryDao().memory("memory-old")
        val failingService = MemoryTransactionService(
            database = database,
            idGenerator = MemoryWriteIdGenerator { "duplicate-audit-id" },
        )

        val result = failingService.correct(
            CorrectMemoryInput(
                inaccurateMemoryId = "memory-old",
                replacement = validatedMemory("memory-new", "experience-new", "Accurate retained meaning."),
                occurredAt = 90,
                triggeringExperienceId = "experience-new",
            ),
        )

        assertTrue(assertFailure(result) is MemoryWriteError.StorageFailure)
        assertEquals(original, database.memoryDao().memory("memory-old"))
        assertNull(database.memoryDao().memory("memory-new"))
        assertEquals(0, database.memoryDao().memoryRelationshipCount("memory-new", "memory-old", MemoryRelationshipType.CORRECTS))
        assertTrue(database.maintenanceDao().memoryAuditHistory("memory-old").isEmpty())
        assertTrue(database.maintenanceDao().memoryAuditHistory("memory-new").isEmpty())
    }

    private fun insertCandidate(id: String, state: CandidateMemoryState) {
        database.memoryDao().insertCandidateMemory(
            CandidateMemoryEntity(
                id = id,
                proposedKind = MemoryKind.SEMANTIC,
                proposedScope = MemoryScope.SHAI,
                proposedMeaning = "Validated candidate meaning.",
                proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                proposedCertainty = MemoryCertainty.CERTAIN,
                state = state,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun candidateEvidence(
        candidateId: String,
        experienceId: String,
        order: Int,
        role: CandidateEvidenceRole,
    ) = CandidateEvidenceWriteInput(
        candidateId = candidateId,
        experienceId = experienceId,
        evidenceOrder = order,
        role = role,
        lineageKey = "lineage-$experienceId",
        createdAt = order.toLong() + 1,
    )

    private fun insertExperience(id: String, order: Long) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = order,
                experienceType = ExperienceType.SHARED_EVENT,
                actor = ExperienceActor.SHAI,
                sourceContent = "Grounded source event $id.",
                occurredAt = order,
                recordedAt = order,
                sensitivity = SensitivityLevel.STANDARD,
                availability = ExperienceAvailability.AVAILABLE,
            ),
        )
    }

    private fun insertCanonicalMemory(
        memoryId: String,
        experienceId: String,
        meaning: String = "Validated retained understanding.",
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

    private fun validatedMemory(
        memoryId: String,
        experienceId: String,
        meaning: String,
    ) = ValidatedMemoryInput(
        memoryId = memoryId,
        kind = MemoryKind.SEMANTIC,
        scope = MemoryScope.SHAI,
        meaning = meaning,
        epistemicBasis = EpistemicBasis.EXPLICIT_CORRECTION,
        certainty = MemoryCertainty.CERTAIN,
        learnedAt = 2,
        sensitivity = SensitivityLevel.STANDARD,
        evidence = listOf(
            MemoryEvidenceInput(
                experienceId = experienceId,
                role = EvidenceRole.SUPPORTS,
                epistemicBasis = EpistemicBasis.EXPLICIT_CORRECTION,
                sourceCertainty = MemoryCertainty.CERTAIN,
                lineageKey = "lineage-$experienceId",
            ),
        ),
    )

    private fun insertMessageSource(experienceId: String, messageId: String) {
        val conversationId = "conversation-$messageId"
        database.conversationDao().insertConversation(
            ConversationEntity(
                id = conversationId,
                createdAt = 1,
                updatedAt = 1,
                status = ConversationStatus.ACTIVE,
            ),
        )
        database.conversationDao().insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                sequenceNumber = 1,
                role = MessageRole.USER,
                deliveryState = MessageDeliveryState.PERSISTED,
                content = "Source message for $experienceId.",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
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

    private fun insertOpenLoop(openLoopId: String, experienceId: String, memoryId: String) {
        database.openLoopDao().insertOpenLoop(
            OpenLoopEntity(
                id = openLoopId,
                creationExperienceId = experienceId,
                relatedMemoryId = memoryId,
                title = "Canonical-lineage open loop",
                state = OpenLoopState.ACTIVE,
                openedAt = 1,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private fun insertArtifact(artifactId: String) {
        database.maintenanceDao().insertDerivedArtifact(
            DerivedArtifactEntity(
                id = artifactId,
                artifactType = DerivedArtifactType.SUMMARY,
                state = DerivedArtifactState.CURRENT,
                producerVersion = "test-v1",
                sourceRevision = 1,
                createdAt = 1,
            ),
        )
    }

    private fun insertArtifactForMemory(artifactId: String, memoryId: String) {
        insertArtifact(artifactId)
        database.maintenanceDao().insertDerivedArtifactMemoryDependency(
            DerivedArtifactMemoryDependencyEntity(artifactId, memoryId, 1),
        )
    }

    private fun insertArtifactForExperience(artifactId: String, experienceId: String) {
        insertArtifact(artifactId)
        database.maintenanceDao().insertDerivedArtifactExperienceDependency(
            DerivedArtifactExperienceDependencyEntity(artifactId, experienceId, 1),
        )
    }

    private fun insertArtifactForMessage(artifactId: String, messageId: String) {
        insertArtifact(artifactId)
        database.maintenanceDao().insertDerivedArtifactMessageDependency(
            DerivedArtifactMessageDependencyEntity(artifactId, messageId, 1),
        )
    }

    private fun insertArtifactForOpenLoop(artifactId: String, openLoopId: String) {
        insertArtifact(artifactId)
        database.maintenanceDao().insertDerivedArtifactOpenLoopDependency(
            DerivedArtifactOpenLoopDependencyEntity(artifactId, openLoopId, 1),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun assertSuccess(result: MemoryWriteResult): MemoryWriteResult.Success {
        assertTrue("Expected success but was $result", result is MemoryWriteResult.Success)
        return result as MemoryWriteResult.Success
    }

    private fun assertFailure(result: MemoryWriteResult): MemoryWriteError {
        assertTrue("Expected failure but was $result", result is MemoryWriteResult.Failure)
        return (result as MemoryWriteResult.Failure).error
    }
}
