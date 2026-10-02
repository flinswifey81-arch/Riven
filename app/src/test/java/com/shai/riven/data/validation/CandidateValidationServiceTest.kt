package com.shai.riven.data.validation

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.shai.riven.data.memory.IntrinsicSignificanceInput
import com.shai.riven.data.memory.MemoryEvidenceInput
import com.shai.riven.data.memory.MemoryEntityLinkInput
import com.shai.riven.data.memory.MemoryTransactionService
import com.shai.riven.data.memory.MemoryWriteResult
import com.shai.riven.data.memory.RefinementDisposition
import com.shai.riven.data.memory.ValidatedMemoryInput
import com.shai.riven.data.memory.sourceLineageHash
import com.shai.riven.data.persistence.RivenDatabase
import com.shai.riven.data.persistence.entity.CandidateMemoryEntity
import com.shai.riven.data.persistence.entity.CandidateMemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.ConversationEntity
import com.shai.riven.data.persistence.entity.ConversationTimelineHeadEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionAssessmentEntity
import com.shai.riven.data.persistence.entity.ExperienceAttentionSignalEntity
import com.shai.riven.data.persistence.entity.ExperienceEntity
import com.shai.riven.data.persistence.entity.ExperienceEntityLinkEntity
import com.shai.riven.data.persistence.entity.ExperienceMessageSourceEntity
import com.shai.riven.data.persistence.entity.KnownEntityEntity
import com.shai.riven.data.persistence.entity.MemoryEntity
import com.shai.riven.data.persistence.entity.MemoryEntityLinkEntity
import com.shai.riven.data.persistence.entity.MemoryEvidenceEntity
import com.shai.riven.data.persistence.entity.MessageEntity
import com.shai.riven.data.persistence.entity.SuppressionTombstoneEntity
import com.shai.riven.data.persistence.model.AttentionOutcome
import com.shai.riven.data.persistence.model.AttentionSignal
import com.shai.riven.data.persistence.model.AttentionSignalPolarity
import com.shai.riven.data.persistence.model.CandidateEvidenceRole
import com.shai.riven.data.persistence.model.CandidateMemoryState
import com.shai.riven.data.persistence.model.ConversationStatus
import com.shai.riven.data.persistence.model.EntityKind
import com.shai.riven.data.persistence.model.EntityLinkRole
import com.shai.riven.data.persistence.model.EpistemicBasis
import com.shai.riven.data.persistence.model.EvidenceRole
import com.shai.riven.data.persistence.model.ExperienceActor
import com.shai.riven.data.persistence.model.ExperienceAvailability
import com.shai.riven.data.persistence.model.ExperienceMessageSourceRole
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
import com.shai.riven.data.persistence.model.SuppressionKind
import com.shai.riven.data.persistence.model.TemporalState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class CandidateValidationServiceTest {
    private lateinit var database: RivenDatabase
    private lateinit var retriever: FakeRetriever
    private lateinit var decider: FakeDecider
    private lateinit var service: CandidateValidationService
    private var eventOrder = 10L
    private var diskDatabaseName: String? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RivenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        retriever = FakeRetriever()
        decider = FakeDecider(acceptDecision())
        service = newService()
        insertGroundedCandidate()
    }

    @After
    fun tearDown() {
        database.close()
        diskDatabaseName?.let { name ->
            ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
        }
    }

    @Test // 1
    fun acceptNewCreatesValidatedMemory() = runBlocking {
        val result = validate()
        assertTrue(result is CandidateValidationResult.AcceptedNew)
        val memory = memory()
        assertEquals(MemoryTruthState.SUPPORTED, memory.truthState)
        assertEquals(MemoryRetentionState.ACTIVE, memory.retentionState)
        assertEquals(MemoryLifecycleState.VALIDATED, memory.lifecycleState)
        assertNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(LINEAGE, database.memoryDao().evidenceForMemory(NEW_MEMORY).single().lineageKey)
    }

    @Test
    fun acceptNewReplayWithSameStableSourceClaimIsDiscardedBeforeModelDecision() = runBlocking {
        val admittedLineage = "AUTO_CANDIDATE_V4:0:10:${"a".repeat(64)}"
        val replayLineage = "AUTO_CANDIDATE_V4:0:10:${"b".repeat(64)}"
        database.openHelper.writableDatabase.execSQL(
            "UPDATE candidate_memory_evidence SET lineage_key = ? WHERE candidate_memory_id = ?",
            arrayOf(replayLineage, CANDIDATE),
        )
        insertTargetMemory(
            evidenceLineage = admittedLineage,
            evidenceExperienceId = SEED,
        )

        val result = validate()

        assertEquals(
            CandidateValidationResult.AlreadyAdmittedCandidateDiscarded(CANDIDATE, listOf(TARGET)),
            result,
        )
        assertNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertNull(decider.snapshot)
        assertEquals(1, database.memoryDao().memoryCount())
    }

    @Test // 2
    fun acceptNewCopiesExactCandidateMeaning() = runBlocking {
        val exact = "  exact meaning is not trimmed or rewritten  "
        updateCandidate { it.copy(proposedMeaning = exact) }
        validate()
        assertEquals(exact, memory().meaning)
    }

    @Test // 3
    fun acceptNewPreservesCandidateCertainty() = runBlocking {
        updateCandidate { it.copy(proposedCertainty = MemoryCertainty.UNCERTAIN) }
        validate()
        assertEquals(MemoryCertainty.UNCERTAIN, memory().certainty)
    }

    @Test // 4
    fun acceptNewAppliesExplicitTemporalState() = runBlocking {
        decider.decision = acceptDecision(admission(TemporalState.TIME_BOUNDED, 20, 30))
        validate()
        assertEquals(TemporalState.TIME_BOUNDED, memory().temporalState)
        assertEquals(20L, memory().validFrom)
        assertEquals(30L, memory().validUntil)
    }

    @Test // 5
    fun acceptNewAppliesFiveSignificanceDimensions() = runBlocking {
        val significance = IntrinsicSignificanceInput(
            SignificanceLevel.CORE,
            SignificanceLevel.HIGH,
            SignificanceLevel.MODERATE,
            SignificanceLevel.LOW,
            SignificanceLevel.NONE,
        )
        decider.decision = acceptDecision(admission().copy(significance = significance))
        validate()
        val stored = memory()
        assertEquals(SignificanceLevel.CORE, stored.autobiographicalSignificance)
        assertEquals(SignificanceLevel.HIGH, stored.relationshipSignificance)
        assertEquals(SignificanceLevel.MODERATE, stored.emotionalSignificance)
        assertEquals(SignificanceLevel.LOW, stored.practicalSignificance)
        assertEquals(SignificanceLevel.NONE, stored.identitySignificance)
    }

    @Test // 6
    fun acceptNewDoesNotInventEntityLinks() = runBlocking {
        validate()
        assertEquals(0, database.memoryDao().memoryEntityLinkCount(NEW_MEMORY))
    }

    @Test // 7
    fun deferToPendingContext() = runBlocking {
        decider.decision = deferDecision(CandidateMemoryState.PENDING_CONTEXT)
        val result = validate()
        assertTrue(result is CandidateValidationResult.Deferred)
        assertEquals(CandidateMemoryState.PENDING_CONTEXT, candidate().state)
    }

    @Test // 8
    fun deferToTentative() = runBlocking {
        decider.decision = deferDecision(CandidateMemoryState.TENTATIVE)
        validate()
        assertEquals(CandidateMemoryState.TENTATIVE, candidate().state)
        assertEquals(VALIDATED_AT, candidate().updatedAt)
    }

    @Test // 9
    fun rejectMovesCandidateToRejected() = runBlocking {
        decider.decision = rejectDecision()
        val result = validate()
        assertTrue(result is CandidateValidationResult.Rejected)
        assertEquals(CandidateMemoryState.REJECTED, candidate().state)
    }

    @Test // 10
    fun rejectCreatesZeroMemory() = runBlocking {
        decider.decision = rejectDecision()
        validate()
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 11
    fun tentativeCandidateCannotNormalValidate() = runBlocking {
        updateCandidate { it.copy(state = CandidateMemoryState.TENTATIVE) }
        assertTrue(error(validate()) is CandidateValidationError.InvalidCandidateState)
    }

    @Test // 12
    fun pendingContextCannotNormalValidate() = runBlocking {
        updateCandidate { it.copy(state = CandidateMemoryState.PENDING_CONTEXT) }
        assertTrue(error(validate()) is CandidateValidationError.InvalidCandidateState)
    }

    @Test // 13
    fun rejectedCandidateCannotResurrect() = runBlocking {
        updateCandidate { it.copy(state = CandidateMemoryState.REJECTED) }
        assertTrue(error(validate()) is CandidateValidationError.InvalidCandidateState)
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 14
    fun acceptedResidueFailsClosed() = runBlocking {
        updateCandidate { it.copy(state = CandidateMemoryState.ACCEPTED) }
        assertTrue(error(validate()) is CandidateValidationError.InvalidCandidateState)
    }

    @Test // 15
    fun reinforceAddsNewExperienceEvidenceToExistingMemory() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        assertTrue(validate() is CandidateValidationResult.ReinforcedExisting)
        assertEquals(setOf("existing-exp-$TARGET", SEED), database.memoryDao().evidenceForMemory(TARGET).map { it.experienceId }.toSet())
    }

    @Test // 16
    fun reinforceDoesNotCreateNewMemory() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        validate()
        assertEquals(1, database.memoryDao().memoryCount())
    }

    @Test // 17
    fun reinforcePreservesLineage() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        validate()
        assertEquals(LINEAGE, database.memoryDao().evidenceForMemory(TARGET).first { it.experienceId == SEED }.lineageKey)
    }

    @Test // 18
    fun sameExperienceCannotReinforceTwice() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        validate()
        insertCandidate("candidate-2", SEED, LINEAGE)
        val replay = validate("candidate-2")
        assertEquals(
            CandidateValidationResult.AlreadyAdmittedCandidateDiscarded("candidate-2", listOf(TARGET)),
            replay,
        )
    }

    @Test // 19
    fun sameLineageCannotFakeIndependentReinforcement() = runBlocking {
        insertTargetMemory(evidenceLineage = LINEAGE)
        reinforceDecision()
        assertTrue(error(validate()) is CandidateValidationError.NoIndependentEvidence)
    }

    @Test // 20
    fun reinforceDoesNotAutoIncreaseCertainty() = runBlocking {
        insertTargetMemory(certainty = MemoryCertainty.UNCERTAIN)
        reinforceDecision()
        validate()
        assertEquals(MemoryCertainty.UNCERTAIN, database.memoryDao().memory(TARGET)!!.certainty)
    }

    @Test // 21
    fun reinforceDoesNotAutoChangeSignificance() = runBlocking {
        insertTargetMemory(significance = SignificanceLevel.LOW)
        reinforceDecision()
        validate()
        assertEquals(SignificanceLevel.LOW, database.memoryDao().memory(TARGET)!!.identitySignificance)
    }

    @Test // 22
    fun contradictingCandidateEvidenceCannotReinforce() = runBlocking {
        insertTargetMemory()
        addCandidateEvidence("contradiction", CandidateEvidenceRole.CONTRADICTING, "lineage-c")
        reinforceDecision()
        assertTrue(error(validate()) is CandidateValidationError.InvalidValidationDecision)
    }

    @Test // 23
    fun candidateConsumedOnlyAfterSuccessfulReinforcement() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_reinforce BEFORE INSERT ON memory_evidence BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )
        assertTrue(error(validate()) is CandidateValidationError.TransactionFailure)
        assertNotNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(1, database.memoryDao().memoryEvidenceCount(TARGET))
    }

    @Test // 24
    fun refineCreatesRefinesRelationship() = runBlocking {
        insertTargetMemory()
        refinementDecision(RefinementDisposition.KEEP_BROADER_CURRENT)
        validate()
        assertEquals(1, relationshipCount(NEW_MEMORY, TARGET, MemoryRelationshipType.REFINES))
    }

    @Test // 25
    fun refineKeepBroaderCurrent() = runBlocking {
        insertTargetMemory()
        refinementDecision(RefinementDisposition.KEEP_BROADER_CURRENT)
        validate()
        val old = database.memoryDao().memory(TARGET)!!
        assertEquals(MemoryLifecycleState.VALIDATED, old.lifecycleState)
        assertEquals(TemporalState.CURRENT, old.temporalState)
    }

    @Test // 26
    fun refineSupersedeBroader() = runBlocking {
        insertTargetMemory()
        refinementDecision(RefinementDisposition.SUPERSEDE_BROADER)
        validate()
        val old = database.memoryDao().memory(TARGET)!!
        assertEquals(MemoryLifecycleState.SUPERSEDED, old.lifecycleState)
        assertEquals(TemporalState.HISTORICAL, old.temporalState)
    }

    @Test // 27
    fun supersedeMakesOldMemoryHistorical() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.SUPERSEDE_EXISTING)
        validate()
        assertEquals(TemporalState.HISTORICAL, database.memoryDao().memory(TARGET)!!.temporalState)
    }

    @Test // 28
    fun supersededOldMemoryRemainsSupportedHistoricalTruth() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.SUPERSEDE_EXISTING)
        validate()
        val old = database.memoryDao().memory(TARGET)!!
        assertEquals(MemoryTruthState.SUPPORTED, old.truthState)
        assertEquals(MemoryLifecycleState.SUPERSEDED, old.lifecycleState)
    }

    @Test // 29
    fun supersedeCreatesSupersedesRelationship() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.SUPERSEDE_EXISTING)
        validate()
        assertEquals(1, relationshipCount(NEW_MEMORY, TARGET, MemoryRelationshipType.SUPERSEDES))
    }

    @Test // 30
    fun correctMarksOldMemoryCorrectedFalse() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.CORRECT_EXISTING)
        validate()
        assertEquals(MemoryTruthState.CORRECTED_FALSE, database.memoryDao().memory(TARGET)!!.truthState)
    }

    @Test // 31
    fun correctCreatesCorrectsRelationship() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.CORRECT_EXISTING)
        validate()
        assertEquals(1, relationshipCount(NEW_MEMORY, TARGET, MemoryRelationshipType.CORRECTS))
    }

    @Test // 32
    fun correctedOldMemoryIsNotDeleted() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.CORRECT_EXISTING)
        validate()
        assertNotNull(database.memoryDao().memory(TARGET))
    }

    @Test // 33
    fun correctionPreservesHistoricalTemporalStateWhenValidated() = runBlocking {
        insertTargetMemory()
        mutationDecision(
            CandidateValidationOutcome.CORRECT_EXISTING,
            admission(TemporalState.HISTORICAL, 1, 2),
        )
        validate()
        val replacement = memory()
        assertEquals(TemporalState.HISTORICAL, replacement.temporalState)
        assertEquals(1L, replacement.validFrom)
        assertEquals(2L, replacement.validUntil)
    }

    @Test // 34
    fun supersedeStillForcesReplacementCurrent() = runBlocking {
        insertTargetMemory()
        mutationDecision(
            CandidateValidationOutcome.SUPERSEDE_EXISTING,
            admission(TemporalState.HISTORICAL, 1, 2),
        )
        validate()
        assertEquals(TemporalState.CURRENT, memory().temporalState)
        assertNull(memory().validUntil)
    }

    @Test // 35
    fun candidateConsumedAfterSuccessfulCanonicalMutation() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.CORRECT_EXISTING)
        validate()
        assertNull(database.memoryDao().candidateMemory(CANDIDATE))
    }

    @Test // 36
    fun disputeAdmitsCompetingCandidateMemory() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        val result = validate()
        assertTrue(result is CandidateValidationResult.Disputed)
        assertNotNull(database.memoryDao().memory(NEW_MEMORY))
    }

    @Test // 37
    fun disputeMarksBothMemoriesDisputed() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertTrue(listOf(TARGET, NEW_MEMORY).all { database.memoryDao().memory(it)!!.truthState == MemoryTruthState.DISPUTED })
    }

    @Test // 38
    fun disputeMarksBothCertaintiesDisputed() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertTrue(listOf(TARGET, NEW_MEMORY).all { database.memoryDao().memory(it)!!.certainty == MemoryCertainty.DISPUTED })
    }

    @Test // 39
    fun disputeCreatesOneDirectionContradictsRelationship() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertEquals(1, relationshipCount(TARGET, NEW_MEMORY, MemoryRelationshipType.CONTRADICTS))
        assertEquals(0, relationshipCount(NEW_MEMORY, TARGET, MemoryRelationshipType.CONTRADICTS))
    }

    @Test // 40
    fun disputePreservesBothProvenances() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertEquals("existing-lineage", database.memoryDao().evidenceForMemory(TARGET).single().lineageKey)
        assertEquals(LINEAGE, database.memoryDao().evidenceForMemory(NEW_MEMORY).single().lineageKey)
    }

    @Test // 41
    fun disputeSelectsNoArbitraryWinner() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertFalse(listOf(TARGET, NEW_MEMORY).any { database.memoryDao().memory(it)!!.truthState == MemoryTruthState.SUPPORTED })
    }

    @Test // 42
    fun disputeConsumesCandidateAtomically() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        validate()
        assertNull(database.memoryDao().candidateMemory(CANDIDATE))
    }

    @Test // 43
    fun controlledDisputeFailureRollsBackAdmissionAndDisputeState() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.DISPUTE_EXISTING)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_contradiction BEFORE INSERT ON memory_relationships BEGIN SELECT RAISE(ABORT, 'forced'); END",
        )
        assertTrue(error(validate()) is CandidateValidationError.TransactionFailure)
        assertNull(database.memoryDao().memory(NEW_MEMORY))
        assertEquals(MemoryTruthState.SUPPORTED, database.memoryDao().memory(TARGET)!!.truthState)
        assertNotNull(database.memoryDao().candidateMemory(CANDIDATE))
    }

    @Test // 44
    fun mergeDoesNotMutateExistingMemories() = runBlocking {
        insertTargetMemory(TARGET)
        insertTargetMemory("target-2")
        mergeDecision()
        val before = listOf(database.memoryDao().memory(TARGET), database.memoryDao().memory("target-2"))
        validate()
        assertEquals(before, listOf(database.memoryDao().memory(TARGET), database.memoryDao().memory("target-2")))
    }

    @Test // 45
    fun mergeSetsCandidateTentative() = runBlocking {
        insertTargetMemory(TARGET)
        insertTargetMemory("target-2")
        mergeDecision()
        validate()
        assertEquals(CandidateMemoryState.TENTATIVE, candidate().state)
    }

    @Test // 46
    fun mergeReturnsDeferredForConsolidationResult() = runBlocking {
        insertTargetMemory(TARGET)
        insertTargetMemory("target-2")
        mergeDecision()
        val result = validate() as CandidateValidationResult.MergeDeferredForConsolidation
        assertEquals(listOf(TARGET, "target-2"), result.relatedMemoryIds)
    }

    @Test // 47
    fun mergeCreatesNoFakeRefinesOrSupersedesRelationship() = runBlocking {
        insertTargetMemory(TARGET)
        insertTargetMemory("target-2")
        mergeDecision()
        validate()
        assertEquals(0L, rowCount("memory_relationships"))
    }

    @Test // 48
    fun inactiveSeedBranchCannotValidate() = runBlocking {
        insertConversationCandidate("candidate-conv", "conv-seed", active = false)
        assertTrue(error(validate("candidate-conv")) is CandidateValidationError.InactiveConversationEvidence)
    }

    @Test // 49
    fun inactiveSupportingEvidenceCannotValidate() = runBlocking {
        insertConversationCandidate("unused", "conv-support", active = false, insertCandidate = false)
        addCandidateEvidence("conv-support", CandidateEvidenceRole.SUPPORTING, "conv-lineage")
        assertTrue(error(validate()) is CandidateValidationError.InactiveConversationEvidence)
    }

    @Test // 50
    fun deletedExperienceCannotValidate() = runBlocking {
        insertUnavailableCandidate()
        assertTrue(error(validate("candidate-deleted")) is CandidateValidationError.ExperienceUnavailable)
    }

    @Test // 51
    fun corruptSourceProvenanceCannotValidate() = runBlocking {
        insertExperience("corrupt", type = ExperienceType.CONVERSATION_MESSAGE)
        insertAttention("corrupt")
        insertCandidate("candidate-corrupt", "corrupt", "corrupt-lineage")
        assertTrue(error(validate("candidate-corrupt")) is CandidateValidationError.InvalidConversationProvenance)
    }

    @Test // 52
    fun seedAttentionNoLongerForwardCannotValidate() = runBlocking {
        val current = database.experienceAttentionDao().assessment(SEED)!!
        database.experienceAttentionDao().updateAssessment(current.copy(outcome = AttentionOutcome.NO_CANDIDATE, revision = 2))
        assertTrue(error(validate()) is CandidateValidationError.AttentionNotForward)
    }

    @Test // 53
    fun candidateChangedDuringRetrieverIsStale() = runBlocking {
        retriever.onRetrieve = { updateCandidate { it.copy(updatedAt = it.updatedAt + 1) } }
        assertTrue(error(validate()) is CandidateValidationError.StaleValidationContext)
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 54
    fun candidateChangedDuringValidatorIsStale() = runBlocking {
        decider.onDecide = { updateCandidate { it.copy(proposedMeaning = "changed") } }
        assertTrue(error(validate()) is CandidateValidationError.StaleValidationContext)
    }

    @Test // 55
    fun evidenceSetChangedDuringValidatorIsStale() = runBlocking {
        decider.onDecide = {
            addCandidateEvidence("late-support", CandidateEvidenceRole.SUPPORTING, "late-lineage")
        }
        assertTrue(error(validate()) is CandidateValidationError.StaleValidationContext)
    }

    @Test // 56
    fun targetMemoryChangedDuringValidatorIsStale() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        decider.onDecide = {
            val target = database.memoryDao().memory(TARGET)!!
            database.memoryDao().updateMemory(target.copy(updatedAt = target.updatedAt + 1))
        }
        assertTrue(error(validate()) is CandidateValidationError.StaleValidationContext)
    }

    @Test // 57
    fun targetMemoryDeletedDuringValidatorIsStale() = runBlocking {
        insertTargetMemory()
        reinforceDecision()
        decider.onDecide = { database.memoryDao().deleteMemory(database.memoryDao().memory(TARGET)!!) }
        assertTrue(error(validate()) is CandidateValidationError.StaleValidationContext)
    }

    @Test // 58
    fun rewindDuringValidatorIsStaleWithoutMutation() = runBlocking {
        insertConversationCandidate("candidate-conv", "conv-seed", active = true)
        decider.onDecide = { moveConversationHead(null) }
        assertTrue(error(validate("candidate-conv")) is CandidateValidationError.StaleValidationContext)
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 59
    fun regenerateDuringValidatorIsStaleWithoutMutation() = runBlocking {
        insertConversationCandidate("candidate-conv", "conv-seed", active = true)
        decider.onDecide = { moveConversationHead("alternate-head") }
        assertTrue(error(validate("candidate-conv")) is CandidateValidationError.StaleValidationContext)
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 60
    fun forgetTombstoneCreatedAfterExtractionBlocksAdmission() = runBlocking {
        insertSuppression(SuppressionKind.FORGET)
        assertTrue(validate() is CandidateValidationResult.SuppressedCandidateDiscarded)
    }

    @Test // 61
    fun deleteTombstoneCreatedAfterExtractionBlocksAdmission() = runBlocking {
        insertSuppression(SuppressionKind.DELETE)
        assertTrue(validate() is CandidateValidationResult.SuppressedCandidateDiscarded)
    }

    @Test // 62
    fun suppressedCandidateIsDiscarded() = runBlocking {
        insertSuppression(SuppressionKind.FORGET)
        validate()
        assertNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(0, database.memoryDao().candidateMemoryEvidenceCount(CANDIDATE))
    }

    @Test // 63
    fun noMemoryCreatedFromSuppressedCandidate() = runBlocking {
        insertSuppression(SuppressionKind.DELETE)
        validate()
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 64
    fun suppressionTombstoneRemainsUnchanged() = runBlocking {
        insertSuppression(SuppressionKind.FORGET)
        val before = database.maintenanceDao().suppressionTombstone(sourceLineageHash(SEED, LINEAGE))
        validate()
        assertEquals(before, database.maintenanceDao().suppressionTombstone(sourceLineageHash(SEED, LINEAGE)))
    }

    @Test // 65
    fun inactiveTombstoneDoesNotBlock() = runBlocking {
        insertSuppression(SuppressionKind.FORGET, active = false)
        assertTrue(validate() is CandidateValidationResult.AcceptedNew)
    }

    @Test // 66
    fun retrieverReturnsIdsOnlyAndCanonicalContentIsHydratedFromRoom() = runBlocking {
        insertTargetMemory(meaning = "canonical-room-meaning")
        retriever.ids = listOf(TARGET)
        decider.decision = rejectDecision()
        validate()
        assertEquals("canonical-room-meaning", decider.snapshot!!.relatedMemories.single().meaning)
    }

    @Test // 67
    fun duplicateRetrieverIdsAreRejected() = runBlocking {
        retriever.ids = listOf("same", "same")
        val failure = error(validate()) as CandidateValidationError.InvalidRetrieverResult
        assertEquals(InvalidRetrieverResultReason.DUPLICATE_MEMORY_ID, failure.reason)
    }

    @Test // 68
    fun tooManyRelatedMemoriesAreRejected() = runBlocking {
        retriever.ids = (0..MAX_VALIDATION_RELATED_MEMORIES).map { "memory-$it" }
        assertTrue(error(validate()) is CandidateValidationError.TooManyRelatedMemories)
    }

    @Test // 69
    fun missingReturnedMemoryIsRejected() = runBlocking {
        retriever.ids = listOf("missing")
        assertEquals(CandidateValidationError.MissingRelatedMemory("missing"), error(validate()))
    }

    @Test // 70
    fun deciderCannotTargetMemoryNotReturnedByRetriever() = runBlocking {
        insertTargetMemory()
        retriever.ids = emptyList()
        decider.decision = CandidateValidationDecision(CandidateValidationOutcome.REINFORCE_EXISTING, listOf(TARGET))
        assertEquals(CandidateValidationError.TargetNotRetrieved(TARGET), error(validate()))
    }

    @Test // 71
    fun validationSnapshotIncludesCorrectMemoryProvenance() = runBlocking {
        insertTargetMemory()
        retriever.ids = listOf(TARGET)
        decider.decision = rejectDecision()
        validate()
        val evidence = decider.snapshot!!.relatedMemories.single().evidence.single()
        assertEquals("existing-exp-$TARGET", evidence.experienceId)
        assertEquals("existing-lineage", evidence.lineageKey)
        assertEquals(EvidenceRole.SUPPORTS, evidence.role)
    }

    @Test // 72
    fun forgottenCorrectedAndSupersededMemoriesMayBeCompared() = runBlocking {
        insertTargetMemory(TARGET, retention = MemoryRetentionState.FORGOTTEN)
        insertTargetMemory("corrected", truth = MemoryTruthState.CORRECTED_FALSE)
        insertTargetMemory("superseded", lifecycle = MemoryLifecycleState.SUPERSEDED, temporal = TemporalState.HISTORICAL)
        retriever.ids = listOf(TARGET, "corrected", "superseded")
        decider.decision = rejectDecision()
        assertTrue(validate() is CandidateValidationResult.Rejected)
        assertEquals(3, decider.snapshot!!.relatedMemories.size)
    }

    @Test // 73
    fun temporaryStateAsIdentityCanBeRejectedWithoutMemory() = runBlocking {
        decider.decision = rejectDecision(CandidateRejectReason.TEMPORARY_STATE_AS_IDENTITY)
        validate()
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 74
    fun relationshipMilestoneCandidateCanBeDeferred() = runBlocking {
        updateCandidate { it.copy(proposedKind = MemoryKind.RELATIONSHIP) }
        decider.decision = deferDecision(CandidateMemoryState.PENDING_CONTEXT)
        assertTrue(validate() is CandidateValidationResult.Deferred)
    }

    @Test // 75
    fun rivenSelfDevelopmentCandidateCanRemainTentative() = runBlocking {
        updateCandidate { it.copy(proposedKind = MemoryKind.SELF_DEVELOPMENT, proposedScope = MemoryScope.RIVEN) }
        decider.decision = deferDecision(CandidateMemoryState.TENTATIVE)
        validate()
        assertEquals(CandidateMemoryState.TENTATIVE, candidate().state)
    }

    @Test // 76
    fun sensitiveMemoryCannotDowngradeSourceSensitivity() = runBlocking {
        replaceSeedSensitivity(SensitivityLevel.HIGHLY_SENSITIVE)
        decider.decision = acceptDecision(admission().copy(sensitivity = SensitivityLevel.STANDARD))
        assertTrue(error(validate()) is CandidateValidationError.InvalidValidationDecision)
    }

    @Test // 77
    fun validationDoesNotInventMotiveText() = runBlocking {
        val exact = "Shai said only this; no motive is established."
        updateCandidate { it.copy(proposedMeaning = exact) }
        validate()
        assertEquals(exact, memory().meaning)
    }

    @Test // 78
    fun validationDoesNotModifyTranscript() = runBlocking {
        val before = rowCount("messages")
        validate()
        assertEquals(before, rowCount("messages"))
    }

    @Test // 79
    fun validationDoesNotModifyAttention() = runBlocking {
        val before = database.experienceAttentionDao().assessment(SEED)
        validate()
        assertEquals(before, database.experienceAttentionDao().assessment(SEED))
    }

    @Test // 80
    fun validationDoesNotModifyDraft() = runBlocking {
        val before = rowCount("conversation_drafts")
        validate()
        assertEquals(before, rowCount("conversation_drafts"))
    }

    @Test // 81
    fun validationDoesNotModifySystemInstructions() = runBlocking {
        val before = rowCount("shai_system_instructions")
        validate()
        assertEquals(before, rowCount("shai_system_instructions"))
    }

    @Test // 82
    fun validationDoesNotModifyProviderProfile() = runBlocking {
        val before = rowCount("provider_profiles")
        validate()
        assertEquals(before, rowCount("provider_profiles"))
    }

    @Test // 83
    fun validationDoesNotCreateOpenLoop() = runBlocking {
        validate()
        assertEquals(0L, rowCount("open_loops"))
        assertEquals(0L, rowCount("open_loop_entity_links"))
        assertEquals(0L, rowCount("open_loop_audit_history"))
    }

    @Test // 84
    fun acceptDoesNotCreateDerivedRepresentation() = runBlocking {
        validate()
        assertEquals(0L, rowCount("derived_artifacts"))
    }

    @Test // 85
    fun acceptNewDoesNotCreateRepairJob() = runBlocking {
        validate()
        assertEquals(0L, rowCount("repair_jobs"))
    }

    @Test // 86
    fun canonicalMutationsRetainExistingRepairAndInvalidationBehavior() = runBlocking {
        insertTargetMemory()
        mutationDecision(CandidateValidationOutcome.CORRECT_EXISTING)
        validate()
        assertTrue(database.maintenanceDao().repairJobs("MEMORY", TARGET).isNotEmpty())
    }

    @Test // 87
    fun retrieverFailureIsTypedAndDoesNotMutateState() = runBlocking {
        retriever.onRetrieve = { error("retriever raw detail") }
        val failure = error(validate()) as CandidateValidationError.RetrieverFailure
        assertEquals("IllegalStateException", failure.causeType)
        assertNotNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 88
    fun validatorFailureIsTypedAndDoesNotMutateState() = runBlocking {
        decider.onDecide = { error("validator raw detail") }
        val failure = error(validate()) as CandidateValidationError.ValidatorFailure
        assertEquals("IllegalStateException", failure.causeType)
        assertNotNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 89
    fun retrieverCancellationPropagates() = runBlocking {
        retriever.onRetrieve = { throw CancellationException("cancel") }
        var propagated = false
        try {
            validate()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test // 90
    fun validatorCancellationPropagates() = runBlocking {
        decider.onDecide = { throw CancellationException("cancel") }
        var propagated = false
        try {
            validate()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test // 91
    fun blankRetrieverMemoryIdIsRejected() = runBlocking {
        retriever.ids = listOf(" ")
        val failure = error(validate()) as CandidateValidationError.InvalidRetrieverResult
        assertEquals(InvalidRetrieverResultReason.BLANK_MEMORY_ID, failure.reason)
    }

    @Test // 92
    fun invalidGeneratedMemoryIdFailsWithoutAdmission() = runBlocking {
        service = CandidateValidationService(database, retriever, decider, ValidatedMemoryIdGenerator { " " })
        assertTrue(error(validate()) is CandidateValidationError.MemoryIdGenerationFailure)
        assertNotNull(database.memoryDao().candidateMemory(CANDIDATE))
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test // 93
    fun invalidTemporalMetadataIsRejected() = runBlocking {
        decider.decision = acceptDecision(admission(TemporalState.TIME_BOUNDED, 20, null))
        val failure = error(validate()) as CandidateValidationError.InvalidValidationDecision
        assertEquals(InvalidValidationDecisionReason.INVALID_TEMPORAL_METADATA, failure.reason)
    }

    @Test // 94
    fun targetedOutcomeRequiresMatchingScope() = runBlocking {
        insertTargetMemory()
        val target = database.memoryDao().memory(TARGET)!!
        database.memoryDao().updateMemory(target.copy(scope = MemoryScope.RIVEN))
        reinforceDecision()
        assertEquals(CandidateValidationError.InvalidTargetMemory(TARGET), error(validate()))
    }

    @Test // 95
    fun reinforcementRequiresMatchingKind() = runBlocking {
        insertTargetMemory()
        val target = database.memoryDao().memory(TARGET)!!
        database.memoryDao().updateMemory(target.copy(kind = MemoryKind.RELATIONSHIP))
        reinforceDecision()
        assertEquals(CandidateValidationError.InvalidTargetMemory(TARGET), error(validate()))
    }

    @Test // 96
    fun refineKindChangeRequiresBoundedClassificationReason() = runBlocking {
        insertTargetMemory()
        val target = database.memoryDao().memory(TARGET)!!
        database.memoryDao().updateMemory(target.copy(kind = MemoryKind.RELATIONSHIP))
        refinementDecision(RefinementDisposition.KEEP_BROADER_CURRENT)
        val failure = error(validate()) as CandidateValidationError.InvalidValidationDecision
        assertEquals(InvalidValidationDecisionReason.INVALID_CLASSIFICATION_CHANGE, failure.reason)
    }

    @Test // 97
    fun refineKindChangeAllowsExplicitBoundedClassificationReason() = runBlocking {
        insertTargetMemory()
        val target = database.memoryDao().memory(TARGET)!!
        database.memoryDao().updateMemory(target.copy(kind = MemoryKind.RELATIONSHIP))
        retriever.ids = listOf(TARGET)
        decider.decision = CandidateValidationDecision(
            CandidateValidationOutcome.REFINE_EXISTING,
            targetMemoryIds = listOf(TARGET),
            admission = admission(),
            refinementDisposition = RefinementDisposition.KEEP_BROADER_CURRENT,
            classificationChangeReason = ClassificationChangeReason.IMPRECISE_CLASSIFICATION,
        )
        assertTrue(validate() is CandidateValidationResult.Refined)
    }

    @Test // 98
    fun omittedSensitivityUsesStrongestSourceFloor() = runBlocking {
        replaceSeedSensitivity(SensitivityLevel.HIGHLY_SENSITIVE)
        validate()
        assertEquals(SensitivityLevel.HIGHLY_SENSITIVE, memory().sensitivity)
    }

    @Test // 99
    fun groundedEntityLinkMayBeAdmitted() = runBlocking {
        database.memoryDao().insertEntity(
            KnownEntityEntity("entity", EntityKind.SUBJECT, "Rain", "rain", 1, 1),
        )
        database.memoryDao().insertExperienceEntityLink(
            ExperienceEntityLinkEntity(SEED, "entity", EntityLinkRole.ABOUT, 1),
        )
        decider.decision = acceptDecision(
            admission().copy(entityLinks = listOf(MemoryEntityLinkInput("entity", EntityLinkRole.ABOUT))),
        )
        validate()
        assertEquals(1, database.memoryDao().memoryEntityLinkCount(NEW_MEMORY))
    }

    @Test // 100
    fun ungroundedEntityLinkIsRejected() = runBlocking {
        decider.decision = acceptDecision(
            admission().copy(entityLinks = listOf(MemoryEntityLinkInput("invented", EntityLinkRole.ABOUT))),
        )
        val failure = error(validate()) as CandidateValidationError.InvalidValidationDecision
        assertEquals(InvalidValidationDecisionReason.UNGROUNDED_ENTITY, failure.reason)
    }

    @Test
    fun unavailableRecallCannotAuthorizeAcceptNew() = runBlocking {
        retriever.readiness = ValidationRecallReadiness.NOT_READY

        val result = validate()

        val failure = error(result) as CandidateValidationError.ValidationRecallUnavailable
        assertEquals(ValidationRecallReadiness.NOT_READY, failure.readiness)
        assertEquals(CandidateMemoryState.READY_FOR_VALIDATION, candidate().state)
        assertNull(decider.snapshot)
        assertEquals(0, database.memoryDao().memoryCount())
    }

    @Test
    fun memoryInsertedDuringDecisionMakesCorpusGenerationStale() = runBlocking {
        decider.onDecide = {
            database.validationRecallCorpusFence().withCanonicalMutation(
                change = { ValidationRecallCorpusChange.memoryIds(setOf("raced-memory")) },
            ) { mutation ->
                database.withTransaction {
                    val write = MemoryTransactionService(database).createValidatedInCurrentTransaction(
                        ValidatedMemoryInput(
                            memoryId = "raced-memory",
                            kind = MemoryKind.SEMANTIC,
                            scope = MemoryScope.SHAI,
                            meaning = "Shai likes rainy afternoons.",
                            epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                            certainty = MemoryCertainty.PROBABLE,
                            learnedAt = 400,
                            sensitivity = SensitivityLevel.STANDARD,
                            evidence = listOf(
                                MemoryEvidenceInput(
                                    experienceId = SEED,
                                    role = EvidenceRole.SUPPORTS,
                                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                                    sourceCertainty = MemoryCertainty.PROBABLE,
                                    lineageKey = "race-lineage",
                                ),
                            ),
                        ),
                        occurredAt = 400,
                        mutation = mutation,
                    )
                    assertTrue(write is MemoryWriteResult.Success)
                }
            }
        }

        val result = validate()

        assertTrue(error(result) is CandidateValidationError.StaleValidationRecall)
        assertEquals(CandidateMemoryState.READY_FOR_VALIDATION, candidate().state)
        assertNotNull(database.memoryDao().memory("raced-memory"))
        assertNull(database.memoryDao().memory(NEW_MEMORY))
    }

    @Test
    fun diskWalUncommittedWriterCannotMintReceiptThatAuthorizesDuplicateAdmission() = runBlocking {
        replaceWithDiskWalDatabase()
        val fence = database.validationRecallCorpusFence()
        val oldGeneration = fence.snapshot()
        insertExperience("writer-evidence", sourceContent = "Shai likes rainy afternoons.")
        val recall = TargetedValidationMemoryRetriever(database, Dispatchers.IO)
        val writerPaused = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            fence.withCanonicalMutation(
                change = { ValidationRecallCorpusChange.memoryIds(setOf("writer-memory")) },
            ) { mutation ->
                database.withTransaction {
                    val write = MemoryTransactionService(database).createValidatedInCurrentTransaction(
                        ValidatedMemoryInput(
                            memoryId = "writer-memory",
                            kind = MemoryKind.SEMANTIC,
                            scope = MemoryScope.SHAI,
                            meaning = "Shai likes rainy afternoons.",
                            epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                            certainty = MemoryCertainty.CERTAIN,
                            learnedAt = 450,
                            sensitivity = SensitivityLevel.STANDARD,
                            evidence = listOf(
                                MemoryEvidenceInput(
                                    experienceId = "writer-evidence",
                                    role = EvidenceRole.SUPPORTS,
                                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                                    sourceCertainty = MemoryCertainty.CERTAIN,
                                    lineageKey = "writer-lineage",
                                ),
                            ),
                        ),
                        occurredAt = 450,
                        mutation = mutation,
                    )
                    assertTrue(write is MemoryWriteResult.Success)
                    writerPaused.complete(Unit)
                    releaseWriter.await()
                }
            }
        }
        try {
            withTimeout(10_000) { writerPaused.await() }
            val duringWrite = withTimeout(5_000) {
                recall.retrieve(
                    ValidationMemoryQuery(
                        candidateId = CANDIDATE,
                        proposedMeaning = "Shai likes rainy afternoons.",
                        proposedKind = MemoryKind.SEMANTIC,
                        proposedScope = MemoryScope.SHAI,
                        proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                        proposedCertainty = MemoryCertainty.PROBABLE,
                        sensitivity = SensitivityLevel.STANDARD,
                        groundedEntityIds = emptySet(),
                        sourceExperienceIds = listOf(SEED),
                        seedAttention = ValidationAttentionSignals(
                            AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                            1,
                            emptySet(),
                            emptySet(),
                        ),
                    ),
                )
            }
            assertEquals(ValidationRecallReadiness.STALE, duringWrite.readiness)
        } finally {
            releaseWriter.complete(Unit)
            withTimeout(10_000) { writer.await() }
        }

        retriever.generation = oldGeneration
        retriever.ids = emptyList()
        val admission = validate()

        assertTrue(error(admission) is CandidateValidationError.StaleValidationRecall)
        assertEquals(CandidateMemoryState.READY_FOR_VALIDATION, candidate().state)
        assertNotNull(database.memoryDao().memory("writer-memory"))
        assertNull(database.memoryDao().memory(NEW_MEMORY))
        recall.close()
    }

    @Test
    fun cancellationAfterWalCommitBeforeCallerResumeRejectsTheOldReceipt() = runBlocking {
        replaceWithDiskWalDatabase()
        val fence = database.validationRecallCorpusFence()
        insertExperience("cancel-evidence", sourceContent = "cancellation commit marker")
        val recall = TargetedValidationMemoryRetriever(database, Dispatchers.IO)
        val query = ValidationMemoryQuery(
            candidateId = CANDIDATE,
            proposedMeaning = "cancellation commit marker",
            proposedKind = MemoryKind.SEMANTIC,
            proposedScope = MemoryScope.SHAI,
            proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
            proposedCertainty = MemoryCertainty.CERTAIN,
            sensitivity = SensitivityLevel.STANDARD,
            groundedEntityIds = emptySet(),
            sourceExperienceIds = emptyList(),
            seedAttention = ValidationAttentionSignals(
                AttentionOutcome.FORWARD_FOR_INTERPRETATION,
                1,
                emptySet(),
                emptySet(),
            ),
        )
        val oldReceipt = recall.retrieve(query)
        assertEquals(ValidationRecallReadiness.READY, oldReceipt.readiness)
        val oldGeneration = requireNotNull(oldReceipt.generation)
        val callerDispatcher = PausingDispatcher()
        val transactionBodyReady = CompletableDeferred<Unit>()
        val allowTransactionToFinish = CompletableDeferred<Unit>()
        val writer = async(callerDispatcher) {
            fence.withCanonicalMutation(
                change = { ValidationRecallCorpusChange.memoryIds(setOf("cancel-committed-memory")) },
            ) { mutation ->
                database.withTransaction {
                    val result = MemoryTransactionService(database).createValidatedInCurrentTransaction(
                        ValidatedMemoryInput(
                            memoryId = "cancel-committed-memory",
                            kind = MemoryKind.SEMANTIC,
                            scope = MemoryScope.SHAI,
                            meaning = "cancellation commit marker",
                            epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                            certainty = MemoryCertainty.CERTAIN,
                            learnedAt = 460,
                            sensitivity = SensitivityLevel.STANDARD,
                            evidence = listOf(
                                MemoryEvidenceInput(
                                    experienceId = "cancel-evidence",
                                    role = EvidenceRole.SUPPORTS,
                                    epistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                                    sourceCertainty = MemoryCertainty.CERTAIN,
                                    lineageKey = "cancel-lineage",
                                ),
                            ),
                        ),
                        occurredAt = 460,
                        mutation = mutation,
                    )
                    assertTrue(result is MemoryWriteResult.Success)
                    transactionBodyReady.complete(Unit)
                    allowTransactionToFinish.await()
                }
            }
        }
        try {
            withTimeout(10_000) { transactionBodyReady.await() }
            callerDispatcher.pause()
            allowTransactionToFinish.complete(Unit)
            withTimeout(10_000) {
                while (database.memoryDao().memory("cancel-committed-memory") == null ||
                    callerDispatcher.queuedTaskCount == 0
                ) {
                    delay(10)
                }
            }

            writer.cancel(CancellationException("cancel after Room commit"))

            assertTrue(fence.isMutationInFlight())
            assertFalse(fence.matches(oldGeneration))
            assertEquals(ValidationRecallReadiness.STALE, recall.retrieve(query).readiness)

            callerDispatcher.resumeQueued()
            withTimeout(10_000) { writer.join() }

            assertTrue(writer.isCancelled)
            assertFalse(fence.isMutationInFlight())
            assertFalse(fence.matches(oldGeneration))
            assertEquals(oldGeneration.corpusGeneration + 1L, fence.snapshot().corpusGeneration)
            assertNotNull(database.memoryDao().memory("cancel-committed-memory"))
            val after = recall.retrieve(query)
            assertEquals(ValidationRecallReadiness.READY, after.readiness)
            assertTrue("cancel-committed-memory" in after.memoryIds)
        } finally {
            allowTransactionToFinish.complete(Unit)
            callerDispatcher.resumeQueued()
            withTimeout(10_000) { writer.join() }
            recall.close()
        }
    }

    @Test
    fun cancellationWhileWalTransactionIsOpenKeepsFenceBusyUntilOutcomeSettles() = runBlocking {
        replaceWithDiskWalDatabase()
        val fence = database.validationRecallCorpusFence()
        val generation = fence.snapshot()
        val transactionBodyReady = CompletableDeferred<Unit>()
        val allowTransactionToFinish = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            fence.withCanonicalMutation(
                change = { ValidationRecallCorpusChange.memoryIds(setOf("cancel-open-memory")) },
            ) {
                database.withTransaction {
                    insertTargetMemory(
                        id = "cancel-open-memory",
                        meaning = "Committed after caller cancellation.",
                    )
                    transactionBodyReady.complete(Unit)
                    allowTransactionToFinish.await()
                }
            }
        }
        try {
            withTimeout(10_000) { transactionBodyReady.await() }

            writer.cancel(CancellationException("cancel while Room transaction is open"))

            assertTrue(fence.isMutationInFlight())
            assertFalse(fence.matches(generation))
            assertFalse(writer.isCompleted)

            allowTransactionToFinish.complete(Unit)
            withTimeout(10_000) { writer.join() }

            assertTrue(writer.isCancelled)
            assertFalse(fence.isMutationInFlight())
            assertFalse(fence.matches(generation))
            assertEquals(generation.corpusGeneration + 1L, fence.snapshot().corpusGeneration)
            assertNotNull(database.memoryDao().memory("cancel-open-memory"))
        } finally {
            allowTransactionToFinish.complete(Unit)
            withTimeout(10_000) { writer.join() }
        }
    }

    @Test
    fun defaultRetrieverHydratesExactCanonicalComparisonBeforeDecision() = runBlocking {
        insertTargetMemory(meaning = "Shai likes rainy afternoons.")
        decider.decision = CandidateValidationDecision(
            CandidateValidationOutcome.REINFORCE_EXISTING,
            targetMemoryIds = listOf(TARGET),
        )
        val realService = CandidateValidationService(
            database = database,
            decider = decider,
            memoryIdGenerator = ValidatedMemoryIdGenerator { NEW_MEMORY },
        )

        val result = realService.validate(ValidateCandidateInput(CANDIDATE, VALIDATED_AT))

        assertTrue(result is CandidateValidationResult.ReinforcedExisting)
        assertEquals(listOf(TARGET), decider.snapshot?.relatedMemories?.map { it.memoryId })
        realService.close()
    }

    @Test
    fun closingOwnedDefaultRetrieverMakesServiceExplicitlyUnavailable() = runBlocking {
        val owned = CandidateValidationService(database = database, decider = decider)
        owned.close()

        val result = owned.validate(ValidateCandidateInput(CANDIDATE, VALIDATED_AT))

        val failure = error(result) as CandidateValidationError.ValidationRecallUnavailable
        assertEquals(ValidationRecallReadiness.NOT_READY, failure.readiness)
    }

    private fun newService(): CandidateValidationService = CandidateValidationService(
        database = database,
        retriever = retriever,
        decider = decider,
        memoryIdGenerator = ValidatedMemoryIdGenerator { NEW_MEMORY },
    )

    private fun replaceWithDiskWalDatabase() {
        database.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "candidate-validation-wal-${System.nanoTime()}.db"
        diskDatabaseName = name
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, RivenDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
        retriever = FakeRetriever()
        decider = FakeDecider(acceptDecision())
        service = newService()
        eventOrder = 10L
        insertGroundedCandidate()
    }

    private suspend fun validate(candidateId: String = CANDIDATE): CandidateValidationResult =
        service.validate(ValidateCandidateInput(candidateId, VALIDATED_AT))

    private fun insertGroundedCandidate() {
        insertExperience(SEED, sourceContent = "Shai likes rainy afternoons.")
        insertAttention(SEED)
        insertCandidate(CANDIDATE, SEED, LINEAGE)
    }

    private fun insertCandidate(
        candidateId: String,
        experienceId: String,
        lineage: String,
        state: CandidateMemoryState = CandidateMemoryState.READY_FOR_VALIDATION,
    ) {
        database.memoryDao().insertCandidateMemory(
            CandidateMemoryEntity(
                id = candidateId,
                proposedKind = MemoryKind.SEMANTIC,
                proposedScope = MemoryScope.SHAI,
                proposedMeaning = "Shai likes rainy afternoons.",
                proposedEpistemicBasis = EpistemicBasis.DIRECT_USER_STATEMENT,
                proposedCertainty = MemoryCertainty.PROBABLE,
                state = state,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 100,
                updatedAt = 100,
            ),
        )
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(candidateId, experienceId, 0, CandidateEvidenceRole.SEED, lineage, 100),
        )
    }

    private fun addCandidateEvidence(
        experienceId: String,
        role: CandidateEvidenceRole,
        lineage: String,
        candidateId: String = CANDIDATE,
    ) {
        if (database.memoryDao().experience(experienceId) == null) insertExperience(experienceId)
        val order = database.memoryDao().candidateEvidence(candidateId).size
        database.memoryDao().insertCandidateMemoryEvidence(
            CandidateMemoryEvidenceEntity(candidateId, experienceId, order, role, lineage, 101 + order.toLong()),
        )
    }

    private fun insertExperience(
        id: String,
        type: ExperienceType = ExperienceType.SHARED_EVENT,
        availability: ExperienceAvailability = ExperienceAvailability.AVAILABLE,
        sensitivity: SensitivityLevel = SensitivityLevel.STANDARD,
        sourceContent: String? = "Evidence $id",
    ) {
        database.memoryDao().insertExperience(
            ExperienceEntity(
                id = id,
                eventOrder = eventOrder++,
                experienceType = type,
                actor = ExperienceActor.SHAI,
                sourceContent = sourceContent,
                occurredAt = eventOrder * 10,
                recordedAt = eventOrder * 10,
                sensitivity = sensitivity,
                availability = availability,
            ),
        )
    }

    private fun insertAttention(
        experienceId: String,
        outcome: AttentionOutcome = AttentionOutcome.FORWARD_FOR_INTERPRETATION,
    ) {
        database.experienceAttentionDao().insertAssessment(
            ExperienceAttentionAssessmentEntity(experienceId, outcome, 1, 80, 80),
        )
        database.experienceAttentionDao().insertSignal(
            ExperienceAttentionSignalEntity(
                experienceId,
                AttentionSignal.IDENTITY,
                AttentionSignalPolarity.POSITIVE,
                80,
            ),
        )
    }

    private fun insertTargetMemory(
        id: String = TARGET,
        meaning: String = "Existing related understanding.",
        evidenceLineage: String = "existing-lineage",
        certainty: MemoryCertainty = MemoryCertainty.PROBABLE,
        significance: SignificanceLevel? = null,
        retention: MemoryRetentionState = MemoryRetentionState.ACTIVE,
        truth: MemoryTruthState = MemoryTruthState.SUPPORTED,
        lifecycle: MemoryLifecycleState = MemoryLifecycleState.VALIDATED,
        temporal: TemporalState = TemporalState.CURRENT,
        evidenceExperienceId: String? = null,
    ) {
        val experienceId = evidenceExperienceId ?: "existing-exp-$id"
        if (evidenceExperienceId == null) insertExperience(experienceId)
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
                learnedAt = 30,
                lastConfirmedAt = 30,
                identitySignificance = significance,
                sensitivity = SensitivityLevel.STANDARD,
                createdAt = 30,
                updatedAt = 30,
            ),
        )
        database.memoryDao().insertMemoryEvidence(
            MemoryEvidenceEntity(
                id,
                experienceId,
                EvidenceRole.SUPPORTS,
                EpistemicBasis.DIRECT_USER_STATEMENT,
                MemoryCertainty.PROBABLE,
                evidenceLineage,
                30,
            ),
        )
    }

    private fun reinforceDecision() {
        retriever.ids = listOf(TARGET)
        decider.decision = CandidateValidationDecision(
            CandidateValidationOutcome.REINFORCE_EXISTING,
            targetMemoryIds = listOf(TARGET),
        )
    }

    private fun refinementDecision(disposition: RefinementDisposition) {
        retriever.ids = listOf(TARGET)
        decider.decision = CandidateValidationDecision(
            CandidateValidationOutcome.REFINE_EXISTING,
            targetMemoryIds = listOf(TARGET),
            admission = admission(),
            refinementDisposition = disposition,
        )
    }

    private fun mutationDecision(
        outcome: CandidateValidationOutcome,
        metadata: ValidationAdmissionMetadata = admission(),
    ) {
        retriever.ids = listOf(TARGET)
        decider.decision = CandidateValidationDecision(
            outcome,
            targetMemoryIds = listOf(TARGET),
            admission = metadata,
        )
    }

    private fun mergeDecision() {
        retriever.ids = listOf(TARGET, "target-2")
        decider.decision = CandidateValidationDecision(
            CandidateValidationOutcome.MERGE,
            targetMemoryIds = retriever.ids,
        )
    }

    private fun insertConversationCandidate(
        candidateId: String,
        experienceId: String,
        active: Boolean,
        insertCandidate: Boolean = true,
    ) {
        val timelineDao = database.conversationTimelineDao()
        if (timelineDao.conversation(CONVERSATION) == null) {
            timelineDao.insertConversation(ConversationEntity(CONVERSATION, 1, 1, ConversationStatus.ACTIVE))
            timelineDao.insertMessage(message(SOURCE_MESSAGE, 1))
            timelineDao.insertMessage(message("alternate-head", 2))
            timelineDao.insertTimelineHead(
                ConversationTimelineHeadEntity(
                    CONVERSATION,
                    if (active) SOURCE_MESSAGE else "alternate-head",
                    1,
                    1,
                ),
            )
        } else {
            val head = timelineDao.timelineHead(CONVERSATION)!!
            timelineDao.updateTimelineHead(head.copy(activeHeadMessageId = if (active) SOURCE_MESSAGE else "alternate-head"))
        }
        insertExperience(experienceId, ExperienceType.CONVERSATION_MESSAGE)
        database.memoryDao().insertExperienceMessageSource(
            ExperienceMessageSourceEntity(
                experienceId,
                SOURCE_MESSAGE,
                0,
                ExperienceMessageSourceRole.PRIMARY,
                createdAt = 1,
            ),
        )
        if (insertCandidate) {
            insertAttention(experienceId)
            insertCandidate(candidateId, experienceId, "lineage-$experienceId")
        }
    }

    private fun message(id: String, sequence: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION,
        sequenceNumber = sequence,
        role = MessageRole.USER,
        deliveryState = MessageDeliveryState.PERSISTED,
        content = id,
        createdAt = sequence,
        updatedAt = sequence,
    )

    private fun moveConversationHead(messageId: String?) {
        val dao = database.conversationTimelineDao()
        val head = dao.timelineHead(CONVERSATION)!!
        dao.updateTimelineHead(head.copy(activeHeadMessageId = messageId, timelineRevision = head.timelineRevision + 1))
    }

    private fun insertUnavailableCandidate() {
        insertExperience("deleted-exp", availability = ExperienceAvailability.DELETED)
        insertAttention("deleted-exp")
        insertCandidate("candidate-deleted", "deleted-exp", "deleted-lineage")
    }

    private fun insertSuppression(kind: SuppressionKind, active: Boolean = true) {
        database.maintenanceDao().insertSuppressionTombstone(
            SuppressionTombstoneEntity(
                id = "tombstone",
                kind = kind,
                sourceLineageHash = sourceLineageHash(SEED, LINEAGE),
                isActive = active,
                createdAt = 101,
                formatVersion = 1,
            ),
        )
    }

    private fun replaceSeedSensitivity(sensitivity: SensitivityLevel) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE experiences SET sensitivity = ? WHERE experience_id = ?",
            arrayOf(sensitivity.name, SEED),
        )
    }

    private fun updateCandidate(transform: (CandidateMemoryEntity) -> CandidateMemoryEntity) {
        database.memoryDao().updateCandidateMemory(transform(candidate()))
    }

    private fun candidate(): CandidateMemoryEntity = database.memoryDao().candidateMemory(CANDIDATE)!!
    private fun memory(): MemoryEntity = database.memoryDao().memory(NEW_MEMORY)!!

    private fun relationshipCount(source: String, target: String, type: MemoryRelationshipType): Int =
        database.memoryDao().memoryRelationshipCount(source, target, type)

    private fun rowCount(table: String): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table")
        .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }

    private fun error(result: CandidateValidationResult): CandidateValidationError =
        (result as CandidateValidationResult.Failure).error

    private inner class FakeRetriever : ValidationMemoryRetriever {
        var ids: List<String> = emptyList()
        var query: ValidationMemoryQuery? = null
        var onRetrieve: suspend () -> Unit = {}
        var readiness: ValidationRecallReadiness = ValidationRecallReadiness.READY
        var generation: ValidationRecallGeneration? = null
        override suspend fun retrieve(query: ValidationMemoryQuery): ValidationMemoryRetrieval {
            this.query = query
            onRetrieve()
            return ValidationMemoryRetrieval(
                memoryIds = ids,
                readiness = readiness,
                generation = generation ?: database.validationRecallCorpusFence().snapshot(),
            )
        }
    }

    private class FakeDecider(var decision: CandidateValidationDecision) : CandidateValidationDecider {
        var snapshot: CandidateValidationSnapshot? = null
        var onDecide: suspend () -> Unit = {}
        override suspend fun decide(snapshot: CandidateValidationSnapshot): CandidateValidationDecision {
            this.snapshot = snapshot
            onDecide()
            return decision
        }
    }

    private class PausingDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        private val paused = AtomicBoolean(false)
        private val queued = ConcurrentLinkedQueue<Runnable>()

        val queuedTaskCount: Int
            get() = queued.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (paused.get()) {
                queued += block
            } else {
                delegate.dispatch(context, block)
            }
        }

        fun pause() {
            paused.set(true)
        }

        fun resumeQueued() {
            paused.set(false)
            while (true) {
                val task = queued.poll() ?: return
                task.run()
            }
        }
    }

    private companion object {
        const val CANDIDATE = "candidate"
        const val SEED = "seed"
        const val LINEAGE = "seed-lineage"
        const val TARGET = "target"
        const val NEW_MEMORY = "memory-new"
        const val VALIDATED_AT = 500L
        const val CONVERSATION = "conversation"
        const val SOURCE_MESSAGE = "source-message"

        fun admission(
            temporalState: TemporalState = TemporalState.CURRENT,
            validFrom: Long? = null,
            validUntil: Long? = null,
        ) = ValidationAdmissionMetadata(temporalState, validFrom, validUntil)

        fun acceptDecision(metadata: ValidationAdmissionMetadata = admission()) =
            CandidateValidationDecision(CandidateValidationOutcome.ACCEPT_NEW, admission = metadata)

        fun deferDecision(state: CandidateMemoryState) = CandidateValidationDecision(
            CandidateValidationOutcome.DEFER,
            deferState = state,
            deferReason = CandidateDeferReason.NEEDS_CONTEXT,
        )

        fun rejectDecision(reason: CandidateRejectReason = CandidateRejectReason.UNSUPPORTED) =
            CandidateValidationDecision(CandidateValidationOutcome.REJECT, rejectReason = reason)
    }
}
